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

    fun getOpenRouterApiKey(): String {
        val saved = prefs.getString(KEY_OPENROUTER_API_KEY, "") ?: ""
        if (saved.isNotBlank()) return saved
        return com.edgehybrid.agent.BuildConfig.CLOUD_API_KEY.takeIf { it.isNotBlank() } ?: ""
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

        const val DEFAULT_CLOUD_MODEL = "google/gemini-3.8-flash"
        const val DEFAULT_OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    }
}
