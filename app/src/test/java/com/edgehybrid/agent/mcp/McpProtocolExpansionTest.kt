package com.edgehybrid.agent.mcp

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class McpProtocolExpansionTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `McpResource models serialize and deserialize according to MCP 1_0 spec`() {
        val resourceJson = """
        {
          "uri": "postgres://database/schema",
          "name": "Database Schema",
          "description": "Public schema tables and columns",
          "mimeType": "application/json"
        }
        """.trimIndent()

        val resource = json.decodeFromString<McpResource>(resourceJson)
        assertEquals("postgres://database/schema", resource.uri)
        assertEquals("Database Schema", resource.name)
        assertEquals("application/json", resource.mimeType)

        val contentJson = """
        {
          "uri": "postgres://database/schema",
          "mimeType": "application/json",
          "text": "{\"tables\":[\"users\",\"orders\"]}"
        }
        """.trimIndent()

        val content = json.decodeFromString<McpResourceContent>(contentJson)
        assertEquals("postgres://database/schema", content.uri)
        assertNotNull(content.text)
    }

    @Test
    fun `McpPrompt models serialize and deserialize according to MCP 1_0 spec`() {
        val promptJson = """
        {
          "name": "analyze_sql_query",
          "description": "Analyze SQL query performance and indexes",
          "arguments": [
            {
              "name": "query",
              "description": "SQL query to inspect",
              "required": true
            }
          ]
        }
        """.trimIndent()

        val prompt = json.decodeFromString<McpPrompt>(promptJson)
        assertEquals("analyze_sql_query", prompt.name)
        assertEquals(1, prompt.arguments.size)
        assertEquals("query", prompt.arguments[0].name)
        assertEquals(true, prompt.arguments[0].required)
    }
}
