package com.edgehybrid.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceFtsIndexTest {

    @Test
    fun `indexing and searching retrieves document by keyword with BM25 rank`() {
        val fts = OnDeviceFtsIndex()

        fts.index(
            docId = "doc1",
            fullContent = "The Samsung Galaxy S23 Ultra features Snapdragon 8 Gen 2 processor with 200MP camera sensor."
        )
        fts.index(
            docId = "doc2",
            fullContent = "Meeting at 3 PM tomorrow with Sarah regarding the new Android app architecture and Jetpack Compose."
        )
        fts.index(
            docId = "doc3",
            fullContent = "Recipe for homemade sourdough bread with organic wheat flour and active starter."
        )

        val results = fts.search("snapdragon camera")
        assertTrue(results.isNotEmpty())
        assertEquals("doc1", results[0].docId)
        assertTrue(results[0].preview.contains("Snapdragon"))
    }

    @Test
    fun `pointer truncation preserves preview and stores payload URI pointer`() {
        val fts = OnDeviceFtsIndex()
        val largeContent = "A".repeat(500) + " crucial data payload " + "B".repeat(500)
        val payloadUri = "file:///data/user/0/com.edgehybrid.agent/files/transcripts/msg_123.json"

        fts.index(
            docId = "msg_123",
            fullContent = largeContent,
            fullPayloadUri = payloadUri,
            previewMaxLength = 100
        )

        val results = fts.search("crucial data")
        assertEquals(1, results.size)
        assertEquals("msg_123", results[0].docId)
        assertEquals(payloadUri, results[0].fullPayloadUri)
        assertTrue(results[0].preview.length <= 102) // truncated preview with ellipsis
    }

    @Test
    fun `empty query or missing terms return empty list`() {
        val fts = OnDeviceFtsIndex()
        fts.index("doc1", "Hello world")

        assertTrue(fts.search("").isEmpty())
        assertTrue(fts.search("nonexistentterm12345").isEmpty())
    }
}
