package com.edgehybrid.agent.core.inference

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages local storage, paths, and status of on-device Gemma 2B Q4 / LiteRT model weights.
 */
@Singleton
class ModelWeightsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val modelsDirectory: File
        get() = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    val defaultModelFile: File
        get() = File(modelsDirectory, DEFAULT_MODEL_FILENAME)

    fun isModelAvailable(): Boolean {
        return defaultModelFile.exists() && defaultModelFile.length() > 0
    }

    fun getModelSizeBytes(): Long {
        return if (defaultModelFile.exists()) defaultModelFile.length() else 0L
    }

    suspend fun sideloadModelFile(sourceFile: File): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            sourceFile.copyTo(defaultModelFile, overwrite = true)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteModelFile(): Boolean = withContext(Dispatchers.IO) {
        if (defaultModelFile.exists()) {
            defaultModelFile.delete()
        } else {
            true
        }
    }

    companion object {
        const val DEFAULT_MODEL_FILENAME = "gemma-2b-it-gpu-int4.bin"
    }
}
