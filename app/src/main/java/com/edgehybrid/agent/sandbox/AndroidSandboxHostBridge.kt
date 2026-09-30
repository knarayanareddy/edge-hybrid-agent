package com.edgehybrid.agent.sandbox

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host bridge exposed to the sandbox WebView as `window.__edgeHost`.
 *
 * The three exposed methods can only signal completion, failure, or emit a log line.
 * None of them reads or writes files, makes network calls, or reaches app state, so a
 * script cannot escalate from the sandbox into the host through this interface.
 */
class AndroidSandboxHostBridge(
    private val completionDeferred: CompletableDeferred<String>
) {
    private val completed = AtomicBoolean(false)

    @JavascriptInterface
    fun complete(resultJson: String) {
        if (completed.compareAndSet(false, true)) {
            completionDeferred.complete(resultJson)
        }
    }

    @JavascriptInterface
    fun fail(errorMessage: String) {
        if (completed.compareAndSet(false, true)) {
            completionDeferred.completeExceptionally(
                IllegalStateException(
                    "Sandbox execution error: ${errorMessage.take(MAX_MESSAGE_CHARS)}"
                )
            )
        }
    }

    /**
     * Receives a diagnostic line from a skill script.
     *
     * The message is control-character stripped and length-capped before it reaches
     * logcat, so a script cannot forge log lines or flood the buffer.
     */
    @JavascriptInterface
    fun log(message: String) {
        val sanitized = message
            .map { ch -> if (ch.isISOControl()) '.' else ch }
            .joinToString("")
            .take(MAX_MESSAGE_CHARS)
        android.util.Log.d(LOG_TAG, "[JS LOG] $sanitized")
    }

    private companion object {
        const val LOG_TAG = "HeadlessSandbox"
        const val MAX_MESSAGE_CHARS = 500
    }
}
