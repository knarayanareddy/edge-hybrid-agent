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

    fun getOpenRouterApiKey(): String = prefs.getString(KEY_OPENROUTER_API_KEY, "") ?: ""
    fun setOpenRouterApiKey(key: String) = prefs.edit().putString(KEY_OPENROUTER_API_KEY, key.trim()).apply()

    fun getTypeSafeApiKey(): String = prefs.getString(KEY_TYPESAFE_API_KEY, "") ?: ""
    fun setTypeSafeApiKey(key: String) = prefs.edit().putString(KEY_TYPESAFE_API_KEY, key.trim()).apply()

    fun getGeminiApiKey(): String = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
    fun setGeminiApiKey(key: String) = prefs.edit().putString(KEY_GEMINI_API_KEY, key.trim()).apply()

    fun getSelectedCloudModel(): String =
        prefs.getString(KEY_SELECTED_CLOUD_MODEL, DEFAULT_CLOUD_MODEL) ?: DEFAULT_CLOUD_MODEL
    fun setSelectedCloudModel(modelId: String) =
        prefs.edit().putString(KEY_SELECTED_CLOUD_MODEL, modelId.trim()).apply()

    fun getCustomEndpoint(): String =
        prefs.getString(KEY_CUSTOM_ENDPOINT, DEFAULT_OPENROUTER_ENDPOINT) ?: DEFAULT_OPENROUTER_ENDPOINT
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
        private const val KEY_SELECTED_CLOUD_MODEL = "selected_cloud_model"
        private const val KEY_CUSTOM_ENDPOINT = "custom_endpoint"
        private const val KEY_ENABLE_LOCAL_FALLBACK = "enable_local_fallback"
        private const val KEY_ENABLE_JEV_ROUTING = "enable_jev_routing"

        const val DEFAULT_CLOUD_MODEL = "google/gemini-2.5-flash"
        const val DEFAULT_OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    }
}
