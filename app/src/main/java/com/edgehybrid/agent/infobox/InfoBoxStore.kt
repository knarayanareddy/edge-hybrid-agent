package com.edgehybrid.agent.infobox

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InfoBoxStore(
    private val baseDir: File?
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.filesDir)

    constructor() : this(null as File?)

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _items = MutableStateFlow<List<InfoBoxItem>>(emptyList())
    val items: StateFlow<List<InfoBoxItem>> = _items.asStateFlow()

    private val storageFile: File?
        get() = baseDir?.let { File(it, "infobox_store.json") }

    init {
        loadItems()
    }

    private fun loadItems() {
        val file = storageFile
        if (file != null && file.exists()) {
            try {
                val content = file.readText()
                _items.value = json.decodeFromString<List<InfoBoxItem>>(content)
                return
            } catch (_: Exception) {
                // fall through to empty
            }
        }
        _items.value = emptyList()
    }

    /**
     * Ingests raw text, article snippet, or hyperlink captured from system text selection or share sheet.
     */
    suspend fun addFromText(
        rawText: String,
        sourceApp: String? = null,
        preferredTitle: String? = null
    ): InfoBoxItem = mutex.withLock {
        val trimmed = rawText.trim()
        val urlRegex = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
        val extractedUrl = urlRegex.find(trimmed)?.value

        val type = when {
            extractedUrl != null && (trimmed == extractedUrl || trimmed.length <= extractedUrl.length + 20) ->
                InfoBoxItemType.HYPERLINK
            trimmed.length > 300 ->
                InfoBoxItemType.ARTICLE_EXCERPT
            else ->
                InfoBoxItemType.TEXT_SNIPPET
        }

        val resolvedTitle = when {
            !preferredTitle.isNullOrBlank() -> preferredTitle.trim()
            extractedUrl != null -> runCatching {
                URI(extractedUrl).host?.removePrefix("www.") ?: extractedUrl
            }.getOrDefault("Web Link")
            else -> {
                val firstLine = trimmed.lines().firstOrNull()?.trim() ?: "Text Snippet"
                if (firstLine.length > 60) firstLine.take(57) + "..." else firstLine
            }
        }

        val item = InfoBoxItem(
            content = trimmed,
            title = resolvedTitle,
            url = extractedUrl,
            type = type,
            sourceApp = sourceApp
        )

        val current = _items.value.toMutableList()
        current.add(0, item)
        _items.value = current
        persist()
        item
    }

    suspend fun addItem(item: InfoBoxItem) = mutex.withLock {
        val current = _items.value.toMutableList()
        current.removeAll { it.id == item.id }
        current.add(0, item)
        _items.value = current
        persist()
    }

    suspend fun deleteItem(id: String) = mutex.withLock {
        val current = _items.value.toMutableList()
        current.removeAll { it.id == id }
        _items.value = current
        persist()
    }

    suspend fun toggleFavorite(id: String) = mutex.withLock {
        val current = _items.value.map {
            if (it.id == id) it.copy(isFavorite = !it.isFavorite) else it
        }
        _items.value = current
        persist()
    }

    suspend fun updateAiSummary(id: String, summary: String) = mutex.withLock {
        val current = _items.value.map {
            if (it.id == id) it.copy(aiSummary = summary) else it
        }
        _items.value = current
        persist()
    }

    suspend fun clearAll() = mutex.withLock {
        _items.value = emptyList()
        persist()
    }

    /**
     * Returns items added during yesterday's calendar date.
     */
    fun getItemsFromYesterday(): List<InfoBoxItem> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
        return _items.value.filter { it.dateString == yesterdayStr }
    }

    /**
     * Returns items added during today's calendar date.
     */
    fun getItemsFromToday(): List<InfoBoxItem> {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return _items.value.filter { it.dateString == todayStr }
    }

    /**
     * Generates a prompt bundle of captured items for the daily scheduled overview synthesis.
     */
    fun buildDailyDigestPrompt(targetItems: List<InfoBoxItem>): String {
        if (targetItems.isEmpty()) {
            return "No new articles or text snippets were captured in the Info Box yesterday."
        }

        val sb = StringBuilder()
        sb.appendLine("Synthesize the following ${targetItems.size} research items, articles, and snippets captured in the user's Info Box into an executive briefing:")
        sb.appendLine()

        targetItems.forEachIndexed { index, item ->
            sb.appendLine("Item #${index + 1}: [${item.type.name}] ${item.title}")
            if (!item.url.isNullOrBlank()) {
                sb.appendLine("Link: ${item.url}")
            }
            sb.appendLine("Content:\n${item.content}")
            sb.appendLine("Captured: ${item.formattedDateTime()}")
            sb.appendLine("---")
        }

        sb.appendLine()
        sb.appendLine("Briefing instructions:")
        sb.appendLine("1. Organize findings by core domain / topic theme.")
        sb.appendLine("2. Extract high-yield insights and key takeaways.")
        sb.appendLine("3. Suggest actionable next steps or relevant follow-up reading.")
        return sb.toString()
    }

    private fun persist() {
        val file = storageFile ?: return
        try {
            file.writeText(json.encodeToString(_items.value))
        } catch (_: Exception) {
            // silent write error
        }
    }
}
