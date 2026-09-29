package com.edgehybrid.agent.di

import com.edgehybrid.agent.data.local.LessonsDao
import com.edgehybrid.agent.data.local.SecureKeyStore
import com.edgehybrid.agent.jev.JevClient
import com.edgehybrid.agent.jev.JevDecisionCache
import com.edgehybrid.agent.jev.JevRiskClassifier
import com.edgehybrid.agent.learning.LessonsLedgerManager
import com.edgehybrid.agent.learning.PromptConstraintInjector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PhaseThreeModule {

    @Provides
    @Singleton
    fun provideJevRiskClassifier(): JevRiskClassifier = JevRiskClassifier()

    @Provides
    @Singleton
    fun provideJevDecisionCache(): JevDecisionCache = JevDecisionCache()

    @Provides
    @Singleton
    fun provideJevClient(
        keyStore: SecureKeyStore,
        decisionCache: JevDecisionCache,
        riskClassifier: JevRiskClassifier
    ): JevClient = JevClient(keyStore, decisionCache, riskClassifier)

    @Provides
    @Singleton
    fun provideLessonsLedgerManager(
        lessonsDao: LessonsDao
    ): LessonsLedgerManager = LessonsLedgerManager(lessonsDao)

    @Provides
    @Singleton
    fun providePromptConstraintInjector(
        lessonsLedgerManager: LessonsLedgerManager
    ): PromptConstraintInjector = PromptConstraintInjector(lessonsLedgerManager)
}
