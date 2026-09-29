package com.edgehybrid.agent.rag

import kotlin.math.sqrt

/**
 * High-performance vector embedding supporting cosine similarity calculation.
 */
data class VectorEmbedding(
    val id: String,
    val text: String,
    val vector: FloatArray
) {
    fun cosineSimilarity(other: FloatArray): Float {
        if (vector.size != other.size || vector.isEmpty()) return 0f

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in vector.indices) {
            val a = vector[i]
            val b = other[i]
            dotProduct += a * b
            normA += a * a
            normB += b * b
        }

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0f) dotProduct / denominator else 0f
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VectorEmbedding
        if (id != other.id) return false
        if (text != other.text) return false
        if (!vector.contentEquals(other.vector)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + vector.contentHashCode()
        return result
    }
}
