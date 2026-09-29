package com.edgehybrid.agent.hardware.vision

import android.graphics.Bitmap
import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enriches prompts with compressed base64 data URLs for multimodal vision models.
 */
@Singleton
class MultimodalPromptEnricher @Inject constructor(
    private val imageCompressor: ImageCompressor
) {

    suspend fun createVisionDataUrl(bitmap: Bitmap): String {
        val jpegBytes = imageCompressor.compressBitmap(bitmap)
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }
}
