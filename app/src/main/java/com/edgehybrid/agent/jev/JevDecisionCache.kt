package com.edgehybrid.agent.jev

import java.security.MessageDigest
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe LRU cache storing JEV evaluation verdicts keyed by prompt SHA-256 hash.
 */
@Singleton
class JevDecisionCache @Inject constructor() {

    private val maxSize = 100
    private val lock = Any()

    private val cache = object : LinkedHashMap<String, JevEvaluationResponse>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JevEvaluationResponse>?): Boolean {
            return size > maxSize
        }
    }

    fun get(prompt: String): JevEvaluationResponse? {
        val key = computeHash(prompt)
        synchronized(lock) {
            return cache[key]
        }
    }

    fun put(prompt: String, response: JevEvaluationResponse) {
        val key = computeHash(prompt)
        synchronized(lock) {
            cache[key] = response
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
        }
    }

    private fun computeHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
