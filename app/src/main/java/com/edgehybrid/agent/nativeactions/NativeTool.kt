package com.edgehybrid.agent.nativeactions

enum class NativeTool(
    val toolName: String,
    val description: String,
    val parameterSchema: String
) {
    CREATE_CALENDAR_EVENT(
        "create_calendar_event",
        "Creates an event in the system calendar",
        """{"type":"object","properties":{"title":{"type":"string","description":"Event title"},"startTime":{"type":"integer","format":"int64","description":"Start time in epoch milliseconds"},"endTime":{"type":"integer","format":"int64","description":"End time in epoch milliseconds"},"description":{"type":"string","description":"Event description"},"location":{"type":"string","description":"Event location"}},"required":["title"],"additionalProperties":false}"""
    ),
    CREATE_QUICK_NOTE(
        "create_quick_note",
        "Saves a quick note into local Room database",
        """{"type":"object","properties":{"title":{"type":"string","description":"Note title"},"content":{"type":"string","description":"Note content"}},"required":["title","content"],"additionalProperties":false}"""
    ),
    SET_TIMER(
        "set_timer",
        "Sets a countdown timer via system AlarmClock",
        """{"type":"object","properties":{"seconds":{"type":"integer","minimum":1,"description":"Countdown duration in seconds"},"message":{"type":"string","description":"Timer label"}},"required":["seconds","message"],"additionalProperties":false}"""
    ),
    SEND_SMS(
        "send_sms",
        "Prepares or sends an SMS message (requires explicit confirmation)",
        """{"type":"object","properties":{"phone":{"type":"string","description":"Recipient phone number"},"message":{"type":"string","description":"SMS message"}},"required":["phone","message"],"additionalProperties":false}"""
    ),
    TOGGLE_FLASHLIGHT(
        "toggle_flashlight",
        "Toggles device camera torch on or off",
        """{"type":"object","properties":{},"required":[],"additionalProperties":false}"""
    )
}