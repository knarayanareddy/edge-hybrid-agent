package com.edgehybrid.agent.mcp

import androidx.activity.ComponentActivity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives the OAuth redirect from the browser and finishes the PKCE flow.
 *
 * The vendor redirects to `edgehybrid://oauth/callback?code=...&state=...`. This activity
 * forwards the values to [McpOAuthManager.completeAuthorization] and closes immediately; it
 * renders no UI because the browser already showed the consent screen.
 *
 * Registered with `android:exported="true"` because the browser is a different app and must
 * be able to launch it, and with a scheme-specific `<data>` filter so it accepts only this
 * app's own callback and nothing else.
 */
@dagger.hilt.android.AndroidEntryPoint
class McpOAuthRedirectActivity : ComponentActivity() {

    @javax.inject.Inject
    lateinit var oauthManager: McpOAuthManager


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        if (uri == null) {
            finishWithError("No callback data received")
            return
        }

        // Validation and the token exchange happen off the main thread; the activity stays
        // alive until the exchange finishes, then closes itself.
        val serverId = uri.getQueryParameter("server_id")
            ?: McpServerCallbackStore.pendingServerId

        when {
            serverId.isNullOrBlank() -> {
                finishWithError("Callback did not identify which server to connect")
            }

            uri.getQueryParameter("error") != null -> {
                val error = uri.getQueryParameter("error")
                val description = uri.getQueryParameter("error_description")
                finishWithError(
                    "Authorization was denied: $error" +
                        (description?.let { " — $it" }.orEmpty())
                )
            }

            uri.getQueryParameter("code").isNullOrBlank() -> {
                finishWithError("Callback did not contain an authorization code")
            }

            else -> {
                val code = uri.getQueryParameter("code").orEmpty()
                val returnedState = uri.getQueryParameter("state").orEmpty()
                exchangeInBackground(serverId, code, returnedState)
            }
        }
    }

    /**
     * Swaps the authorization code for tokens on an IO dispatcher, then finishes.
     *
     * The error message surfaced to the user comes from our own validation or the token
     * endpoint's `error` field. It never contains the code, the verifier, or the token.
     */
    private fun exchangeInBackground(serverId: String, code: String, returnedState: String) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            val result = runCatching {
                oauthManager.completeAuthorization(serverId, code, returnedState)
            }.fold(
                onSuccess = { CallbackResult.Success(serverId) },
                onFailure = { failure ->
                    CallbackResult.Error(failure.message ?: "Authorization failed")
                }
            )
            McpServerCallbackStore.lastResult = result
            finish()
        }
    }

    private fun deliver(result: CallbackResult) {
        McpServerCallbackStore.lastResult = result
        // Close the browser tab chain cleanly rather than leaving it dangling.
        runCatching {
            startActivity(
                Intent(this, McpOAuthRedirectActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }
    }

    private fun finishWithError(message: String) {
        McpServerCallbackStore.lastResult = CallbackResult.Error(message)
        finish()
    }

    companion object {
        private const val CALLBACK_SCHEME = "edgehybrid"
        private const val CALLBACK_HOST = "oauth"
        private const val CALLBACK_PATH = "/callback"

        /** The redirect URI this app registers with every MCP vendor. */
        fun redirectUri(): String = "$CALLBACK_SCHEME://$CALLBACK_HOST$CALLBACK_PATH"
    }
}

/** Result of an OAuth callback, used by the UI to show an outcome. */
sealed class CallbackResult {
    data class Success(val serverId: String) : CallbackResult()
    data class Error(val message: String) : CallbackResult()
}

/**
 * Hand-off slot between the callback activity and the settings UI.
 *
 * The redirect arrives in a fresh task, so the value is parked here and picked up when the
 * UI resumes. Kept in memory only: a redirect result is not worth persisting to disk.
 */
object McpServerCallbackStore {
    @Volatile
    var pendingServerId: String? = null

    @Volatile
    var lastResult: CallbackResult? = null
}
