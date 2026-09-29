package com.edgehybrid.agent.core.inference

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intelligent hybrid router delegating to LocalLiteRtEngine or CloudInferenceEngine
 * based on live network connectivity and model availability.
 */
@Singleton
class HybridInferenceRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localEngine: LocalLiteRtEngine,
    private val cloudEngine: CloudInferenceEngine
) : InferenceEngine {

    override val engineName: String
        get() = if (isNetworkAvailable()) "HybridRouter(Cloud)" else "HybridRouter(Local)"

    override val requiresNetwork: Boolean = false

    override suspend fun isAvailable(): Boolean {
        return isNetworkAvailable() || localEngine.isAvailable()
    }

    fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun selectActiveEngine(): InferenceEngine {
        return if (isNetworkAvailable()) {
            cloudEngine
        } else {
            localEngine
        }
    }

    override fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        config: GenerationConfig
    ): Flow<StreamChunk> {
        val target = if (isNetworkAvailable()) cloudEngine else localEngine
        return target.generate(messages, tools, config)
    }

    override suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        config: GenerationConfig
    ): CompletionResult {
        val target = if (isNetworkAvailable()) cloudEngine else localEngine
        return target.complete(messages, tools, config)
    }
}
