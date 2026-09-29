package com.edgehybrid.agent.di

import com.edgehybrid.agent.sandbox.HeadlessWebViewSandbox
import com.edgehybrid.agent.sandbox.ScriptSandbox
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PhaseTwoModule {

    @Binds
    @Singleton
    abstract fun bindScriptSandbox(
        headlessWebViewSandbox: HeadlessWebViewSandbox
    ): ScriptSandbox
}
