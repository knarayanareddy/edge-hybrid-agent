package com.edgehybrid.agent.rag

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OnDeviceVectorStoreTest {

    private lateinit var store: OnDeviceVectorStore
    private lateinit var augmenter: RagContextAugmenter

    @Before
    fun setUp() {
        store = OnDeviceVectorStore()
        augmenter = RagContextAugmenter(store)
    }

    @Test
    fun testVectorCosineSimilarity() {
        val v1 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val v2 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val v3 = floatArrayOf(0.0f, 1.0f, 0.0f)

        val emb = VectorEmbedding("1", "doc1", v1)

        assertEquals(1.0f, emb.cosineSimilarity(v2), 0.001f)
        assertEquals(0.0f, emb.cosineSimilarity(v3), 0.001f)
    }

    @Test
    fun testAddDocumentAndRetrieveTopK() = runTest {
        val v1 = floatArrayOf(0.9f, 0.1f, 0.0f)
        val v2 = floatArrayOf(0.1f, 0.9f, 0.0f)
        val v3 = floatArrayOf(0.8f, 0.2f, 0.0f)

        store.addDocument("doc1", "Android development with Kotlin", v1)
        store.addDocument("doc2", "Cooking pasta recipe", v2)
        store.addDocument("doc3", "Jetpack Compose architecture", v3)

        assertEquals(3, store.size())

        val query = floatArrayOf(1.0f, 0.0f, 0.0f)
        val results = store.findTopK(query, k = 2)

        assertEquals(2, results.size)
        // Top 1 should be doc1 (similarity ~0.9)
        assertEquals("doc1", results[0].first.id)
        assertTrue(results[0].second > results[1].second)
    }

    @Test
    fun testRagContextAugmenter() = runTest {
        val vector = augmenter.createTermVector("Kotlin coroutines and Room database", 64)
        store.addDocument("chunk1", "Room database handles local SQLite storage in Android.", vector)

        val prompt = "How do I query notes using Room?"
        val augmented = augmenter.augmentPromptWithContext(prompt, 64)

        assertTrue(augmented.contains("RELEVANT ON-DEVICE CONTEXT"))
        assertTrue(augmented.contains("Room database handles local SQLite"))
    }
}
