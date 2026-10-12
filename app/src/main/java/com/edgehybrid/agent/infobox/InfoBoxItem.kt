package com.edgehybrid.agent.infobox

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
enum class InfoBoxItemType {
    HYPERLINK,
    TEXT_SNIPPET,
    ARTICLE_EXCERPT
}

@Serializable
data class InfoBoxItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val title: String = "",
    val url: String? = null,
    val type: InfoBoxItemType = InfoBoxItemType.TEXT_SNIPPET,
    val sourceApp: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val dateString: String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp)),
    val isFavorite: Boolean = false,
    val aiSummary: String? = null,
    val tags: List<String> = emptyList()
) {
    /** Formats timestamp into user-friendly relative or calendar string */
    fun formattedDateTime(): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = timeFormat.format(Date(timestamp))

        return when {
            diff < 60_000L -> "Just now"
            diff < 3600_000L -> "${diff / 60_000L}m ago"
            android.text.format.DateUtils.isToday(timestamp) -> "Today at $timeStr"
            diff < 2 * 86400_000L -> "Yesterday at $timeStr"
            else -> SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }

    /** Display preview snippet */
    fun previewSnippet(maxLength: Int = 140): String {
        val clean = content.replace("\n", " ").trim()
        return if (clean.length > maxLength) clean.take(maxLength - 3) + "..." else clean
    }
}
