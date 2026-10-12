package com.edgehybrid.agent.scheduled

import kotlinx.serialization.Serializable

@Serializable
enum class ScheduleRecurrence {
    DAILY,
    WEEKDAYS,
    WEEKLY,
    MONTHLY,
    CUSTOM
}

@Serializable
data class ScheduledTask(
    val id: String,
    val title: String,
    val description: String,
    val prompt: String,
    val recurrence: ScheduleRecurrence,
    val daysOfWeek: List<Int> = emptyList(), // 1=Mon, 2=Tue, ..., 7=Sun
    val hour: Int = 9,
    val minute: Int = 0,
    val isEnabled: Boolean = false,
    val category: String = "GENERAL",
    val iconEmoji: String = "⚡",
    val lastRunTime: Long? = null,
    val lastResultSnippet: String? = null,
    val isPreset: Boolean = false
) {
    /** Formats recurrence for UI display, e.g. "Every Monday and Thursday at 09:00" */
    fun formattedSchedule(): String {
        val timeStr = String.format("%02d:%02d", hour, minute)
        return when (recurrence) {
            ScheduleRecurrence.DAILY -> "Every day at $timeStr"
            ScheduleRecurrence.WEEKDAYS -> "Every weekday at $timeStr"
            ScheduleRecurrence.WEEKLY -> {
                if (daysOfWeek.isEmpty()) "Weekly at $timeStr"
                else {
                    val days = daysOfWeek.map { dayIndexToName(it) }.joinToString(" and ")
                    "Every $days at $timeStr"
                }
            }
            ScheduleRecurrence.MONTHLY -> "Monthly at $timeStr"
            ScheduleRecurrence.CUSTOM -> "Custom schedule at $timeStr"
        }
    }

    private fun dayIndexToName(day: Int): String = when (day) {
        1 -> "Monday"
        2 -> "Tuesday"
        3 -> "Wednesday"
        4 -> "Thursday"
        5 -> "Friday"
        6 -> "Saturday"
        7 -> "Sunday"
        else -> "Day $day"
    }
}
