package com.edgehybrid.agent.plugins

import com.edgehybrid.agent.data.model.FunctionDefinition
import com.edgehybrid.agent.data.model.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Parsed representation of an OpenAPI 3.0 endpoint operation.
 */
data class ParsedPluginEndpoint(
    val operationId: String,
    val toolName: String,
    val method: String,
    val path: String,
    val summary: String,
    val description: String,
    val pathParameters: List<String>,
    val queryParameters: List<String>,
    val headerParameters: List<String>,
    val toolDefinition: ToolDefinition
)

/**
 * Parses OpenAPI 3.0/3.1 specifications into executable tool definitions and endpoint metadata.
 *
 * Implements ChatGPT GPT Actions specification conversion.
 */
object OpenApiSpecParser {

    fun parse(
        pluginId: String,
        openApiJson: JsonObject,
        overrideBaseUrl: String? = null
    ): Pair<String, List<ParsedPluginEndpoint>> {
        val serverUrl = overrideBaseUrl
            ?: extractServerUrl(openApiJson)
            ?: ""

        val pathsObj = openApiJson["paths"] as? JsonObject ?: JsonObject(emptyMap())
        val endpoints = mutableListOf<ParsedPluginEndpoint>()

        pathsObj.forEach { (path, pathItemElement) ->
            val pathItem = pathItemElement as? JsonObject ?: return@forEach
            val supportedMethods = listOf("get", "post", "put", "delete", "patch")

            supportedMethods.forEach { method ->
                val operation = pathItem[method] as? JsonObject ?: return@forEach
                val endpoint = parseOperation(pluginId, method.uppercase(Locale.ROOT), path, operation)
                endpoints.add(endpoint)
            }
        }

        return Pair(serverUrl, endpoints)
    }

    private fun extractServerUrl(openApiJson: JsonObject): String? {
        val servers = openApiJson["servers"] as? JsonArray
        if (!servers.isNullOrEmpty()) {
            val first = servers[0] as? JsonObject
            val url = first?.get("url")?.jsonPrimitive?.contentOrNull
            if (!url.isNullOrBlank()) {
                return url.trimEnd('/')
            }
        }
        return null
    }

    private fun parseOperation(
        pluginId: String,
        method: String,
        path: String,
        operation: JsonObject
    ): ParsedPluginEndpoint {
        val rawOpId = operation["operationId"]?.jsonPrimitive?.contentOrNull
            ?: "${method.lowercase(Locale.ROOT)}_${sanitizePath(path)}"

        // Clean tool name compatible with OpenAI tool naming conventions: ^[a-zA-Z0-9_-]{1,64}$
        val toolName = "${pluginId}_$rawOpId".take(64)
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")

        val summary = operation["summary"]?.jsonPrimitive?.contentOrNull ?: ""
        val description = operation["description"]?.jsonPrimitive?.contentOrNull ?: summary

        val pathParams = mutableListOf<String>()
        val queryParams = mutableListOf<String>()
        val headerParams = mutableListOf<String>()
        val propertiesBuilder = mutableMapOf<String, JsonElement>()
        val requiredList = mutableListOf<String>()

        // 1. Process parameters array
        val parameters = operation["parameters"] as? JsonArray
        parameters?.forEach { paramElem ->
            val paramObj = paramElem as? JsonObject ?: return@forEach
            val name = paramObj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val `in` = paramObj["in"]?.jsonPrimitive?.contentOrNull ?: "query"
            val required = paramObj["required"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            val paramDesc = paramObj["description"]?.jsonPrimitive?.contentOrNull ?: ""
            val schema = paramObj["schema"] as? JsonObject

            when (`in`) {
                "path" -> pathParams.add(name)
                "query" -> queryParams.add(name)
                "header" -> headerParams.add(name)
            }

            if (required) {
                requiredList.add(name)
            }

            val paramSchemaType = schema?.get("type")?.jsonPrimitive?.contentOrNull ?: "string"
            propertiesBuilder[name] = buildJsonObject {
                put("type", paramSchemaType)
                put("description", paramDesc)
            }
        }

        // 2. Process requestBody (JSON schema)
        val requestBody = operation["requestBody"] as? JsonObject
        if (requestBody != null) {
            val contentObj = requestBody["content"] as? JsonObject
            val jsonContent = contentObj?.get("application/json") as? JsonObject
            val bodySchema = jsonContent?.get("schema") as? JsonObject

            if (bodySchema != null) {
                val bodyProps = bodySchema["properties"] as? JsonObject
                bodyProps?.forEach { (propName, propDef) ->
                    propertiesBuilder[propName] = propDef
                }
                val bodyRequired = bodySchema["required"] as? JsonArray
                bodyRequired?.forEach { req ->
                    req.jsonPrimitive.contentOrNull?.let { requiredList.add(it) }
                }
            }
        }

        val parametersSchema = buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(propertiesBuilder))
            if (requiredList.isNotEmpty()) {
                put("required", buildJsonArray {
                    requiredList.distinct().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                })
            }
            put("additionalProperties", false)
        }

        val toolDefinition = ToolDefinition(
            function = FunctionDefinition(
                name = toolName,
                description = description.ifBlank { "Execute $method on $path" },
                parameters = parametersSchema
            )
        )

        return ParsedPluginEndpoint(
            operationId = rawOpId,
            toolName = toolName,
            method = method,
            path = path,
            summary = summary,
            description = description,
            pathParameters = pathParams,
            queryParameters = queryParams,
            headerParameters = headerParams,
            toolDefinition = toolDefinition
        )
    }

    private fun sanitizePath(path: String): String =
        path.replace('/', '_')
            .replace("{", "")
            .replace("}", "")
            .trim('_')
}
