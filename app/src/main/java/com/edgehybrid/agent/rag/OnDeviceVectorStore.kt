package com.edgehybrid.agent.rag

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe on-device vector store with in-memory caching and cosine similarity ranking.
 */
@Singleton
class OnDeviceVectorStore @Inject constructor() {

    private val embeddingsMap = ConcurrentHashMap<String, VectorEmbedding>()
    private val mutex = Mutex()

    suspend fun addDocument(id: String, text: String, vector: FloatArray) = withContext(Dispatchers.Default) {
        mutex.withLock {
            embeddingsMap[id] = VectorEmbedding(id, text, vector)
        }
    }

    suspend fun findTopK(queryVector: FloatArray, k: Int = 3): List<Pair<VectorEmbedding, Float>> =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                embeddingsMap.values
                    .map { embedding ->
                        val similarity = embedding.cosineSimilarity(queryVector)
                        Pair(embedding, similarity)
                    }
                    .sortedByDescending { it.second }
                    .take(k)
            }
        }

    fun size(): Int = embeddingsMap.size

    fun clear() {
        embeddingsMap.clear()
    }
}
