package com.edgehybrid.agent.mcp

import kotlinx.serialization.Serializable

/**
 * Configuration for a remote Model Context Protocol (MCP) server endpoint.
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val bearerToken: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val isEnabled: Boolean = true
)
