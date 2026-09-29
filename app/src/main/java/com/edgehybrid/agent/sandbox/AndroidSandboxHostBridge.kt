package com.edgehybrid.agent.sandbox

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe JavaScript interface exposed to the headless WebView as `window.__edgeHost`.
 * Safely resumes coroutines without race conditions or multiple resumes.
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
                IllegalStateException("Sandbox execution error: $errorMessage")
            )
        }
    }

    @JavascriptInterface
    fun log(message: String) {
        android.util.Log.d("HeadlessSandbox", "[JS LOG] $message")
    }
}
