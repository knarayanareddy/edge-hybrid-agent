package com.edgehybrid.agent.skills

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File

/**
 * Serves user-authored skill scripts to the sandbox WebView.
 *
 * ## Why this exists
 *
 * The sandbox previously read scripts only from `assets/`, which is baked into the
 * signed APK and therefore immutable. Making skills editable means reading from
 * `filesDir` instead, and the obvious shortcuts are all unsafe:
 *
 *  - **`file://`** — the WebView already sets `allowFileAccess = false`, and
 *    re-enabling it to serve user scripts would hand the sandbox the whole
 *    filesystem.
 *  - **Inlining user JS into the HTML document** — a script containing
 *    `</script>` terminates the element and escapes into the host page. The
 *    existing neutralization helpers cover *data*, but a script body is code, and
 *    code needs a real boundary rather than escaping.
 *  - **A new origin per user script** — widens the network policy surface the
 *    `shouldInterceptRequest` block has to reason about.
 *
 * This handler keeps user scripts on the **same** `appassets.androidplatform.net`
 * origin as bundled ones, under a distinct path prefix. No new origin, no inlining,
 * no escaping problem, and the existing allow-by-path logic still applies.
 */
class UserScriptPathHandler(
    @ApplicationContext private val context: Context
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        // `path` arrives URL-decoded and is still attacker-controlled (a crafted
        // name comes from a model tool call), so validate rather than assume.
        if (path.isBlank() || !path.endsWith(".js")) return null
        if (path.contains("..") || path.contains('/') || path.contains('\\')) return null
        if (!Regex("^[A-Za-z0-9_-]{1,64}\\.js$").matches(path)) return null

        val file = File(File(context.filesDir, "skills/scripts"), path)
        if (!file.isFile || !file.canRead()) return null

        // Cap the read so a huge file cannot exhaust memory on load.
        if (file.length() > UserSkill.MAX_SCRIPT_CHARS) return null

        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return WebResourceResponse(
            "application/javascript",
            "utf-8",
            bytes.inputStream()
        )
    }

    companion object {
        /** Path prefix; distinct from `/assets/` so bundled and user scripts cannot collide. */
        const val PATH_PREFIX = "userscripts"
    }
}