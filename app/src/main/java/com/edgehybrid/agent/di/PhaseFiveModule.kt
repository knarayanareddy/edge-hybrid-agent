package com.edgehybrid.agent.di

import com.edgehybrid.agent.rag.OnDeviceVectorStore
import com.edgehybrid.agent.rag.RagContextAugmenter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * RAG wiring.
 *
 * The inference bindings that used to live here (`ModelWeightsManager`,
 * `LocalLiteRtEngine`, `HybridInferenceRouter`) pointed at a duplicate
 * `com.edgehybrid.agent.core.inference` tree that has been removed. The live inference
 * path is [com.edgehybrid.agent.agent.CloudInferenceEngine], bound in `AgentModule`.
 *
 * [OnDeviceVectorStore] is a real, tested in-memory vector store; [RagContextAugmenter]
 * uses it to prepend relevant prior chunks to a query. Neither is currently invoked from
 * the agent loop, so RAG is wired but dormant.
 */
@Module
@InstallIn(SingletonComponent::class)
object PhaseFiveModule {

    @Provides
    @Singleton
    fun provideOnDeviceVectorStore(): OnDeviceVectorStore = OnDeviceVectorStore()

    @Provides
    @Singleton
    fun provideRagContextAugmenter(
        vectorStore: OnDeviceVectorStore
    ): RagContextAugmenter = RagContextAugmenter(vectorStore)
}