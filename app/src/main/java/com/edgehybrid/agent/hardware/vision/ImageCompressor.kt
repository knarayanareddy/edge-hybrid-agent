package com.edgehybrid.agent.hardware.vision

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Optimizes bitmaps for multimodal LLM transmission, bounding max dimension to 1024px and size < 250KB.
 */
@Singleton
class ImageCompressor @Inject constructor() {

    suspend fun compressBitmap(bitmap: Bitmap, maxDimension: Int = 1024, initialQuality: Int = 85): ByteArray =
        withContext(Dispatchers.Default) {
            val width = bitmap.width
            val height = bitmap.height
            val largestDim = max(width, height)

            val scaledBitmap = if (largestDim > maxDimension) {
                val scale = maxDimension.toFloat() / largestDim.toFloat()
                val targetW = (width * scale).toInt()
                val targetH = (height * scale).toInt()
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            } else {
                bitmap
            }

            var quality = initialQuality
            var outputBytes: ByteArray

            do {
                val stream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                outputBytes = stream.toByteArray()
                quality -= 10
            } while (outputBytes.size > 250 * 1024 && quality >= 40)

            outputBytes
        }
}
