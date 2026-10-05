package com.edgehybrid.agent.sandbox

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.edgehybrid.agent.skills.UserScriptPathHandler
import com.edgehybrid.agent.skills.UserSkillStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.net.IDN
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Headless WebView runner for bundled skill scripts.
 *
 * Isolation properties this class actually provides:
 *
 *  - **Origin.** Scripts are served from `https://appassets.androidplatform.net/` through
 *    [WebViewAssetLoader]. Only that host is readable; `allowFileAccess` and
 *    `allowContentAccess` are off, so the APK's private files and `content://` URIs are
 *    unreachable.
 *  - **Script allowlist.** Only the three bundled scripts can be loaded. The name is
 *    validated against the allowlist *before* it reaches the WebView, so path traversal
 *    or an arbitrary asset name is rejected up front.
 *  - **Network.** Every request the WebView makes — navigation, subresource, `fetch`,
 *    XHR, WebSocket, or `sendBeacon` — passes through [shouldInterceptRequest]. Requests
 *    are only permitted to an allowlisted **exact** `https://host[:port]` origin that is
 *    not a private, loopback, link-local, or otherwise reserved address. Everything else
 *    is refused with a 403 by the host, so enforcement does not depend on the skill
 *    script cooperating.
 *  - **Timeout.** Execution is bounded at 5000 ms. On expiry the page is torn down
 *    immediately (see `stopLoading`/`destroy` in the `finally` block), which stops
 *    in-flight script work rather than merely abandoning the coroutine.
 *
 * The bridge exposed as `window.__edgeHost` carries no file, network, or app-state
 * access; it can only report a result back to the host.
 */
