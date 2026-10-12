package com.edgehybrid.agent.library

import android.content.Context
import com.edgehybrid.agent.rag.OnDeviceVectorStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryStore(
    private val baseDir: File?,
    private val vectorStore: OnDeviceVectorStore?
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        vectorStore: OnDeviceVectorStore
    ) : this(context.filesDir, vectorStore)

    constructor(vectorStore: OnDeviceVectorStore? = null) : this(null, vectorStore)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _items = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items: StateFlow<List<LibraryItem>> = _items.asStateFlow()

    private val indexFile: File?
        get() = baseDir?.let { File(it, "library_index.json") }

    init {
        loadIndex()
    }

    private fun loadIndex() {
        val file = indexFile
        if (file != null && file.exists()) {
            try {
                val content = file.readText()
                _items.value = json.decodeFromString<List<LibraryItem>>(content)
                return
            } catch (_: Exception) {
                // fall through
            }
        }
        _items.value = emptyList()
    }

    suspend fun addItem(item: LibraryItem) = mutex.withLock {
        val current = _items.value.toMutableList()
        current.removeAll { it.id == item.id }
        current.add(0, item)
        _items.value = current
        persist()

        // Index in local RAG vector store if non-empty text content
        if (item.textContent.isNotBlank() && vectorStore != null) {
            val chunks = chunkText(item.textContent)
            val augmenter = com.edgehybrid.agent.rag.RagContextAugmenter(vectorStore)
            chunks.forEachIndexed { idx, chunk ->
                val vec = augmenter.createTermVector(chunk, 64)
                vectorStore.addDocument(
                    id = "${item.id}_chunk_$idx",
                    text = chunk,
                    vector = vec
                )
            }
        }
    }

    suspend fun toggleFavorite(id: String) = mutex.withLock {
        _items.value = _items.value.map {
            if (it.id == id) it.copy(isFavorite = !it.isFavorite) else it
        }
        persist()
    }

    suspend fun deleteItem(id: String) = mutex.withLock {
        _items.value = _items.value.filter { it.id != id }
        persist()
    }

    fun getFilteredItems(tab: String, searchQuery: String): List<LibraryItem> {
        val q = searchQuery.trim().lowercase()
        return _items.value.filter { item ->
            val matchesTab = when (tab.lowercase()) {
                "suggested" -> item.isSuggested || item.isFavorite
                "favorites" -> item.isFavorite
                "folders" -> item.type == LibraryItemType.FOLDER || item.folderPath.isNotBlank()
                "images" -> item.type == LibraryItemType.IMAGE
                "documents" -> item.type == LibraryItemType.DOCUMENT
                "code" -> item.type == LibraryItemType.CODE
                else -> true
            }
            val matchesQuery = q.isEmpty() ||
                item.name.lowercase().contains(q) ||
                item.summary.lowercase().contains(q)

            matchesTab && matchesQuery
        }
    }

    private fun persist() {
        val file = indexFile ?: return
        try {
            file.writeText(json.encodeToString(_items.value))
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun chunkText(text: String, chunkSizeChars: Int = 1000): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = (start + chunkSizeChars).coerceAtMost(text.length)
            chunks.add(text.substring(start, end))
            start = end
        }
        return chunks
    }
}
