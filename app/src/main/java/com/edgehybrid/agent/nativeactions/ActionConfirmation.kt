package com.edgehybrid.agent.nativeactions

data class ActionConfirmation(
    val id: String,
    val tool: String,
    val summary: String,
    val params: Map<String, Any>
)