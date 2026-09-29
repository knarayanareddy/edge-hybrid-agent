package com.edgehybrid.agent.rag

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Augments user queries with top matching knowledge chunks from OnDeviceVectorStore.
 */
@Singleton
class RagContextAugmenter @Inject constructor(
    private val vectorStore: OnDeviceVectorStore
) {

    suspend fun augmentPromptWithContext(prompt: String, dimension: Int = 64): String {
        if (vectorStore.size() == 0) {
            return prompt
        }

        // Generate deterministic query embedding
        val queryVector = createTermVector(prompt, dimension)
        val matches = vectorStore.findTopK(queryVector, k = 3)

        if (matches.isEmpty() || matches.first().second <= 0.1f) {
            return prompt
        }

        val contextBlock = buildString {
            append("\n\n### RELEVANT ON-DEVICE CONTEXT:\n")
            matches.forEachIndexed { idx, pair ->
                append("${idx + 1}. [Relevance: ${(pair.second * 100).toInt()}%] ${pair.first.text}\n")
            }
            append("\n")
        }

        return contextBlock + prompt
    }

    fun createTermVector(text: String, dimension: Int = 64): FloatArray {
        val vector = FloatArray(dimension)
        val words = text.lowercase().split("\\W+".toRegex()).filter { it.isNotBlank() }

        for (word in words) {
            val hash = (word.hashCode() and 0x7FFFFFFF) % dimension
            vector[hash] += 1.0f
        }

        // Normalize
        var sumSquares = 0.0f
        for (v in vector) sumSquares += v * v
        val norm = kotlin.math.sqrt(sumSquares)
        if (norm > 0f) {
            for (i in vector.indices) vector[i] /= norm
        }

        return vector
    }
}