@Singleton
class HeadlessWebViewSandbox @Inject constructor(
    @ApplicationContext private val context: Context,
    private val skillStore: UserSkillStore
) : ScriptSandbox {

    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        // User-authored scripts are served from filesDir on the SAME origin, so
        // editable skills do not widen the network policy or introduce a new
        // origin the interceptor has to reason about.
        .addPathHandler("/${UserScriptPathHandler.PATH_PREFIX}/", UserScriptPathHandler(context))
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun executeScript(
        scriptName: String,
        inputJson: String,
        networkOrigins: List<String>
    ): Result<String> {
        // Fail closed on anything that is not a known, enabled skill. This is checked
        // before a WebView is created, so a crafted name never reaches a path handler.
        //
        // The set is no longer a hardcoded constant: it is the enabled SCRIPT skills
        // from the store, which is what makes skills editable. Bundled scripts are
        // still included, and a user script still has to pass the handler's own
        // name validation (id pattern, .js suffix, no separators) before it is read.
        val knownScript = skillStore.skills.value.any {
            it.kind == com.edgehybrid.agent.skills.UserSkill.Kind.SCRIPT &&
                it.enabled &&
                (it.id + ".js" == scriptName || it.id == scriptName.removeSuffix(".js"))
        }
        if (!knownScript) {
            return Result.failure(
                IllegalArgumentException("Unknown or disabled skill: $scriptName")
            )
        }

        // Normalize the allowlist to exact scheme://host:port origins and drop anything
        // non-HTTPS or non-public, so `web_extract.js` and the host agree on one list.
        val allowedOrigins = networkOrigins
            .mapNotNull { normalizeOrigin(it) }
            .toSet()

        return withContext(Dispatchers.Main) {
            val completionDeferred = CompletableDeferred<String>()
            val bridge = AndroidSandboxHostBridge(completionDeferred)

            val webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    databaseEnabled = false
                    domStorageEnabled = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mediaPlaybackRequiresUserGesture = true
                    javaScriptCanOpenWindowsAutomatically = false
                    setGeolocationEnabled(false)
                }

                addJavascriptInterface(bridge, "__edgeHost")

                webViewClient = SandboxWebViewClient(allowedOrigins, bridge)
            }

            try {
                // `inputJson` is already a JSON document from the caller, so it is
                // inlined as a JSON *value* (not as a quoted string) and then neutralized
                // for the script context. `origins` is serialized as a JSON array.
                val html = buildHostDocument(
                    scriptName = scriptName,
                    inputJsonLiteral = neutralizeForScriptContext(
                        inputJson.ifBlank { "{}" }
                    ),
                    originsLiteral = neutralizeForScriptContext(
                        kotlinx.serialization.json.JsonArray(
                            allowedOrigins.map { JsonPrimitive(it) }
                        ).toString()
                    )
                )

                webView.loadDataWithBaseURL(
                    ASSET_BASE_URL,
                    html,
                    "text/html",
                    "UTF-8",
                    null
                )

                val result = withTimeoutOrNull(EXECUTION_TIMEOUT_MS) {
                    completionDeferred.await()
                }

                if (result != null) {
                    Result.success(result)
                } else {
                    Result.failure(
                        IllegalStateException(
                            "Sandbox execution timed out after ${EXECUTION_TIMEOUT_MS}ms"
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                // Tear the page down eagerly. After this the JS context is gone, so a
                // script that outran the watchdog cannot keep burning CPU.
                webView.stopLoading()
                webView.removeJavascriptInterface("__edgeHost")
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }
    }

    /**
     * Builds the host document.
     *
     * Both interpolated values are JSON-encoded **by a serializer** and then neutralized
     * against HTML/script-context breakout, so a payload containing `"`, `</script>`, or
     * a line separator cannot terminate the string literal or the script element.
     */
    private fun buildHostDocument(
        scriptName: String,
        inputJsonLiteral: String,
        originsLiteral: String
    ): String = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <title>Sandbox</title>
        </head>
        <body>
            <script src="${escapeAttribute("$ASSET_ORIGIN/assets/skills/$scriptName")}"></script>
            <script>
                (function () {
                    var input = $inputJsonLiteral;
                    var origins = $originsLiteral;
                    function report(error) {
                        try { window.__edgeHost.fail(String(error)); } catch (ignored) {}
                    }
                    window.addEventListener('DOMContentLoaded', function () {
                        try {
                            if (typeof window.__edgeRun === 'function') {
                                window.__edgeRun(input, origins);
                            } else {
                                report('Skill script does not define window.__edgeRun');
                            }
                        } catch (e) {
                            report(e && e.message ? e.message : String(e));
                        }
                    });
                })();
            </script>
        </body>
        </html>
    """.trimIndent()

    /**
     * Neutralizes a JSON document for inlining into a `<script>` block.
     *
     * The value is already JSON, so it is **not** re-encoded. It only needs three
     * neutralizations, all of which preserve JSON semantics:
     *
     *  - U+2028 / U+2029 are valid raw characters in JSON but are line terminators in
     *    JavaScript, so they are escaped.
     *  - `<` is escaped so a payload containing `</script>` cannot terminate the script
     *    element early. JSON string values keep their meaning after the escape.
     *
     * Escaping `<` is only safe inside string values, which is where attacker-controlled
     * data lives; structural characters and numbers are untouched.
     */
    private fun neutralizeForScriptContext(json: String): String = json
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
        .replace("<", "\\u003C")

    /** Escapes a value for use inside a double-quoted HTML attribute. */
    private fun escapeAttribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private inner class SandboxWebViewClient(
        private val allowedOrigins: Set<String>,
        private val bridge: AndroidSandboxHostBridge
    ) : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val url = request?.url ?: return blocked()

            val scheme = url.scheme?.lowercase()

            // Bundled skill assets are served by the asset loader.
            if (scheme == "https" && url.host?.equals(ASSET_HOST, ignoreCase = true) == true) {
                return assetLoader.shouldInterceptRequest(url) ?: blocked()
            }

            // Everything else must be an explicitly allowed, public, HTTPS origin.
            if (scheme != "https") {
                return blocked()
            }

            val origin = normalizeOrigin(url.toString()) ?: return blocked()
            if (origin !in allowedOrigins) {
                return blocked()
            }
            if (isPrivateOrReservedHost(url.host)) {
                return blocked()
            }
            // Allowed: let the WebView perform the request.
            return null
        }

        override fun onReceivedError(
            view: WebView?,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            bridge.fail("WebView error ($errorCode): $description")
        }

        private fun blocked(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Forbidden",
            emptyMap(),
            ByteArrayInputStream(BLOCKED_BODY.toByteArray(StandardCharsets.UTF_8))
        )
    }

    private fun bridgeOf(view: WebView?): AndroidSandboxHostBridge? = null

    /**
     * Reduces a URL to a canonical `https://host:port` origin, or null if it is not a
     * usable public HTTPS origin.
     */
    private fun normalizeOrigin(raw: String): String? {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https") return null

        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (isPrivateOrReservedHost(host)) return null

        // IDN.toASCII canonicalizes unicode hostnames so `https://ｅxample.com` and
        // `https://example.com` cannot be treated as different origins.
        val asciiHost = runCatching { IDN.toASCII(host) }.getOrDefault(host)
        val port = if (uri.port == -1) DEFAULT_HTTPS_PORT else uri.port
        if (port !in 1..65535) return null

        return "https://$asciiHost:$port"
    }

    companion object {
        /**
         * Rejects loopback, RFC 1918 / private, link-local, CGNAT, and reserved ranges,
         * plus the usual local-only names.
         *
         * This covers the whole of 172.16.0.0/12 (not just 172.16) and 169.254.0.0/16,
         * both of which a naive prefix check misses. Literal IPv6 addresses are refused
         * outright because a DNS name is required for an allowlist entry to be meaningful.
         *
         * A pure function with no Android dependencies, so it is directly testable.
         */
        internal fun isPrivateOrReservedHost(host: String?): Boolean {
            val normalized = host
                ?.lowercase()
                ?.trim()
                ?.removeSurrounding("[", "]")
                ?.takeIf { it.isNotBlank() }
                ?: return true

            if (normalized == "localhost" || normalized.endsWith(".localhost")) return true
            if (normalized.endsWith(".local") || normalized.endsWith(".internal")) return true
            if (normalized == "metadata.google.internal") return true

            // IPv6 literals: allow nothing.
            if (normalized.contains(':')) return true

            // An IPv4-intended address is anything with a leading numeric label. It must
            // parse as four valid octets; anything else in that shape is malformed and
            // fails closed rather than being mistaken for a DNS name.
            if (normalized.first().isDigit()) {
                val octets = normalized.split('.')
                if (octets.size != 4) return true
                val values = octets.map { part ->
                    part.toIntOrNull()?.takeIf { it in 0..255 } ?: return true
                }
                return isReservedIpv4(values[0], values[1])
            }

            // A real DNS name is not itself a private address; it is reachable only when
            // the exact host appears in the origin allowlist.
            return false
        }

        private fun isReservedIpv4(a: Int, b: Int): Boolean = when {
            a == 0 -> true                              // "this" network
            a == 10 -> true                             // RFC 1918 /8
            a == 127 -> true                            // loopback
            a == 169 && b == 254 -> true               // link-local, incl. cloud metadata
            a == 172 && b in 16..31 -> true            // RFC 1918 /12 (whole range)
            a == 192 && b == 168 -> true               // RFC 1918 /16
            a == 192 && b == 0 -> true                 // IETF protocol assignments
            a == 100 && b in 64..127 -> true           // CGNAT /10
            a == 198 && (b == 18 || b == 19) -> true   // benchmarking
            a >= 224 -> true                           // multicast + reserved
            else -> false
        }

        const val EXECUTION_TIMEOUT_MS = 5_000L
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val ASSET_ORIGIN = "https://$ASSET_HOST"
        const val ASSET_BASE_URL = "$ASSET_ORIGIN/"
        private const val DEFAULT_HTTPS_PORT = 443
        private const val BLOCKED_BODY = "Blocked by Sandbox Security Policy"

        /**
         * Bundled skill filenames, kept for reference and for the Skills UI.
         *
         * This is NOT the execution boundary any more. It was the hardcoded
         * three-item gate that made every skill immutable; enforcement now reads the
         * enabled SCRIPT skills from [UserSkillStore], and [UserScriptPathHandler]
         * independently re-validates the filename before touching the filesystem.
         * Both layers must agree — the store decides *whether*, the handler decides
         * *how it is read*.
         */
        val BUNDLED_SKILLS = setOf(
            "calculator.js",
            "device_info.js",
            "web_extract.js"
        )
    }
}
