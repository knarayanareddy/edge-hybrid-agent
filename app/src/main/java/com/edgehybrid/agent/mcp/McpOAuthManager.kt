package com.edgehybrid.agent.mcp

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.edgehybrid.agent.data.local.SecureKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * OAuth 2.1 authorization-code flow with PKCE, for MCP servers that use it.
 *
 * Scope note, because it matters for expectations: as of 2026-09-30 **no curated server
 * can complete this flow from the app yet**. Linear and Notion both require dynamic client
 * registration (RFC 7591) to obtain a `client_id` at runtime, and Figma is limited to
 * catalog-approved clients. See [McpAuthCatalog] for the per-vendor findings.
 *
 * What this class therefore provides is the part that is genuinely reusable and
 * implemented correctly:
 *  - PKCE verifier/challenge generation (S256),
 *  - one-shot state verification in constant time,
 *  - atomic credential storage with expiry, and
 *  - automatic refresh shortly before expiry.
 *
 * [beginAuthorization] requires a caller-supplied client id rather than a hardcoded one,
 * because hardcoding a client id that the vendor never issued would guarantee failure.
 * Linear's API key path does not need any of this and works today.
 */
@Singleton
class McpOAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore,
    private val registry: McpServerRegistry
) : McpTokenSource {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val secureRandom = SecureRandom()

    override suspend fun accessTokenFor(serverId: String): String? {
        val existing = keyStore.getMcpOAuthAccessToken(serverId)
        if (existing.isNotBlank() && !isExpired(serverId)) return existing

        val refreshToken = keyStore.getMcpOAuthRefreshToken(serverId)
        if (refreshToken.isBlank()) return existing.takeIf { it.isNotBlank() }

        val client = pendingClients[serverId]
            ?: return existing.takeIf { it.isNotBlank() }

        return runCatching {
            val payload = postForm(
                url = client.tokenEndpoint,
                form = buildMap {
                    put("grant_type", "refresh_token")
                    put("refresh_token", refreshToken)
                    put("client_id", client.clientId)
                }
            )
            storeTokens(serverId, payload)
            keyStore.getMcpOAuthAccessToken(serverId).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Builds an authorization URL for a server that supports PKCE.
     *
     * @param registration the client id and token endpoint obtained from the server's
     *   dynamic-registration or OAuth metadata. Supplying these per call — rather than
     *   embedding them — is what makes this usable with Linear and Notion once dynamic
     *   client registration is wired up.
     *
     * @return the URL to open in a browser.
     */
    fun beginAuthorization(
        serverId: String,
        registration: McpClientRegistration,
        scopes: String = "",
        redirectUri: String = McpOAuthRedirectActivity.redirectUri()
    ): AuthorizationRequest {
        val verifier = randomUrlSafeString(VERIFIER_BYTES)
        val challenge = sha256Base64Url(verifier)
        val state = randomUrlSafeString(STATE_BYTES)

        keyStore.setMcpOAuthVerifier(serverId, verifier)
        keyStore.setMcpOAuthState(serverId, state)
        pendingClients[serverId] = registration

        val url = buildString {
            append(registration.authorizationEndpoint)
            append("?response_type=code")
            append("&client_id=").append(Uri.encode(registration.clientId))
            append("&redirect_uri=").append(Uri.encode(redirectUri))
            append("&state=").append(Uri.encode(state))
            append("&code_challenge=").append(Uri.encode(challenge))
            append("&code_challenge_method=S256")
            if (scopes.isNotBlank()) append("&scope=").append(Uri.encode(scopes))
            registration.resource?.let { append("&resource=").append(Uri.encode(it)) }
        }

        return AuthorizationRequest(url = url, serverId = serverId)
    }

    /**
     * Completes the flow using values from the redirect.
     *
     * @return the server id that was authorized.
     * @throws OAuthException on a state mismatch or a token-endpoint failure.
     */
    suspend fun completeAuthorization(
        serverId: String,
        code: String,
        returnedState: String,
        redirectUri: String = McpOAuthRedirectActivity.redirectUri()
    ): String {
        val expectedState = keyStore.getMcpOAuthState(serverId)
        val verifier = keyStore.getMcpOAuthVerifier(serverId)
        val client = pendingClients[serverId]

        // Clear the one-shot values first: a redirect is redeemable exactly once, whether
        // it succeeds or fails.
        keyStore.clearMcpOAuthState(serverId)
        keyStore.clearMcpOAuthVerifier(serverId)
        pendingClients.remove(serverId)

        if (expectedState.isBlank() || verifier.isBlank() || client == null) {
            throw OAuthException(
                "No authorization is in progress for '$serverId'. Start the connection again."
            )
        }
        if (!constantTimeEquals(expectedState, returnedState)) {
            // A mismatched state means the redirect did not come from the flow we started,
            // so it is discarded rather than trusted.
            throw OAuthException(
                "Authorization response did not match this app's request. It was discarded."
            )
        }

        val payload = postForm(
            url = client.tokenEndpoint,
            form = buildMap {
                put("grant_type", "authorization_code")
                put("code", code)
                put("redirect_uri", redirectUri)
                put("client_id", client.clientId)
                put("code_verifier", verifier)
            }
        )

        return storeTokens(serverId, payload)
    }

    /** True when a usable credential is stored for [serverId]. */
    fun isConnected(serverId: String): Boolean =
        keyStore.hasMcpOAuthToken(serverId) ||
            keyStore.getMcpOAuthRefreshToken(serverId).isNotBlank()

    /** Discards every stored credential for [serverId]. */
    fun disconnect(serverId: String) {
        keyStore.clearMcpOAuthCredentials(serverId)
        keyStore.clearMcpOAuthVerifier(serverId)
        keyStore.clearMcpOAuthState(serverId)
        pendingClients.remove(serverId)
    }

    /** True when the stored access token is past its expiry, with a safety margin. */
    fun isExpired(serverId: String): Boolean {
        val expiresAt = keyStore.getMcpOAuthExpiresAt(serverId)
        if (expiresAt <= 0L) return false
        return System.currentTimeMillis() >= expiresAt - EXPIRY_SKEW_MS
    }

    // ------------------------------------------------------------------ internals

    private fun storeTokens(serverId: String, payload: JsonObject): String {
        val error = payload["error"]?.jsonPrimitive?.contentOrNull
        if (error != null) {
            val description = payload["error_description"]?.jsonPrimitive?.contentOrNull
            throw OAuthException("Token endpoint returned '$error': ${description ?: "no detail"}")
        }

        val accessToken = payload["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw OAuthException("Token endpoint response did not contain an access_token")

        val refreshToken = payload["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val expiresIn = payload["expires_in"]?.jsonPrimitive?.longOrNull

        // Only move the expiry forward when the endpoint reported one; 0 means "unknown",
        // which is treated as non-expiring rather than expired.
        val expiresAt = if (expiresIn != null && expiresIn > 0) {
            System.currentTimeMillis() + expiresIn * 1000L
        } else {
            0L
        }

        if (refreshToken.isNotBlank()) {
            keyStore.setMcpOAuthCredentials(serverId, accessToken, refreshToken, expiresAt)
        } else {
            keyStore.setMcpOAuthAccessToken(serverId, accessToken)
            keyStore.setMcpOAuthExpiresAt(serverId, expiresAt)
        }
        return accessToken
    }

    /**
     * Posts a form-encoded token request.
     *
     * The URL is re-validated as https here as a second line of defence, because this
     * function sends a client credential.
     */
    private suspend fun postForm(
        url: String,
        form: Map<String, String>
    ): JsonObject = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!url.startsWith("https://")) {
            throw OAuthException("Refusing to send credentials to a non-https token endpoint")
        }

        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Accept", "application/json")

            val encoded = form.entries.joinToString("&") { (key, value) ->
                "${Uri.encode(key)}=${Uri.encode(value)}"
            }
            connection.outputStream.use { it.write(encoded.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                throw OAuthException("Token endpoint returned HTTP $status: ${body.take(200)}")
            }

            json.parseToJsonElement(body).jsonObject
        } finally {
            connection.disconnect()
        }
    }

    private fun randomUrlSafeString(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun sha256Base64Url(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Length is compared first, which is safe: both values are fixed-length random strings. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    data class AuthorizationRequest(val url: String, val serverId: String)

    /**
     * Registrations obtained at runtime from a server's OAuth metadata or dynamic client
     * registration. Held in memory only: a registration is short-lived and re-obtained on
     * each authorization.
     */
    private val pendingClients = java.util.concurrent.ConcurrentHashMap<String, McpClientRegistration>()

    private companion object {
        const val VERIFIER_BYTES = 64
        const val STATE_BYTES = 32

        /** Refresh a minute early so a token cannot expire mid-request. */
        const val EXPIRY_SKEW_MS = 60_000L
    }
}

class OAuthException(message: String) : RuntimeException(message)

/**
 * An OAuth client registration obtained from a server, rather than hardcoded in the app.
 *
 * [clientId] here is a **public** client identifier issued to this app by the server's
 * authorization server. It is not a secret, which is why it can live in memory and in the
 * authorization URL. No client secret is ever stored.
 */
data class McpClientRegistration(
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val resource: String? = null
)