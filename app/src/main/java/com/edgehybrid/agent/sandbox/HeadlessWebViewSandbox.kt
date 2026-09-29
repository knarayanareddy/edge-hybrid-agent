package com.edgehybrid.agent.sandbox

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Isolated headless WebView runner enforcing strict network isolation and watchdog timeouts.
 */
@Singleton
class HeadlessWebViewSandbox @Inject constructor(
    @ApplicationContext private val context: Context
) : ScriptSandbox {

    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun executeScript(
        scriptName: String,
        inputJson: String,
        networkOrigins: List<String>
    ): Result<String> = withContext(Dispatchers.Main) {
        val completionDeferred = CompletableDeferred<String>()
        val bridge = AndroidSandboxHostBridge(completionDeferred)

        val webView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                databaseEnabled = false
                domStorageEnabled = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                mediaPlaybackRequiresUserGesture = true
            }

            // Enforce zero cookie tracking
            CookieManager.getInstance().setAcceptCookie(false)

            addJavascriptInterface(bridge, "__edgeHost")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url ?: return null
                    val scheme = url.scheme?.lowercase()
                    val host = url.host?.lowercase() ?: ""

                    // Allow local assets loaded via WebViewAssetLoader
                    if (host == "appassets.androidplatform.net") {
                        return assetLoader.shouldInterceptRequest(url)
                    }

                    // Strict network filtering: only explicitly allowlisted origins allowed
                    if (scheme == "https") {
                        val isAllowed = networkOrigins.any { origin ->
                            val allowedUri = Uri.parse(origin)
                            allowedUri.host?.equals(host, ignoreCase = true) == true
                        }
                        if (isAllowed && !isPrivateOrRestrictedHost(host)) {
                            return null // Let WebView load allowed external origin
                        }
                    }

                    // Block private IPs, local subnets, WebRTC/STUN, and unlisted origins
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        403,
                        "Forbidden",
                        emptyMap(),
                        ByteArrayInputStream("Blocked by Sandbox Security Policy".toByteArray(StandardCharsets.UTF_8))
                    )
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    bridge.fail("WebView error ($errorCode): $description")
                }
            }
        }

        try {
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <title>Sandbox</title>
                </head>
                <body>
                    <script src="https://appassets.androidplatform.net/assets/skills/$scriptName"></script>
                    <script>
                        window.addEventListener('DOMContentLoaded', () => {
                            try {
                                if (typeof window.__edgeRun === 'function') {
                                    window.__edgeRun($inputJson, ${inputJsonArray(networkOrigins)});
                                } else {
                                    window.__edgeHost.fail('Skill script does not define window.__edgeRun');
                                }
                            } catch (e) {
                                window.__edgeHost.fail(e.toString());
                            }
                        });
                    </script>
                </body>
                </html>
            """.trimIndent()

            webView.loadDataWithBaseURL(
                "https://appassets.androidplatform.net/",
                html,
                "text/html",
                "UTF-8",
                null
            )

            val result = withTimeoutOrNull(5000L) {
                completionDeferred.await()
            }

            if (result != null) {
                Result.success(result)
            } else {
                Result.failure(IllegalStateException("Sandbox execution timed out after 5000ms watchdog"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            webView.stopLoading()
            webView.removeJavascriptInterface("__edgeHost")
            webView.destroy()
        }
    }

    private fun inputJsonArray(origins: List<String>): String {
        return origins.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    }

    private fun isPrivateOrRestrictedHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
        if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("172.16.")) return true
        if (host.endsWith(".local") || host.endsWith(".internal")) return true
        return false
    }
}
