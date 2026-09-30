package com.edgehybrid.agent.mcp

import android.content.Context
import com.edgehybrid.agent.data.local.SecureKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Persisted catalogue of MCP servers the agent may talk to.
 *
 * Design notes:
 *  - A server must be explicitly enabled. Nothing is dialled otherwise, so shipping
 *    curated defaults cannot by itself cause outbound traffic.
 *  - Bearer tokens live in [SecureKeyStore], never in this metadata record, and are never
 *    included in catalog output.
 *  - Only `https://` endpoints are accepted: an MCP server receives tool calls and often
 *    credentials, so cleartext would expose both.
 */
/**
 * Read/write seam over the server catalogue.
 *
 * The router depends on this rather than the concrete registry, so dispatch can be tested
 * against a fake catalogue without a Context or the Android keystore.
 */
interface McpServerSource {
    fun enabledServers(): List<McpServerEntry>
    fun tokenFor(serverId: String): String?
}

@Singleton
class McpServerRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore
) : McpServerSource {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ catalog

    /** Curated defaults plus any user-added servers, with no credential material. */
    fun catalog(): McpServerCatalog {
        val userServers = readUserServers()
        // Only *user-defined* ids suppress a curated entry. Including the curated ids here
        // would filter every curated server out of its own catalogue.
        val userIds = userServers.map { it.id }.toSet()

        val merged = userServers +
            CuratedMcpServers.ALL.filterNot { it.id in userIds }

        val withFlags = merged.map { applyEnabledFlag(it) }

        return McpServerCatalog(
            servers = withFlags,
            enabledCount = withFlags.count { it.enabled }
        )
    }

    override fun enabledServers(): List<McpServerEntry> = catalog().servers.filter { it.enabled }

    fun find(id: String): McpServerEntry? = catalog().servers.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ enable/disable

    /**
     * Enables a known server.
     *
     * @return false for an unknown id, so a model cannot invent a server by naming it.
     */
    fun enable(id: String): Boolean = setEnabled(id, true)

    fun disable(id: String): Boolean = setEnabled(id, false)

    private fun setEnabled(id: String, enabled: Boolean): Boolean {
        if (find(id) == null) return false

        // Both sets must be updated: enabling has to *record* the id, not merely remove it
        // from the disabled set (which would be a no-op when nothing was disabled).
        // An explicit enable outranks an explicit disable, so the two are kept disjoint.
        val disabled = disabledIds().toMutableSet()
        val enabledSet = enabledIds().toMutableSet()

        if (enabled) {
            disabled.remove(id)
            enabledSet.add(id)
        } else {
            enabledSet.remove(id)
            disabled.add(id)
        }

        prefs.edit()
            .putString(KEY_DISABLED, disabled.joinToString(","))
            .putString(KEY_ENABLED, enabledSet.joinToString(","))
            .apply()
        return true
    }

    private fun enabledIds(): Set<String> =
        prefs.getString(KEY_ENABLED, "")?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    private fun disabledIds(): Set<String> =
        prefs.getString(KEY_DISABLED, "")?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    /** Explicit enable wins; an explicit disable always wins over a curated default. */
    private fun applyEnabledFlag(entry: McpServerEntry): McpServerEntry = when {
        entry.id in disabledIds() -> entry.copy(enabled = false)
        entry.id in enabledIds() -> entry.copy(enabled = true)
        else -> entry.copy(enabled = false)
    }

    // ------------------------------------------------------------------ credentials

    /**
     * Stores a bearer token for [serverId] in the encrypted keystore.
     *
     * @return false when the server is unknown, so tokens cannot be filed under an
     *   invented id.
     */
    fun setToken(serverId: String, token: String): Boolean {
        if (find(serverId) == null) return false
        keyStore.setMcpServerToken(McpServerKeys.forId(serverId), token)
        return true
    }

    fun hasToken(serverId: String): Boolean =
        keyStore.getMcpServerToken(McpServerKeys.forId(serverId)).isNotBlank()

    fun clearToken(serverId: String) {
        keyStore.clearMcpServerToken(McpServerKeys.forId(serverId))
    }

    /** Bearer token for [serverId], or null. */
    override fun tokenFor(serverId: String): String? =
        keyStore.getMcpServerToken(McpServerKeys.forId(serverId)).takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ user servers

    /**
     * Adds or replaces a user-defined server.
     *
     * @return false when the URL is not https, or the id collides with a curated server.
     */
    fun upsert(entry: McpServerEntry, token: String? = null): Boolean {
        if (!entry.url.startsWith("https://")) return false
        if (entry.id.isBlank() || ID_PATTERN.matches(entry.id).not()) return false
        if (entry.id in CuratedMcpServers.IDS) return false

        val current = readUserServers().filterNot { it.id == entry.id }.toMutableList()
        current.add(entry.copy(enabled = false))
        writeUserServers(current)

        if (!token.isNullOrBlank()) {
            keyStore.setMcpServerToken(McpServerKeys.forId(entry.id), token)
        }
        return true
    }

    fun remove(id: String): Boolean {
        if (id in CuratedMcpServers.IDS) return false
        val current = readUserServers()
        if (current.none { it.id == id }) return false
        writeUserServers(current.filterNot { it.id == id })
        keyStore.clearMcpServerToken(McpServerKeys.forId(id))
        return true
    }

    private fun readUserServers(): List<McpServerEntry> = runCatching {
        val array = json.parseToJsonElement(
            prefs.getString(KEY_LOCAL_SERVERS, "[]") ?: "[]"
        ) as? JsonArray
        array?.mapNotNull { runCatching { it.jsonObject.toServer() }.getOrNull() }
            ?: emptyList()
    }.getOrDefault(emptyList())

    private fun writeUserServers(entries: List<McpServerEntry>) {
        val array = JsonArray(entries.map { it.toJson() })
        prefs.edit().putString(KEY_LOCAL_SERVERS, array.toString()).apply()
    }

    private fun McpServerEntry.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("url", url)
        put("category", category)
        put("description", description)
        put("requires_api_key", requiresApiKey)
    }

    private fun JsonObject.toServer(): McpServerEntry = McpServerEntry(
        id = this["id"]?.jsonPrimitive?.content.orEmpty(),
        name = this["name"]?.jsonPrimitive?.content.orEmpty(),
        url = this["url"]?.jsonPrimitive?.content.orEmpty(),
        enabled = false,
        category = this["category"]?.jsonPrimitive?.content ?: "custom",
        description = this["description"]?.jsonPrimitive?.content.orEmpty(),
        requiresApiKey = this["requires_api_key"]?.jsonPrimitive?.content?.toBoolean() ?: false
    )

    private companion object {
        const val PREFS = "edge_mcp_servers"
        const val KEY_LOCAL_SERVERS = "local_servers"
        const val KEY_ENABLED = "enabled_ids"
        const val KEY_DISABLED = "disabled_ids"
        val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,40}$")
    }
}