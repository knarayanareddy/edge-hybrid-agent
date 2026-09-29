package com.edgehybrid.agent.di

import android.content.Context
import com.edgehybrid.agent.hardware.spen.SPenController
import com.edgehybrid.agent.hardware.vision.ImageCompressor
import com.edgehybrid.agent.hardware.vision.MultimodalPromptEnricher
import com.edgehybrid.agent.hardware.vision.ScreenCaptureHelper
import com.edgehybrid.agent.service.BatteryOptimizationHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PhaseFourModule {

    @Provides
    @Singleton
    fun provideSPenController(): SPenController = SPenController()

    @Provides
    @Singleton
    fun provideScreenCaptureHelper(
        @ApplicationContext context: Context
    ): ScreenCaptureHelper = ScreenCaptureHelper(context)

    @Provides
    @Singleton
    fun provideImageCompressor(): ImageCompressor = ImageCompressor()

    @Provides
    @Singleton
    fun provideMultimodalPromptEnricher(
        imageCompressor: ImageCompressor
    ): MultimodalPromptEnricher = MultimodalPromptEnricher(imageCompressor)

    @Provides
    @Singleton
    fun provideBatteryOptimizationHelper(
        @ApplicationContext context: Context
    ): BatteryOptimizationHelper = BatteryOptimizationHelper(context)
}
