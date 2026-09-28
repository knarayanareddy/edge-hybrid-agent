package com.edgehybrid.agent.di

import com.edgehybrid.agent.BuildConfig
import com.edgehybrid.agent.agent.AgentLoop
import com.edgehybrid.agent.agent.AgentOrchestrator
import com.edgehybrid.agent.agent.AgentPolicy
import com.edgehybrid.agent.agent.CloudInferenceEngine
import com.edgehybrid.agent.agent.InferenceEngine
import com.edgehybrid.agent.agent.KotlinSuspendDelay
import com.edgehybrid.agent.agent.MonotonicClock
import com.edgehybrid.agent.agent.ProviderSettings
import com.edgehybrid.agent.agent.SuspendDelay
import com.edgehybrid.agent.tool.BuiltInSkillLoader
import com.edgehybrid.agent.tool.McpClient
import com.edgehybrid.agent.tool.McpSettings
import com.edgehybrid.agent.tool.SkillLoader
import com.edgehybrid.agent.tool.StreamableHttpMcpClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import jakarta.inject.Singleton
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentBindingsModule {
    @Binds
    @Singleton
    abstract fun bindInferenceEngine(
        implementation: CloudInferenceEngine
    ): InferenceEngine

    @Binds
    @Singleton
    abstract fun bindAgentLoop(
        implementation: AgentOrchestrator
    ): AgentLoop

    @Binds
    @Singleton
    abstract fun bindSkillLoader(
        implementation: BuiltInSkillLoader
    ): SkillLoader

    @Binds
    @Singleton
    abstract fun bindMcpClient(
        implementation: StreamableHttpMcpClient
    ): McpClient
}

@Module
@InstallIn(SingletonComponent::class)
object AgentInfrastructureModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = false
    }

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient =
        HttpClient(CIO) {
            expectSuccess = false
            followRedirects = true

            install(ContentNegotiation) {
                json(json)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 300_000L
                connectTimeoutMillis = 15_000L
                socketTimeoutMillis = 120_000L
            }
        }

    @Provides
    @Singleton
    fun provideProviderSettings(): ProviderSettings =
        ProviderSettings(
            baseUrl = BuildConfig.CLOUD_BASE_URL,
            apiKey = BuildConfig.CLOUD_API_KEY.takeIf(String::isNotBlank),
            model = BuildConfig.CLOUD_MODEL
        )

    @Provides
    @Singleton
    fun provideMcpSettings(): McpSettings =
        McpSettings(
            enabled = BuildConfig.MCP_ENABLED,
            endpoint = BuildConfig.MCP_ENDPOINT,
            bearerToken = BuildConfig.MCP_BEARER_TOKEN.takeIf(String::isNotBlank)
        )

    @Provides
    @Singleton
    fun provideAgentPolicy(): AgentPolicy = AgentPolicy()

    @Provides
    @Singleton
    fun provideClock(): MonotonicClock =
        MonotonicClock(System::nanoTime)

    @Provides
    @Singleton
    fun provideDelay(): SuspendDelay = KotlinSuspendDelay
}