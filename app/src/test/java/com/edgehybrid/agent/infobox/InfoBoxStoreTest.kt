package com.edgehybrid.agent.infobox

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class InfoBoxStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: InfoBoxStore

    @Before
    fun setUp() {
        store = InfoBoxStore(tempFolder.root)
    }

    @Test
    fun testAddFromTextDetectsHyperlinkAndExtractsDomain() = runTest {
        val linkText = "https://android-developers.googleblog.com/2026/04/gemini-nano.html"
        val item = store.addFromText(rawText = linkText, sourceApp = "Samsung Internet")

        assertEquals(InfoBoxItemType.HYPERLINK, item.type)
        assertEquals(linkText, item.url)
        assertEquals("android-developers.googleblog.com", item.title)
        assertEquals("Samsung Internet", item.sourceApp)
        assertEquals(1, store.items.value.size)
    }

    @Test
    fun testAddFromTextDetectsSnippetVsExcerpt() = runTest {
        val shortSnippet = "Agentic workflows prioritize goal completion with tool use."
        val item1 = store.addFromText(rawText = shortSnippet, sourceApp = "Chrome")
        assertEquals(InfoBoxItemType.TEXT_SNIPPET, item1.type)
        assertEquals(shortSnippet, item1.title)

        val longArticle = "A".repeat(350)
        val item2 = store.addFromText(
            rawText = longArticle,
            sourceApp = "Notes",
            preferredTitle = "Long Research Paper"
        )
        assertEquals(InfoBoxItemType.ARTICLE_EXCERPT, item2.type)
        assertEquals("Long Research Paper", item2.title)
        assertEquals(2, store.items.value.size)
    }

    @Test
    fun testToggleFavoriteAndDelete() = runTest {
        val item = store.addFromText("Important memo to review tomorrow.")
        assertFalse(item.isFavorite)

        store.toggleFavorite(item.id)
        assertTrue(store.items.value.first { it.id == item.id }.isFavorite)

        store.toggleFavorite(item.id)
        assertFalse(store.items.value.first { it.id == item.id }.isFavorite)

        store.deleteItem(item.id)
        assertTrue(store.items.value.isEmpty())
    }

    @Test
    fun testGetYesterdayItemsAndBuildDailyDigestPrompt() = runTest {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayTimestamp = cal.timeInMillis

        val yesterdayItem = InfoBoxItem(
            id = "yest_1",
            content = "Researchers announce breakthrough in quantized transformer inference on Snapdragon NPU.",
            title = "Snapdragon NPU Breakthrough",
            url = "https://qualcomm.com/news/npu-speedup",
            type = InfoBoxItemType.HYPERLINK,
            timestamp = yesterdayTimestamp,
            sourceApp = "Google Chrome"
        )
        val todayItem = InfoBoxItem(
            id = "today_1",
            content = "Quick task note for afternoon standup.",
            title = "Task Note",
            type = InfoBoxItemType.TEXT_SNIPPET,
            timestamp = System.currentTimeMillis()
        )

        store.addItem(yesterdayItem)
        store.addItem(todayItem)

        val yesterdayList = store.getItemsFromYesterday()
        assertEquals(1, yesterdayList.size)
        assertEquals("yest_1", yesterdayList.first().id)

        val prompt = store.buildDailyDigestPrompt(yesterdayList)
        assertTrue(prompt.contains("Snapdragon NPU Breakthrough"))
        assertTrue(prompt.contains("https://qualcomm.com/news/npu-speedup"))
        assertTrue(prompt.contains("Briefing instructions:"))
    }

    @Test
    fun testPersistenceReloadAcrossInstances() = runTest {
        store.addFromText("Persistent note 1", preferredTitle = "Persisted Note")
        assertEquals(1, store.items.value.size)

        // Instantiate new store targeting the same directory
        val store2 = InfoBoxStore(tempFolder.root)
        assertEquals(1, store2.items.value.size)
        assertEquals("Persisted Note", store2.items.value.first().title)
    }
}
