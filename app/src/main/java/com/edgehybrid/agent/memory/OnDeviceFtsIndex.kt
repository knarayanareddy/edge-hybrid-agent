package com.edgehybrid.agent.memory

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

/**
 * On-Device Full-Text Search (FTS) and BM25 Ranking Engine for Samsung S23 Ultra.
 *
 * ## Why this exists alongside Room:
 * 1. **Sub-5ms In-Memory Latency**: Provides instant BM25 token-weighted search across
 *    thousands of past chat messages and user facts without SQLite query latency.
 * 2. **Pointer Truncation**: Stores only truncated text previews in memory and UI lists,
 *    storing large transcripts and JSON outputs on disk at [fullPayloadUri]. This preserves
 *    120 FPS scrolling on the Dynamic AMOLED 2X display and prevents mobile OOM errors.
 */
class OnDeviceFtsIndex(
    private val k1: Double = 1.2,
    private val b: Double = 0.75
) {
    data class IndexedDoc(
        val docId: String,
        val preview: String,
        val fullPayloadUri: String? = null,
        val tokenCount: Int,
        val termFrequencies: Map<String, Int>,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class FtsSearchResult(
        val docId: String,
        val preview: String,
        val fullPayloadUri: String? = null,
        val score: Double,
        val timestamp: Long
    )

    private val docs = ConcurrentHashMap<String, IndexedDoc>()
    private val invertedIndex = ConcurrentHashMap<String, MutableSet<String>>()

    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "be", "been",
        "to", "of", "in", "on", "for", "with", "at", "by", "from", "as", "it", "its"
    )

    fun tokenize(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9_]+"))
            .filter { it.length >= 2 && it !in stopWords }

    /**
     * Indexes a document with pointer truncation.
     *
     * @param previewMaxLength Maximum character length for in-memory preview (default 200).
     */
    fun index(
        docId: String,
        fullContent: String,
        fullPayloadUri: String? = null,
        previewMaxLength: Int = 200
    ) {
        val tokens = tokenize(fullContent)
        if (tokens.isEmpty()) return

        val termFreqs = mutableMapOf<String, Int>()
        tokens.forEach { token ->
            termFreqs[token] = (termFreqs[token] ?: 0) + 1
        }

        val preview = if (fullContent.length > previewMaxLength) {
            fullContent.substring(0, previewMaxLength) + "…"
        } else {
            fullContent
        }

        val doc = IndexedDoc(
            docId = docId,
            preview = preview,
            fullPayloadUri = fullPayloadUri,
            tokenCount = tokens.size,
            termFrequencies = termFreqs
        )

        docs[docId] = doc

        // Update inverted index
        termFreqs.keys.forEach { term ->
            invertedIndex.computeIfAbsent(term) { ConcurrentHashMap.newKeySet() }.add(docId)
        }
    }

    /**
     * Executes BM25 full-text query ranking against indexed documents.
     */
    fun search(query: String, topK: Int = 10): List<FtsSearchResult> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty() || docs.isEmpty()) return emptyList()

        val totalDocs = docs.size.toDouble()
        val avgDocLength = docs.values.map { it.tokenCount }.average().takeIf { it > 0 } ?: 1.0

        // Find candidate documents containing at least one query token
        val candidateDocIds = mutableSetOf<String>()
        queryTokens.forEach { token ->
            invertedIndex[token]?.let { candidateDocIds.addAll(it) }
        }

        if (candidateDocIds.isEmpty()) return emptyList()

        val results = candidateDocIds.mapNotNull { docId ->
            val doc = docs[docId] ?: return@mapNotNull null
            var score = 0.0

            queryTokens.forEach { token ->
                val tf = doc.termFrequencies[token] ?: 0
                if (tf > 0) {
                    val docFreq = (invertedIndex[token]?.size ?: 0).toDouble()
                    // Standard BM25 Robertson-Spärck Jones IDF
                    val idf = ln((totalDocs - docFreq + 0.5) / (docFreq + 0.5) + 1.0)
                    val numerator = tf * (k1 + 1.0)
                    val denominator = tf + k1 * (1.0 - b + b * (doc.tokenCount / avgDocLength))
                    score += idf * (numerator / denominator)
                }
            }

            if (score > 0.0) {
                FtsSearchResult(
                    docId = doc.docId,
                    preview = doc.preview,
                    fullPayloadUri = doc.fullPayloadUri,
                    score = score,
                    timestamp = doc.timestamp
                )
            } else null
        }

        return results.sortedByDescending { it.score }.take(topK)
    }

    fun size(): Int = docs.size

    fun clear() {
        docs.clear()
        invertedIndex.clear()
    }
}
