package com.edgehybrid.agent.plugins

import com.edgehybrid.agent.data.local.SecureKeyStore
import com.edgehybrid.agent.data.model.ToolDefinition
import com.edgehybrid.agent.tool.ToolExecutionOutcome
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PluginEntry(
    val id: String,
    val name: String,
    val description: String,
    val baseUrl: String,
    val isEnabled: Boolean = true,
    val requiresAuth: Boolean = false
)

/**
 * Unified Registry for ChatGPT Plugins and OpenAPI 3.0 Actions.
 */
@Singleton
class PluginRegistry(
    private val httpClient: HttpClient,
    private val keyStore: SecureKeyStore? = null
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val executor = PluginActionExecutor(httpClient)

    private val plugins = ConcurrentHashMap<String, PluginEntry>()
    private val endpointsByTool = ConcurrentHashMap<String, Pair<PluginEntry, ParsedPluginEndpoint>>()
    private val pluginTokens = ConcurrentHashMap<String, String>()

    fun registerOpenApi(
        id: String,
        name: String,
        description: String,
        openApiJsonStr: String,
        overrideBaseUrl: String? = null,
        token: String? = null
    ): List<ToolDefinition> {
        val rootObj = json.parseToJsonElement(openApiJsonStr).jsonObject
        val (serverUrl, parsedEndpoints) = OpenApiSpecParser.parse(id, rootObj, overrideBaseUrl)

        val entry = PluginEntry(
            id = id,
            name = name,
            description = description,
            baseUrl = serverUrl,
            isEnabled = true,
            requiresAuth = !token.isNullOrBlank()
        )
        plugins[id] = entry

        if (!token.isNullOrBlank()) {
            pluginTokens[id] = token
            keyStore?.setMcpServerToken("plugin:$id", token)
        }

        val toolDefs = mutableListOf<ToolDefinition>()
        parsedEndpoints.forEach { ep ->
            endpointsByTool[ep.toolName] = Pair(entry, ep)
            toolDefs.add(ep.toolDefinition)
        }
        return toolDefs
    }

    fun registerManifest(
        manifest: PluginManifest,
        openApiJsonStr: String,
        token: String? = null
    ): List<ToolDefinition> = registerOpenApi(
        id = manifest.nameForModel,
        name = manifest.nameForHuman,
        description = manifest.descriptionForHuman,
        openApiJsonStr = openApiJsonStr,
        overrideBaseUrl = null,
        token = token
    )

    fun listPlugins(): List<PluginEntry> = plugins.values.toList()

    fun setEnabled(pluginId: String, enabled: Boolean) {
        plugins[pluginId]?.let {
            plugins[pluginId] = it.copy(isEnabled = enabled)
        }
    }

    fun removePlugin(pluginId: String) {
        plugins.remove(pluginId)
        pluginTokens.remove(pluginId)
        endpointsByTool.entries.removeIf { it.value.first.id == pluginId }
        keyStore?.clearMcpServerToken("plugin:$pluginId")
    }

    fun allToolDefinitions(): List<ToolDefinition> =
        endpointsByTool.values
            .filter { it.first.isEnabled }
            .map { it.second.toolDefinition }

    fun toolNames(): Set<String> =
        endpointsByTool.filterValues { it.first.isEnabled }.keys

    suspend fun executeTool(toolName: String, arguments: JsonObject): ToolExecutionOutcome? {
        val mapping = endpointsByTool[toolName] ?: return null
        val (plugin, endpoint) = mapping
        if (!plugin.isEnabled) {
            return ToolExecutionOutcome("""{"error": "Plugin ${plugin.id} is disabled"}""", isError = true)
        }

        val token = pluginTokens[plugin.id] ?: keyStore?.getMcpServerToken("plugin:${plugin.id}").takeIf { !it.isNullOrBlank() }
        return executor.execute(
            baseUrl = plugin.baseUrl,
            endpoint = endpoint,
            arguments = arguments,
            bearerToken = token
        )
    }
}
