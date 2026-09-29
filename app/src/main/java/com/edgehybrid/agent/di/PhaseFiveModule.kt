package com.edgehybrid.agent.di

import android.content.Context
import com.edgehybrid.agent.core.inference.CloudInferenceEngine
import com.edgehybrid.agent.core.inference.HybridInferenceRouter
import com.edgehybrid.agent.core.inference.LocalLiteRtEngine
import com.edgehybrid.agent.core.inference.ModelWeightsManager
import com.edgehybrid.agent.rag.OnDeviceVectorStore
import com.edgehybrid.agent.rag.RagContextAugmenter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PhaseFiveModule {

    @Provides
    @Singleton
    fun provideModelWeightsManager(
        @ApplicationContext context: Context
    ): ModelWeightsManager = ModelWeightsManager(context)

    @Provides
    @Singleton
    fun provideLocalLiteRtEngine(
        modelWeightsManager: ModelWeightsManager
    ): LocalLiteRtEngine = LocalLiteRtEngine(modelWeightsManager)

    @Provides
    @Singleton
    fun provideHybridInferenceRouter(
        @ApplicationContext context: Context,
        localEngine: LocalLiteRtEngine,
        cloudEngine: CloudInferenceEngine
    ): HybridInferenceRouter = HybridInferenceRouter(context, localEngine, cloudEngine)

    @Provides
    @Singleton
    fun provideOnDeviceVectorStore(): OnDeviceVectorStore = OnDeviceVectorStore()

    @Provides
    @Singleton
    fun provideRagContextAugmenter(
        vectorStore: OnDeviceVectorStore
    ): RagContextAugmenter = RagContextAugmenter(vectorStore)
}
