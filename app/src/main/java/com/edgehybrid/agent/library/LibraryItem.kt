package com.edgehybrid.agent.library

import kotlinx.serialization.Serializable

@Serializable
enum class LibraryItemType {
    DOCUMENT,
    CODE,
    IMAGE,
    FOLDER
}

@Serializable
data class LibraryItem(
    val id: String,
    val name: String,
    val type: LibraryItemType,
    val sizeBytes: Long = 0,
    val summary: String = "",
    val folderPath: String = "",
    val isFavorite: Boolean = false,
    val isSuggested: Boolean = false,
    val textContent: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun formattedSize(): String = when {
        sizeBytes < 1024 -> "$sizeBytes B"
        sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
        else -> String.format("%.1f MB", sizeBytes.toDouble() / (1024 * 1024))
    }
}
