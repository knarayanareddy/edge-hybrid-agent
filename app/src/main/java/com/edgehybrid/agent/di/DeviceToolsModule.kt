package com.edgehybrid.agent.di

import android.content.Context
import com.edgehybrid.agent.mcp.McpServerRegistry
import com.edgehybrid.agent.mcp.McpServerSource
import com.edgehybrid.agent.mcp.McpTokenSource
import com.edgehybrid.agent.systemactions.SamsungActions
import com.edgehybrid.agent.systemactions.SystemActionHandler
import com.edgehybrid.agent.systemactions.SystemToolProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Device and settings tools, plus the MCP server catalogue.
 *
 * The handlers are `@Singleton` and annotated `@Inject`, so they are added here explicitly
 * only where a plain constructor argument is needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object DeviceToolsModule {

    @Provides
    @Singleton
    fun provideSystemActionHandler(
        @ApplicationContext context: Context
    ): SystemActionHandler = SystemActionHandler(context)

    @Provides
    @Singleton
    fun provideSamsungActions(
        @ApplicationContext context: Context
    ): SamsungActions = SamsungActions(context)

    @Provides
    @Singleton
    fun provideSystemToolProvider(
        systemActions: SystemActionHandler,
        samsungActions: SamsungActions
    ): SystemToolProvider = SystemToolProvider(systemActions, samsungActions)

    @Provides
    @Singleton
    fun provideMcpServerRegistry(
        @ApplicationContext context: Context,
        keyStore: com.edgehybrid.agent.data.local.SecureKeyStore
    ): McpServerRegistry = McpServerRegistry(context, keyStore)

    /** Binds the catalogue seam to the real registry. */
    @Provides
    @Singleton
    fun provideMcpServerSource(registry: McpServerRegistry): McpServerSource = registry

    /** Binds the token seam to the real OAuth store. */
    @Provides
    @Singleton
    fun provideMcpTokenSource(manager: com.edgehybrid.agent.mcp.McpOAuthManager): McpTokenSource =
        manager

    @Provides
    @Singleton
    fun provideMcpProbeRunner(): com.edgehybrid.agent.mcp.McpProbeRunner =
        com.edgehybrid.agent.mcp.McpProbeRunner()

    @Provides
    @Singleton
    fun provideMcpOAuthManager(
        @ApplicationContext context: Context,
        keyStore: com.edgehybrid.agent.data.local.SecureKeyStore,
        registry: McpServerRegistry
    ): com.edgehybrid.agent.mcp.McpOAuthManager =
        com.edgehybrid.agent.mcp.McpOAuthManager(context, keyStore, registry)
}