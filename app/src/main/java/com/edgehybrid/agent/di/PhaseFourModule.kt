package com.edgehybrid.agent.di

import android.content.Context
import com.edgehybrid.agent.hardware.vision.ImageCompressor
import com.edgehybrid.agent.hardware.vision.MultimodalPromptEnricher
import com.edgehybrid.agent.service.BatteryOptimizationHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Vision and power helpers.
 *
 * `ScreenCaptureHelper` and the `SPenController` / `SPenReceiver` trio were removed here.
 * `ScreenCaptureHelper` returned a grey bitmap with placeholder text rather than a real
 * capture, and the S Pen classes had no consumer and no manifest registration — Samsung
 * exposes no public S Pen button or air-gesture API to third-party apps, so that receiver
 * could never have fired. `BatteryOptimizationHelper` is still provided but is not yet
 * consumed by the agent.
 */
@Module
@InstallIn(SingletonComponent::class)
object PhaseFourModule {

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