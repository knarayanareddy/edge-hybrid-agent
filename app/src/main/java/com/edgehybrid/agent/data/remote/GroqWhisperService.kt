package com.edgehybrid.agent.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for transcribing long audio recordings (meeting notes, voice memos, lectures)
 * using Groq's high-speed Whisper Large v3 (whisper-large-v3) LPU inference.
 */
@Singleton
class GroqWhisperService @Inject constructor() {

    suspend fun transcribeAudio(
        audioBytes: ByteArray,
        fileName: String,
        apiKey: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Groq API Key is not configured. Please add your Groq key in Settings.")
                )
            }

            if (audioBytes.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("The selected audio recording is empty.")
                )
            }

            val boundary = "GroqBoundary" + System.currentTimeMillis()
            val lineEnd = "\r\n"
            val twoHyphens = "--"

            val url = URL("https://api.groq.com/openai/v1/audio/transcriptions")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                useCaches = false
                connectTimeout = 45_000
                readTimeout = 120_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(connection.outputStream).use { output ->
                // Model field: whisper-large-v3
                output.writeBytes("$twoHyphens$boundary$lineEnd")
                output.writeBytes("Content-Disposition: form-data; name=\"model\"$lineEnd$lineEnd")
                output.writeBytes("whisper-large-v3$lineEnd")

                // Response format field
                output.writeBytes("$twoHyphens$boundary$lineEnd")
                output.writeBytes("Content-Disposition: form-data; name=\"response_format\"$lineEnd$lineEnd")
                output.writeBytes("json$lineEnd")

                // Audio file part
                val safeFileName = if (fileName.contains('.')) fileName else "$fileName.m4a"
                output.writeBytes("$twoHyphens$boundary$lineEnd")
                output.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$safeFileName\"$lineEnd")
                output.writeBytes("Content-Type: application/octet-stream$lineEnd$lineEnd")
                output.write(audioBytes)
                output.writeBytes(lineEnd)

                // End of multipart boundary
                output.writeBytes("$twoHyphens$boundary$twoHyphens$lineEnd")
                output.flush()
            }

            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val responseBody = responseStream.bufferedReader().use { it.readText() }

            if (responseCode !in 200..299) {
                return@withContext Result.failure(
                    Exception("Groq Whisper API error (HTTP $responseCode): $responseBody")
                )
            }

            val json = JSONObject(responseBody)
            val text = json.optString("text", "")
            if (text.isBlank()) {
                Result.failure(Exception("Whisper returned an empty transcription."))
            } else {
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
