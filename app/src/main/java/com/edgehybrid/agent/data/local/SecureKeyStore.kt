package com.edgehybrid.agent.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure on-device key-value store using Android Keystore and EncryptedSharedPreferences (AES-256 GCM).
 * Holds sensitive API tokens, endpoints, and persistent agent configurations.
 */
@Singleton
class SecureKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILENAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getOpenRouterApiKey(): String = getOpenRouterApiKey(0)

    /**
     * Parses `BuildConfig.CLOUD_KEY_POOL` into the ordered rotation pool.
     *
     * The pool is a comma-separated BuildConfig string rather than a fixed set of
     * fields so the size is not baked into the schema. Blank entries are dropped:
     * a partially configured pool must not rotate onto an empty key, which would
     * send an unauthenticated request and look like a provider fault.
     */
    private fun keyPool(): List<String> =
        com.edgehybrid.agent.BuildConfig.CLOUD_KEY_POOL
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    fun getOpenRouterApiKey(index: Int): String {
        if (index < 0) return ""
        // Slot 0 honours the in-app Settings value, which is the user's own override
        // and should win over whatever the build compiled in.
        if (index == 0) {
            val saved = prefs.getString(KEY_OPENROUTER_API_KEY, "") ?: ""
            return if (saved.isNotBlank()) saved else keyPool().firstOrNull() ?: ""
        }
        // System.getenv is empty on Android, so BuildConfig is the only real source
        // for the spares. The env lookup is kept for host-side unit tests.
        val fromEnv = runCatching { System.getenv("OPENROUTER_API_KEY_$index") }.getOrNull()
        if (!fromEnv.isNullOrBlank()) return fromEnv
        return keyPool().getOrNull(index) ?: ""
    }

    /**
     * Returns the next available OpenRouter key index after a 429, or -1 if
     * exhausted. The caller is responsible for persisting the chosen index if
     * it should stick across sessions.
     */
    fun getNextOpenRouterKeyIndex(currentIndex: Int): Int {
        val next = currentIndex + 1
        return if (getOpenRouterApiKey(next).isNotBlank()) next else -1
    }
    fun setOpenRouterApiKey(key: String) = prefs.edit().putString(KEY_OPENROUTER_API_KEY, key.trim()).apply()

    fun getTypeSafeApiKey(): String {
        val saved = prefs.getString(KEY_TYPESAFE_API_KEY, "") ?: ""
        if (saved.isNotBlank()) return saved
        return com.edgehybrid.agent.BuildConfig.TYPESAFE_API_KEY.takeIf { it.isNotBlank() } ?: ""
    }
    fun setTypeSafeApiKey(key: String) = prefs.edit().putString(KEY_TYPESAFE_API_KEY, key.trim()).apply()

    fun getGeminiApiKey(): String {
        val saved = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        if (saved.isNotBlank()) return saved
        return com.edgehybrid.agent.BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() } ?: ""
    }
    fun setGeminiApiKey(key: String) = prefs.edit().putString(KEY_GEMINI_API_KEY, key.trim()).apply()

    fun getGroqApiKey(): String {
        val saved = prefs.getString(KEY_GROQ_API_KEY, "") ?: ""
        if (saved.isNotBlank()) return saved
        return com.edgehybrid.agent.BuildConfig.GROQ_API_KEY.takeIf { it.isNotBlank() } ?: ""
    }
    fun setGroqApiKey(key: String) = prefs.edit().putString(KEY_GROQ_API_KEY, key.trim()).apply()

    fun getTelegramBotToken(): String = prefs.getString(KEY_TELEGRAM_BOT_TOKEN, "") ?: ""
    fun setTelegramBotToken(token: String) = prefs.edit().putString(KEY_TELEGRAM_BOT_TOKEN, token.trim()).apply()

    fun getTelegramChatId(): String = prefs.getString(KEY_TELEGRAM_CHAT_ID, "") ?: ""
    fun setTelegramChatId(chatId: String) = prefs.edit().putString(KEY_TELEGRAM_CHAT_ID, chatId.trim()).apply()

    /**
     * Bearer tokens for registered MCP servers.
     *
     * Stored here rather than in the Room notes table so credentials are always
     * AndroidKeyStore-encrypted at rest. Never returns these values to the model layer.
     */
    fun getMcpServerToken(serverName: String): String =
        prefs.getString(mcpTokenKey(serverName), "") ?: ""

    fun setMcpServerToken(serverName: String, token: String) =
        prefs.edit().putString(mcpTokenKey(serverName), token.trim()).apply()

    fun clearMcpServerToken(serverName: String) =
        prefs.edit().remove(mcpTokenKey(serverName)).apply()

    /** True when a non-empty token is stored for [serverName]. */
    fun hasMcpServerToken(serverName: String): Boolean =
        getMcpServerToken(serverName).isNotBlank()

    private fun mcpTokenKey(serverName: String): String =
        "$KEY_MCP_SERVER_TOKEN_PREFIX${serverName.trim().lowercase()}"

    // ------------------------------------------------------------------ OAuth (MCP)

    /**
     * OAuth access tokens obtained via the authorization-code + PKCE flow.
     *
     * Kept separate from the plain bearer tokens above because they carry an expiry and a
     * refresh token, and must never be sent to a server that is not the one they were
     * issued for. Stored in the same AndroidKeyStore-encrypted preferences.
     */
    fun getMcpOAuthAccessToken(serverId: String): String =
        prefs.getString(oauthAccessKey(serverId), "") ?: ""

    fun setMcpOAuthAccessToken(serverId: String, token: String) =
        prefs.edit().putString(oauthAccessKey(serverId), token.trim()).apply()

    fun getMcpOAuthRefreshToken(serverId: String): String =
        prefs.getString(oauthRefreshKey(serverId), "") ?: ""

    fun setMcpOAuthRefreshToken(serverId: String, token: String) =
        prefs.edit().putString(oauthRefreshKey(serverId), token.trim()).apply()

    /** Absolute expiry time in epoch millis, or 0 when unknown. */
    fun getMcpOAuthExpiresAt(serverId: String): Long =
        prefs.getLong(oauthExpiryKey(serverId), 0L)

    fun setMcpOAuthExpiresAt(serverId: String, epochMillis: Long) =
        prefs.edit().putLong(oauthExpiryKey(serverId), epochMillis).apply()

    /** True when an access token is stored for [serverId]. */
    fun hasMcpOAuthToken(serverId: String): Boolean =
        getMcpOAuthAccessToken(serverId).isNotBlank()

    /**
     * Stores a complete OAuth credential set atomically.
     *
     * Written in one edit so a crash cannot leave a refresh token without its access token
     * (which would force the user to re-authorize).
     */
    fun setMcpOAuthCredentials(
        serverId: String,
        accessToken: String,
        refreshToken: String,
        expiresAtEpochMillis: Long
    ) = prefs.edit()
        .putString(oauthAccessKey(serverId), accessToken.trim())
        .putString(oauthRefreshKey(serverId), refreshToken.trim())
        .putLong(oauthExpiryKey(serverId), expiresAtEpochMillis)
        .apply()

    /** Removes every stored OAuth value for [serverId]. */
    fun clearMcpOAuthCredentials(serverId: String) = prefs.edit()
        .remove(oauthAccessKey(serverId))
        .remove(oauthRefreshKey(serverId))
        .remove(oauthExpiryKey(serverId))
        .apply()

    /**
     * Pending PKCE verifier for an in-flight authorization.
     *
     * Held only between opening the browser and receiving the redirect, and deleted
     * immediately afterwards (success or failure) so a verifier cannot be replayed.
     */
    fun getMcpOAuthVerifier(serverId: String): String =
        prefs.getString(oauthVerifierKey(serverId), "") ?: ""

    fun setMcpOAuthVerifier(serverId: String, verifier: String) =
        prefs.edit().putString(oauthVerifierKey(serverId), verifier).apply()

    fun clearMcpOAuthVerifier(serverId: String) =
        prefs.edit().remove(oauthVerifierKey(serverId)).apply()

    /** Pending OAuth state parameter, used to detect a mismatched redirect. */
    fun getMcpOAuthState(serverId: String): String =
        prefs.getString(oauthStateKey(serverId), "") ?: ""

    fun setMcpOAuthState(serverId: String, state: String) =
        prefs.edit().putString(oauthStateKey(serverId), state).apply()

    fun clearMcpOAuthState(serverId: String) =
        prefs.edit().remove(oauthStateKey(serverId)).apply()

    private fun oauthAccessKey(serverId: String) =
        "$KEY_MCP_OAUTH_ACCESS_PREFIX${serverId.trim().lowercase()}"

    private fun oauthRefreshKey(serverId: String) =
        "$KEY_MCP_OAUTH_REFRESH_PREFIX${serverId.trim().lowercase()}"

    private fun oauthExpiryKey(serverId: String) =
        "$KEY_MCP_OAUTH_EXPIRY_PREFIX${serverId.trim().lowercase()}"

    private fun oauthVerifierKey(serverId: String) =
        "$KEY_MCP_OAUTH_VERIFIER_PREFIX${serverId.trim().lowercase()}"

    private fun oauthStateKey(serverId: String) =
        "$KEY_MCP_OAUTH_STATE_PREFIX${serverId.trim().lowercase()}"

    fun getSelectedCloudModel(): String {
        val saved = prefs.getString(KEY_SELECTED_CLOUD_MODEL, "") ?: ""
        if (saved.isNotBlank()) return saved
        return com.edgehybrid.agent.BuildConfig.CLOUD_MODEL.takeIf { it.isNotBlank() } ?: DEFAULT_CLOUD_MODEL
    }
    fun setSelectedCloudModel(modelId: String) =
        prefs.edit().putString(KEY_SELECTED_CLOUD_MODEL, modelId.trim()).apply()

    fun getCustomEndpoint(): String {
        val saved = prefs.getString(KEY_CUSTOM_ENDPOINT, "") ?: ""
        if (saved.isNotBlank()) return saved
        return com.edgehybrid.agent.BuildConfig.CLOUD_BASE_URL.takeIf { it.isNotBlank() } ?: DEFAULT_OPENROUTER_ENDPOINT
    }
    fun setCustomEndpoint(url: String) =
        prefs.edit().putString(KEY_CUSTOM_ENDPOINT, url.trim()).apply()

    /**
     * The provider the app should use.
     *
     * Precedence: a value chosen in Settings, then the provider baked in at build time
     * (`EDGE_CLOUD_PROVIDER`), then OpenRouter. Without the build-time fallback a fresh
     * install would always resolve to OpenRouter, so a build configured for Google AI Studio
     * would silently use the wrong endpoint.
     *
     * Unrecognised values fall back rather than propagating: a typo should degrade to the
     * default, not produce an unconfigured provider.
     */
    fun getPreferredProvider(): String {
        val saved = prefs.getString(KEY_PREFERRED_PROVIDER, null)
        if (!saved.isNullOrBlank()) return normalizeProvider(saved)

        val buildTime = com.edgehybrid.agent.BuildConfig.CLOUD_PROVIDER
        if (!buildTime.isNullOrBlank()) return normalizeProvider(buildTime)

        return PROVIDER_OPENROUTER
    }

    /** Maps a provider name onto a supported constant, defaulting to OpenRouter. */
    private fun normalizeProvider(value: String): String = when (value.trim().lowercase()) {
        PROVIDER_GOOGLE_AI_STUDIO -> PROVIDER_GOOGLE_AI_STUDIO
        PROVIDER_OPENROUTER -> PROVIDER_OPENROUTER
        else -> PROVIDER_OPENROUTER
    }

    fun setPreferredProvider(provider: String) =
        prefs.edit().putString(KEY_PREFERRED_PROVIDER, normalizeProvider(provider)).apply()

    /** True when at least one cloud provider has a usable key configured. */
    fun hasAnyCloudKey(): Boolean =
        getOpenRouterApiKey().isNotBlank() || getGeminiApiKey().isNotBlank()

    fun isLocalFallbackEnabled(): Boolean =
        prefs.getBoolean(KEY_ENABLE_LOCAL_FALLBACK, true)
    fun setLocalFallbackEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_ENABLE_LOCAL_FALLBACK, enabled).apply()

    fun isJevRoutingEnabled(): Boolean =
        prefs.getBoolean(KEY_ENABLE_JEV_ROUTING, true)
    fun setJevRoutingEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_ENABLE_JEV_ROUTING, enabled).apply()

    companion object {
        private const val PREFS_FILENAME = "edge_hybrid_secure_prefs"
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        private const val KEY_TYPESAFE_API_KEY = "typesafe_api_key"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
        private const val KEY_TELEGRAM_CHAT_ID = "telegram_chat_id"
        private const val KEY_SELECTED_CLOUD_MODEL = "selected_cloud_model"
        private const val KEY_CUSTOM_ENDPOINT = "custom_endpoint"
        private const val KEY_ENABLE_LOCAL_FALLBACK = "enable_local_fallback"
        private const val KEY_ENABLE_JEV_ROUTING = "enable_jev_routing"
        private const val KEY_PREFERRED_PROVIDER = "preferred_provider"
        private const val KEY_MCP_SERVER_TOKEN_PREFIX = "mcp_server_token_"
        private const val KEY_MCP_OAUTH_ACCESS_PREFIX = "mcp_oauth_access_"
        private const val KEY_MCP_OAUTH_REFRESH_PREFIX = "mcp_oauth_refresh_"
        private const val KEY_MCP_OAUTH_EXPIRY_PREFIX = "mcp_oauth_expiry_"
        private const val KEY_MCP_OAUTH_VERIFIER_PREFIX = "mcp_oauth_verifier_"
        private const val KEY_MCP_OAUTH_STATE_PREFIX = "mcp_oauth_state_"

        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_GOOGLE_AI_STUDIO = "google_ai_studio"

        const val DEFAULT_CLOUD_MODEL = "google/gemini-3.8-flash"
        /** Google AI Studio model ID (no provider prefix) */
        const val DEFAULT_GOOGLE_AI_STUDIO_MODEL = "gemini-2.5-flash"
        const val DEFAULT_OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1"
        /** Google AI Studio OpenAI-compatible endpoint (requires ?key=API_KEY) */
        const val DEFAULT_GOOGLE_AI_STUDIO_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/openai"
    }
}
