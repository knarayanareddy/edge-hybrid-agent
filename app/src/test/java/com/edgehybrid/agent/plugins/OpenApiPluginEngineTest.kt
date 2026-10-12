package com.edgehybrid.agent.plugins

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenApiPluginEngineTest {

    private val sampleOpenApiSpec = """
    {
      "openapi": "3.0.1",
      "info": {
        "title": "Weather Plugin",
        "version": "v1"
      },
      "servers": [
        {
          "url": "https://api.weather-service.com"
        }
      ],
      "paths": {
        "/forecast/{city}": {
          "get": {
            "operationId": "getForecast",
            "summary": "Get weather forecast for a city",
            "parameters": [
              {
                "name": "city",
                "in": "path",
                "required": true,
                "description": "City name",
                "schema": {
                  "type": "string"
                }
              },
              {
                "name": "days",
                "in": "query",
                "required": false,
                "description": "Number of days",
                "schema": {
                  "type": "integer"
                }
              }
            ]
          }
        },
        "/alerts": {
          "post": {
            "operationId": "createAlert",
            "summary": "Register weather alert",
            "requestBody": {
              "required": true,
              "content": {
                "application/json": {
                  "schema": {
                    "type": "object",
                    "properties": {
                      "severity": { "type": "string" },
                      "region": { "type": "string" }
                    },
                    "required": ["region"]
                  }
                }
              }
            }
          }
        }
      }
    }
    """.trimIndent()

    @Test
    fun `OpenApiSpecParser parses servers, paths, parameters and creates valid ToolDefinitions`() {
        val root = Json.parseToJsonElement(sampleOpenApiSpec).jsonObject
        val (baseUrl, endpoints) = OpenApiSpecParser.parse("weather", root)

        assertEquals("https://api.weather-service.com", baseUrl)
        assertEquals(2, endpoints.size)

        val getEndpoint = endpoints.first { it.operationId == "getForecast" }
        assertEquals("GET", getEndpoint.method)
        assertEquals("/forecast/{city}", getEndpoint.path)
        assertEquals(listOf("city"), getEndpoint.pathParameters)
        assertEquals(listOf("days"), getEndpoint.queryParameters)
        assertEquals("weather_getForecast", getEndpoint.toolName)

        val postEndpoint = endpoints.first { it.operationId == "createAlert" }
        assertEquals("POST", postEndpoint.method)
        assertEquals("/alerts", postEndpoint.path)
        assertTrue(postEndpoint.toolDefinition.function.parameters.toString().contains("region"))
    }

    @Test
    fun `PluginManifest parses ChatGPT ai-plugin json standard correctly`() {
        val manifestJson = """
        {
          "schema_version": "v1",
          "name_for_model": "weather_plugin",
          "name_for_human": "Weather Service",
          "description_for_model": "Plugin for checking weather forecasts.",
          "description_for_human": "Check weather forecasts worldwide.",
          "auth": {
            "type": "service_http",
            "authorization_type": "bearer"
          },
          "api": {
            "type": "openapi",
            "url": "https://api.weather-service.com/openapi.json"
          },
          "logo_url": "https://api.weather-service.com/logo.png"
        }
        """.trimIndent()

        val manifest = Json.decodeFromString<PluginManifest>(manifestJson)
        assertEquals("v1", manifest.schemaVersion)
        assertEquals("weather_plugin", manifest.nameForModel)
        assertEquals("Weather Service", manifest.nameForHuman)
        assertEquals("service_http", manifest.auth.type)
        assertEquals("https://api.weather-service.com/openapi.json", manifest.api.url)
    }

    @Test
    fun `PluginRegistry registers and dispatches tool execution via HttpClient`() = runTest {
        val mockEngine = MockEngine { request ->
            when {
                request.url.encodedPath == "/forecast/Amsterdam" && request.headers[HttpHeaders.Authorization] == "Bearer test-token" -> {
                    respond(
                        content = """{"city":"Amsterdam","temp_c":18,"condition":"Partly Cloudy"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> respond(
                    content = """{"error":"not found"}""",
                    status = HttpStatusCode.NotFound
                )
            }
        }

        val client = HttpClient(mockEngine)
        val registry = PluginRegistry(client)

        val tools = registry.registerOpenApi(
            id = "weather",
            name = "Weather API",
            description = "Live weather forecasts",
            openApiJsonStr = sampleOpenApiSpec,
            token = "test-token"
        )

        assertEquals(2, tools.size)
        assertTrue(registry.toolNames().contains("weather_getForecast"))

        val callArgs = buildJsonObject {
            put("city", "Amsterdam")
            put("days", 3)
        }

        val outcome = registry.executeTool("weather_getForecast", callArgs)
        assertNotNull(outcome)
        assertFalse(outcome!!.isError)
        assertTrue(outcome.content.contains("Amsterdam"))
        assertTrue(outcome.content.contains("Partly Cloudy"))
    }
}
