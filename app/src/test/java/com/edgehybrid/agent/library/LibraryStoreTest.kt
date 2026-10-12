package com.edgehybrid.agent.library

import com.edgehybrid.agent.rag.OnDeviceVectorStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibraryStoreTest {

    private lateinit var vectorStore: OnDeviceVectorStore
    private lateinit var libraryStore: LibraryStore

    @Before
    fun setUp() {
        vectorStore = OnDeviceVectorStore()
        libraryStore = LibraryStore(null, vectorStore)
    }

    @Test
    fun testAddAndFilterByTabs() = runTest {
        val doc = LibraryItem(
            id = "doc_1",
            name = "system_architecture.md",
            type = LibraryItemType.DOCUMENT,
            sizeBytes = 2048,
            summary = "Agent harness architectural breakdown",
            isFavorite = true,
            textContent = "This document describes the 22 architectural modules of the mobile superapp."
        )
        val code = LibraryItem(
            id = "code_1",
            name = "ScheduledTask.kt",
            type = LibraryItemType.CODE,
            sizeBytes = 1024,
            summary = "Data models for autonomous tasks",
            isFavorite = false,
            textContent = "package com.edgehybrid.agent.scheduled..."
        )
        val image = LibraryItem(
            id = "img_1",
            name = "screenshot_library.jpg",
            type = LibraryItemType.IMAGE,
            sizeBytes = 4096,
            summary = "ChatGPT Library UI screenshot",
            isFavorite = false
        )

        libraryStore.addItem(doc)
        libraryStore.addItem(code)
        libraryStore.addItem(image)

        assertEquals(3, libraryStore.items.value.size)

        // Filter by tab
        val favorites = libraryStore.getFilteredItems("favorites", "")
        assertEquals(1, favorites.size)
        assertEquals("system_architecture.md", favorites.first().name)

        val codeItems = libraryStore.getFilteredItems("code", "")
        assertEquals(1, codeItems.size)
        assertEquals("ScheduledTask.kt", codeItems.first().name)

        val documents = libraryStore.getFilteredItems("documents", "")
        assertEquals(1, documents.size)
        assertEquals("system_architecture.md", documents.first().name)

        val images = libraryStore.getFilteredItems("images", "")
        assertEquals(1, images.size)
        assertEquals("screenshot_library.jpg", images.first().name)
    }

    @Test
    fun testSearchFiltering() = runTest {
        libraryStore.addItem(
            LibraryItem(
                id = "doc_alpha",
                name = "quantum_computing.pdf",
                type = LibraryItemType.DOCUMENT,
                summary = "Superconducting qubit benchmarks"
            )
        )
        libraryStore.addItem(
            LibraryItem(
                id = "doc_beta",
                name = "neural_search.md",
                type = LibraryItemType.DOCUMENT,
                summary = "Local vector indexing and retrieval"
            )
        )

        val searchResult = libraryStore.getFilteredItems("documents", "quantum")
        assertEquals(1, searchResult.size)
        assertEquals("quantum_computing.pdf", searchResult.first().name)

        val searchBySummary = libraryStore.getFilteredItems("documents", "retrieval")
        assertEquals(1, searchBySummary.size)
        assertEquals("neural_search.md", searchBySummary.first().name)
    }

    @Test
    fun testRagVectorIndexing() = runTest {
        val content = "The Samsung S23 Ultra runs Snapdragon 8 Gen 2 with integrated Hexagon NPU."
        val doc = LibraryItem(
            id = "rag_doc_1",
            name = "hardware_spec.txt",
            type = LibraryItemType.DOCUMENT,
            summary = "Mobile hardware specs",
            textContent = content
        )

        libraryStore.addItem(doc)

        // Verify chunk was indexed in OnDeviceVectorStore
        val augmenter = com.edgehybrid.agent.rag.RagContextAugmenter(vectorStore)
        val queryVector = augmenter.createTermVector("Snapdragon", 64)
        val matches = vectorStore.findTopK(queryVector, k = 1)
        assertFalse("Vector store must have matching chunk", matches.isEmpty())
        assertTrue("Matching chunk must contain Snapdragon", matches.first().first.text.contains("Snapdragon"))
    }

    @Test
    fun testToggleFavoriteAndDelete() = runTest {
        val item = LibraryItem(
            id = "test_item_fav",
            name = "notes.txt",
            type = LibraryItemType.DOCUMENT,
            isFavorite = false
        )
        libraryStore.addItem(item)

        libraryStore.toggleFavorite("test_item_fav")
        assertTrue(libraryStore.items.value.first().isFavorite)

        libraryStore.deleteItem("test_item_fav")
        assertTrue(libraryStore.items.value.isEmpty())
    }
}
