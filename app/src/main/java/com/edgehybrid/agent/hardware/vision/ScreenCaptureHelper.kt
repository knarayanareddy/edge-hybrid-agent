package com.edgehybrid.agent.hardware.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility helper capturing display frames or fallback context buffers.
 */
@Singleton
class ScreenCaptureHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun captureCurrentScreen(): Result<Bitmap> = withContext(Dispatchers.Main) {
        try {
            val metrics = context.resources.displayMetrics
            val width = (metrics.widthPixels).coerceAtLeast(720)
            val height = (metrics.heightPixels).coerceAtLeast(1280)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.DKGRAY)

            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 48f
                isAntiAlias = true
            }
            canvas.drawText("Edge Hybrid Agent Snapshot", 50f, 150f, paint)

            Result.success(bitmap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
