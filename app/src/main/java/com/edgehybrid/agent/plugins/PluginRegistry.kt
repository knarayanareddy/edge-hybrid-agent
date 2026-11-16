package com.edgehybrid.agent.plugins

import android.content.Context
import com.edgehybrid.agent.data.local.SecureKeyStore
import com.edgehybrid.agent.data.model.ToolDefinition
import com.edgehybrid.agent.tool.ToolExecutionOutcome
import io.ktor.client.HttpClient
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class PluginEntry(
    val id: String,
    val name: String,
    val description: String,
    val baseUrl: String,
    val isEnabled: Boolean = true,
    val requiresAuth: Boolean = false,
    val authType: String = "bearer",
    val authParameterName: String? = null
)

@Serializable
private data class PersistedPlugin(
    val id: String,
    val name: String,
    val description: String,
    val openApiSpec: String,
    val baseUrl: String,
    val isEnabled: Boolean,
    val requiresAuth: Boolean,
    val authType: String = "bearer",
    val authParameterName: String? = null
)

/** Persistent registry for OpenAPI integrations. Credentials stay in SecureKeyStore. */
@Singleton
class PluginRegistry(
    private val httpClient: HttpClient,
    private val keyStore: SecureKeyStore? = null,
    private val context: Context? = null
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val executor = PluginActionExecutor(httpClient)
    private val plugins = ConcurrentHashMap<String, PluginEntry>()
    private val persisted = ConcurrentHashMap<String, PersistedPlugin>()
    private val pluginTokens = ConcurrentHashMap<String, String>()
    private val endpointsByTool = ConcurrentHashMap<String, Pair<PluginEntry, ParsedPluginEndpoint>>()
    private val preferences by lazy {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    init {
        restore()
    }

    /** Install an OpenAPI document pasted/imported by the user. */
    @Synchronized
    fun registerOpenApi(
        id: String,
        name: String,
        description: String,
        openApiJsonStr: String,
        overrideBaseUrl: String? = null,
        token: String? = null
    ): List<ToolDefinition> {
        require(id.matches(Regex("[a-zA-Z0-9_-]{2,48}"))) {
            "Integration ID must be 2–48 letters, numbers, underscores, or hyphens."
        }
        require(name.isNotBlank()) { "Integration name is required." }
        val rootObj = json.parseToJsonElement(openApiJsonStr).jsonObject
        val (specBaseUrl, parsedEndpoints) = OpenApiSpecParser.parse(id, rootObj, overrideBaseUrl)
        require(parsedEndpoints.isNotEmpty()) { "The OpenAPI document has no supported GET, POST, PUT, DELETE, or PATCH operations." }
        val baseUrl = validateBaseUrl(overrideBaseUrl ?: specBaseUrl)
        require(parsedEndpoints.all { it.path.startsWith("/") }) { "The API document contains an invalid path." }
        val auth = authenticationConfig(rootObj)

        if (!token.isNullOrBlank()) setPluginToken(id, token)
        val entry = PluginEntry(
            id = id,
            name = name.trim(),
            description = description.trim(),
            baseUrl = baseUrl,
            isEnabled = plugins[id]?.isEnabled ?: true,
            requiresAuth = auth.required,
            authType = auth.type,
            authParameterName = auth.parameterName
        )
        val record = PersistedPlugin(
            id = entry.id,
            name = entry.name,
            description = entry.description,
            openApiSpec = openApiJsonStr,
            baseUrl = baseUrl,
            isEnabled = entry.isEnabled,
            requiresAuth = entry.requiresAuth,
            authType = entry.authType,
            authParameterName = entry.authParameterName
        )
        replacePlugin(entry, record, parsedEndpoints)
        persist()
        return parsedEndpoints.map { it.toolDefinition }
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
        token = token
    )

    fun listPlugins(): List<PluginEntry> = plugins.values.sortedBy { it.name.lowercase() }

    @Synchronized
    fun setEnabled(pluginId: String, enabled: Boolean) {
        val current = plugins[pluginId] ?: return
        val updated = current.copy(isEnabled = enabled)
        plugins[pluginId] = updated
        persisted[pluginId]?.let { persisted[pluginId] = it.copy(isEnabled = enabled) }
        endpointsByTool.entries.forEach { (name, mapping) ->
            if (mapping.first.id == pluginId) endpointsByTool[name] = updated to mapping.second
        }
        persist()
    }

    /** Store credentials only in Android Keystore-backed encrypted preferences. */
    @Synchronized
    fun setPluginToken(pluginId: String, token: String) {
        if (token.isBlank()) {
            pluginTokens.remove(pluginId)
            keyStore?.clearMcpServerToken(tokenKey(pluginId))
        } else {
            pluginTokens[pluginId] = token.trim()
            keyStore?.setMcpServerToken(tokenKey(pluginId), token.trim())
        }
        plugins[pluginId]?.let { entry ->
            // Authentication requirement comes from the OpenAPI security declarations;
            // credential presence is presented separately by the UI.
            endpointsByTool.entries.forEach { (name, mapping) ->
                if (mapping.first.id == pluginId) endpointsByTool[name] = entry to mapping.second
            }
            persist()
        }
    }

    fun hasPluginToken(pluginId: String): Boolean =
        keyStore?.getMcpServerToken(tokenKey(pluginId))?.isNotBlank() == true ||
            pluginTokens[pluginId]?.isNotBlank() == true

    @Synchronized
    fun removePlugin(pluginId: String) {
        plugins.remove(pluginId)
        persisted.remove(pluginId)
        endpointsByTool.entries.removeIf { it.value.first.id == pluginId }
        keyStore?.clearMcpServerToken(tokenKey(pluginId))
        pluginTokens.remove(pluginId)
        persist()
    }

    fun allToolDefinitions(): List<ToolDefinition> =
        endpointsByTool.values
            .filter { it.first.isEnabled }
            .map { it.second.toolDefinition }
            .distinctBy { it.function.name }

    fun toolNames(): Set<String> =
        endpointsByTool.filterValues { it.first.isEnabled }.keys

    fun pluginIdForTool(toolName: String): String? =
        endpointsByTool[toolName]?.first?.id

    suspend fun executeTool(toolName: String, arguments: JsonObject): ToolExecutionOutcome? {
        val mapping = endpointsByTool[toolName] ?: return null
        val (plugin, endpoint) = mapping
        if (!plugin.isEnabled) {
            return ToolExecutionOutcome("""{"error":"Integration is disabled"}""", isError = true)
        }

        val token = keyStore?.getMcpServerToken(tokenKey(plugin.id))
            ?.takeIf(String::isNotBlank)
            ?: pluginTokens[plugin.id]?.takeIf(String::isNotBlank)
        if (plugin.requiresAuth && plugin.authType == "unsupported") {
            return ToolExecutionOutcome(
                "This OpenAPI authentication scheme is not supported yet. Use HTTP bearer or API key in a header/query parameter.",
                isError = true
            )
        }
        if (plugin.requiresAuth && token == null) {
            return ToolExecutionOutcome(
                "Authentication is required. Add this integration's credential in Skills & Integrations.",
                isError = true
            )
        }
        val extraHeaders = if (plugin.authType == "api_key_header" && token != null) {
            mapOf(plugin.authParameterName.orEmpty() to token)
        } else emptyMap()
        val extraQueryParams = if (plugin.authType == "api_key_query" && token != null) {
            mapOf(plugin.authParameterName.orEmpty() to token)
        } else emptyMap()
        return executor.execute(
            baseUrl = plugin.baseUrl,
            endpoint = endpoint,
            arguments = arguments,
            bearerToken = token.takeIf { plugin.authType == "bearer" },
            extraHeaders = extraHeaders,
            extraQueryParams = extraQueryParams
        )
    }

    @Synchronized
    private fun replacePlugin(
        entry: PluginEntry,
        record: PersistedPlugin,
        endpoints: List<ParsedPluginEndpoint>
    ) {
        endpointsByTool.entries.removeIf { it.value.first.id == entry.id }
        plugins[entry.id] = entry
        persisted[entry.id] = record
        endpoints.forEach { endpoint -> endpointsByTool[endpoint.toolName] = entry to endpoint }
    }

    private fun restore() {
        val raw = preferences?.getString(KEY_PLUGINS, null) ?: return
        val records = runCatching { json.decodeFromString<List<PersistedPlugin>>(raw) }
            .getOrElse { return }
        records.forEach { record ->
            runCatching {
                val root = json.parseToJsonElement(record.openApiSpec).jsonObject
                val (_, endpoints) = OpenApiSpecParser.parse(record.id, root, record.baseUrl)
                require(endpoints.isNotEmpty())
                val entry = PluginEntry(
                    id = record.id,
                    name = record.name,
                    description = record.description,
                    baseUrl = validateBaseUrl(record.baseUrl),
                    isEnabled = record.isEnabled,
                    requiresAuth = record.requiresAuth,
                    authType = record.authType,
                    authParameterName = record.authParameterName
                )
                replacePlugin(entry, record, endpoints)
            }
        }
    }

    private fun persist() {
        val encoded = runCatching { json.encodeToString(persisted.values.sortedBy { it.id }) }
            .getOrNull() ?: return
        preferences?.edit()?.putString(KEY_PLUGINS, encoded)?.apply()
    }

    private data class AuthConfig(
        val required: Boolean,
        val type: String,
        val parameterName: String? = null
    )

    private fun authenticationConfig(root: JsonObject): AuthConfig {
        val requirements = mutableListOf<JsonObject>()
        fun collect(value: JsonArray?) {
            value?.forEach { item -> (item as? JsonObject)?.let(requirements::add) }
        }
        collect(root["security"] as? JsonArray)
        val paths = root["paths"] as? JsonObject
        paths?.values?.forEach { pathItem ->
            (pathItem as? JsonObject)?.values?.forEach { operation ->
                collect((operation as? JsonObject)?.get("security") as? JsonArray)
            }
        }
        val referencedScheme = requirements.asSequence()
            .flatMap { it.keys.asSequence() }
            .firstOrNull()
            ?: return AuthConfig(required = false, type = "bearer")
        val schemes = root["components"]?.jsonObject?.get("securitySchemes") as? JsonObject
            ?: return AuthConfig(required = true, type = "unsupported")
        val scheme = schemes[referencedScheme] as? JsonObject
            ?: return AuthConfig(required = true, type = "unsupported")
        val type = scheme["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when {
            type.equals("http", ignoreCase = true) &&
                scheme["scheme"]?.jsonPrimitive?.contentOrNull?.equals("bearer", ignoreCase = true) == true ->
                AuthConfig(required = true, type = "bearer")
            type.equals("apiKey", ignoreCase = true) -> {
                val location = scheme["in"]?.jsonPrimitive?.contentOrNull
                val name = scheme["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                when {
                    location == "header" && name != null -> AuthConfig(true, "api_key_header", name)
                    location == "query" && name != null -> AuthConfig(true, "api_key_query", name)
                    else -> AuthConfig(true, "unsupported")
                }
            }
            else -> AuthConfig(required = true, type = "unsupported")
        }
    }

    private fun validateBaseUrl(value: String): String {
        val uri = runCatching { URI(value.trim()) }
            .getOrElse { throw IllegalArgumentException("Enter a valid HTTPS API base URL.") }
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme.equals("https", ignoreCase = true) && host.isNotBlank() && uri.userInfo == null) {
            "Integrations must use an absolute HTTPS URL without embedded credentials."
        }
        require(!isLocalHost(host)) {
            "Local and private-network plugin endpoints are blocked. Use a public HTTPS endpoint."
        }
        return uri.toString().trimEnd('/')
    }

    private fun isLocalHost(host: String): Boolean {
        val normalized = host.removePrefix("[").removeSuffix("]").lowercase().trimEnd('.')
        if (normalized == "localhost" || normalized.endsWith(".localhost") || normalized.endsWith(".local") || normalized.endsWith(".internal")) return true
        if (normalized == "::1" || normalized.startsWith("fc") || normalized.startsWith("fd") ||
            normalized.startsWith("fe80:") || normalized.startsWith("::ffff:")) return true
        val parts = normalized.split('.')
        if (parts.size != 4) return normalized.matches(Regex("[0-9.]+"))
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 0 || octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 169 && octets[1] == 254) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    private fun tokenKey(pluginId: String) = "plugin:$pluginId"

    private companion object {
        const val PREFS_NAME = "edge_openapi_integrations"
        const val KEY_PLUGINS = "installed_plugins_v1"
    }
}
