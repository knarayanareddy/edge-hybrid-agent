package com.edgehybrid.agent.di

import com.edgehybrid.agent.data.local.LessonsDao
import com.edgehybrid.agent.data.local.SecureKeyStore
import com.edgehybrid.agent.jev.JevClient
import com.edgehybrid.agent.jev.JevDecisionCache
import com.edgehybrid.agent.jev.JevEvaluator
import com.edgehybrid.agent.jev.JevRiskClassifier
import com.edgehybrid.agent.jev.JevSafetyGate
import com.edgehybrid.agent.learning.LessonsLedgerManager
import com.edgehybrid.agent.learning.PromptConstraintInjector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Just-Enough Validation (JEV) and learning wiring.
 *
 * [JevClient] and [JevSafetyGate] are on the live path: the client backs tool-payload
 * pruning in `ToolGateway`, and the safety gate supplies a calibrated escalation signal to
 * `ConfirmationGate`. Both require a Jev API key in Settings; without one they report
 * unavailable and the app falls back to local policy.
 *
 * [JevRiskClassifier] and [JevDecisionCache] stay local and offline: the classifier needs
 * no API key and gives a deterministic answer, which is preferable to a network call.
 */
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
    fun provideJevClient(keyStore: SecureKeyStore): JevClient = JevClient(keyStore)

    @Provides
    @Singleton
    fun provideJevEvaluator(client: JevClient): JevEvaluator = client

    @Provides
    @Singleton
    fun provideJevSafetyGate(jevClient: JevClient): JevSafetyGate = JevSafetyGate(jevClient)

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
