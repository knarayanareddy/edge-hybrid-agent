package com.edgehybrid.agent.plugins

import com.edgehybrid.agent.tool.ToolExecutionOutcome
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Executes OpenAPI REST actions dynamically over HTTP with authentication and parameter binding.
 */
class PluginActionExecutor(
    private val httpClient: HttpClient
) {

    suspend fun execute(
        baseUrl: String,
        endpoint: ParsedPluginEndpoint,
        arguments: JsonObject,
        bearerToken: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): ToolExecutionOutcome = withContext(Dispatchers.IO) {
        try {
            // 1. Resolve path parameters
            var resolvedPath = endpoint.path
            endpoint.pathParameters.forEach { paramName ->
                val paramVal = arguments[paramName]?.jsonPrimitive?.contentOrNull ?: ""
                val encoded = URLEncoder.encode(paramVal, StandardCharsets.UTF_8.toString())
                resolvedPath = resolvedPath.replace("{$paramName}", encoded)
            }

            // 2. Resolve query parameters
            val queryPairs = mutableListOf<String>()
            endpoint.queryParameters.forEach { paramName ->
                arguments[paramName]?.jsonPrimitive?.contentOrNull?.let { value ->
                    val k = URLEncoder.encode(paramName, StandardCharsets.UTF_8.toString())
                    val v = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
                    queryPairs.add("$k=$v")
                }
            }

            val queryString = if (queryPairs.isNotEmpty()) "?${queryPairs.joinToString("&")}" else ""
            val fullUrl = "${baseUrl.trimEnd('/')}/${resolvedPath.trimStart('/')}$queryString"

            // 3. Resolve request body for modifying requests (POST, PUT, PATCH)
            val isBodyMethod = endpoint.method in listOf("POST", "PUT", "PATCH")
            val bodyPayload = if (isBodyMethod) {
                val bodyMap = arguments.filterKeys {
                    it !in endpoint.pathParameters && it !in endpoint.queryParameters && it !in endpoint.headerParameters
                }
                if (bodyMap.isNotEmpty()) JsonObject(bodyMap).toString() else null
            } else null

            // 4. Dispatch HTTP request
            val response = httpClient.request(fullUrl) {
                this.method = HttpMethod.parse(endpoint.method)
                header(HttpHeaders.Accept, "application/json, text/plain")
                if (!bearerToken.isNullOrBlank()) {
                    header(HttpHeaders.Authorization, "Bearer $bearerToken")
                }
                extraHeaders.forEach { (k, v) -> header(k, v) }

                endpoint.headerParameters.forEach { headerName ->
                    arguments[headerName]?.jsonPrimitive?.contentOrNull?.let { v ->
                        header(headerName, v)
                    }
                }

                if (bodyPayload != null) {
                    contentType(ContentType.Application.Json)
                    setBody(bodyPayload)
                }
            }

            val rawBody = response.bodyAsText()
            val truncatedBody = if (rawBody.length > MAX_PAYLOAD_CHARS) {
                rawBody.take(MAX_PAYLOAD_CHARS) + "\n...[truncated remainder of response]"
            } else {
                rawBody
            }

            val isError = response.status.value !in 200..299
            ToolExecutionOutcome(
                content = if (isError) {
                    buildJsonObject {
                        put("status_code", response.status.value)
                        put("error", "HTTP ${response.status.value}: ${response.status.description}")
                        put("body", truncatedBody)
                    }.toString()
                } else {
                    truncatedBody
                },
                isError = isError
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ToolExecutionOutcome(
                content = buildJsonObject {
                    put("error", e.message ?: "Plugin HTTP dispatch failed")
                    put("endpoint", "${endpoint.method} ${endpoint.path}")
                }.toString(),
                isError = true
            )
        }
    }

    private companion object {
        const val MAX_PAYLOAD_CHARS = 16_384
    }
}
