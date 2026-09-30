package com.edgehybrid.agent.ui.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edgehybrid.agent.mcp.CallbackResult
import com.edgehybrid.agent.mcp.McpOAuthManager
import com.edgehybrid.agent.mcp.McpServerCallbackStore
import com.edgehybrid.agent.mcp.McpServerEntry
import com.edgehybrid.agent.mcp.McpServerRegistry
import com.edgehybrid.agent.mcp.McpProbeRunner
import com.edgehybrid.agent.mcp.McpAuthCatalog
import com.edgehybrid.agent.mcp.McpAuthMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.content.Context as AndroidContext

/** Per-server row state, including the last connection-test outcome. */
data class McpServerUi(
    val entry: McpServerEntry,
    val hasToken: Boolean,
    val authMode: McpAuthMode,
    val authExplanation: String?,
    val isOAuthConnected: Boolean,
    val tokenExpired: Boolean,
    val probeState: ProbeState
)

sealed class ProbeState {
    data object Idle : ProbeState()
    data object Running : ProbeState()
    data class Ok(val serverName: String, val toolCount: Int) : ProbeState()
    data class NeedsAuth(val detail: String) : ProbeState()
    data class Failed(val detail: String) : ProbeState()
}

data class McpUiState(
    val servers: List<McpServerUi> = emptyList(),
    val enabledCount: Int = 0,
    val message: String? = null,
    val loading: Boolean = false
)

/**
 * Backs the MCP management screen.
 *
 * Every state change here is user-driven: nothing is enabled, dialled, or refreshed on
 * startup. Enabling a server and testing its connection are separate, explicit actions.
 */
@HiltViewModel
class McpServersViewModel @Inject constructor(
    private val registry: McpServerRegistry,
    private val oauthManager: McpOAuthManager,
    private val probeRunner: McpProbeRunner,
    @dagger.hilt.android.qualifiers.ApplicationContext private val applicationContext: AndroidContext
) : ViewModel() {

    private val _uiState = MutableStateFlow(McpUiState())
    val uiState: StateFlow<McpUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val catalog = registry.catalog()
        _uiState.update { state ->
            state.copy(
                servers = catalog.servers.map { entry ->
                    McpServerUi(
                        entry = entry,
                        hasToken = registry.hasToken(entry.id),
                        authMode = McpAuthCatalog.forId(entry.id)?.mode
                            ?: McpAuthMode.NONE,
                        authExplanation = McpAuthCatalog.forId(entry.id)?.explanation,
                        isOAuthConnected = oauthManager.isConnected(entry.id),
                        tokenExpired = oauthManager.isExpired(entry.id),
                        // Preserve an in-flight or completed probe so a list refresh does
                        // not wipe the result the user is reading.
                        probeState = state.servers
                            .firstOrNull { it.entry.id == entry.id }
                            ?.probeState ?: ProbeState.Idle
                    )
                },
                enabledCount = catalog.enabledCount
            )
        }
    }

    fun setEnabled(serverId: String, enabled: Boolean) {
        val changed = if (enabled) registry.enable(serverId) else registry.disable(serverId)
        _uiState.update {
            it.copy(
                message = if (changed) {
                    null
                } else {
                    "Unknown server '$serverId'"
                }
            )
        }
        refresh()
    }

    /** Stores a personal access token for a server that does not use OAuth. */
    fun saveToken(serverId: String, token: String) {
        val ok = registry.setToken(serverId, token)
        _uiState.update {
            it.copy(message = if (ok) "Token saved" else "Could not save the token")
        }
        refresh()
    }

    fun clearToken(serverId: String) {
        registry.clearToken(serverId)
        oauthManager.disconnect(serverId)
        refresh()
    }

    /**
     * Opens the vendor's consent page in the browser.
     *
     * The pending server id is parked in memory so the redirect activity, which runs in a
     * separate task, knows which server the response belongs to.
     */
    /**
     * Reports whether interactive sign-in is possible for [serverId].
     *
     * It is not, today: every curated OAuth server requires dynamic client registration,
     * which this app does not perform. Returning the explanation keeps the UI honest
     * instead of opening a browser that would fail.
     */
    fun connectWithOAuth(serverId: String, context: Context? = null) {
        val auth = McpAuthCatalog.forId(serverId)
        _uiState.update {
            it.copy(
                message = auth?.explanation
                    ?: "This server does not use interactive sign-in."
            )
        }
    }

    /**
     * Switches a server to its vendor-provided read-only endpoint.
     *
     * Linear is the one curated vendor that publishes a narrower endpoint, so this is a
     * real permission reduction rather than a cosmetic toggle.
     */
    fun useReadOnlyEndpoint(serverId: String) {
        val auth = McpAuthCatalog.forId(serverId) ?: return
        val readOnly = auth.readOnlyUrl ?: return
        val existing = registry.find(serverId) ?: return

        registry.upsert(existing.copy(url = readOnly))
        _uiState.update {
            it.copy(message = "${existing.name} switched to its read-only endpoint")
        }
        refresh()
    }

    /** Called when the screen resumes, to pick up any redirect result. */
    fun consumeCallbackResult() {
        when (val result = McpServerCallbackStore.lastResult) {
            is CallbackResult.Success -> {
                McpServerCallbackStore.lastResult = null
                McpServerCallbackStore.pendingServerId = null
                _uiState.update {
                    it.copy(message = "Connected to ${result.serverId}")
                }
                // Enabling is still the user's decision, but a completed connection with no
                // server enabled would be surprising, so surface it rather than doing it.
                refresh()
            }

            is CallbackResult.Error -> {
                McpServerCallbackStore.lastResult = null
                McpServerCallbackStore.pendingServerId = null
                _uiState.update { it.copy(message = result.message) }
            }

            null -> Unit
        }
    }

    /** Runs a real initialize + tools/list against the server. */
    fun testConnection(serverId: String) {
        val entry = registry.find(serverId) ?: return

        _uiState.update { state ->
            state.copy(
                servers = state.servers.map {
                    if (it.entry.id == serverId) it.copy(probeState = ProbeState.Running) else it
                }
            )
        }

        viewModelScope.launch {
            val token = oauthManager.accessTokenFor(serverId)
                ?: registry.tokenFor(serverId)

            when (val result = probeRunner.probe(entry.url, token)) {
                is McpProbeRunner.Result.Ok -> _uiState.update { state ->
                    state.copy(
                        servers = state.servers.map {
                            if (it.entry.id == serverId) {
                                it.copy(
                                    probeState = ProbeState.Ok(result.serverName, result.toolCount)
                                )
                            } else {
                                it
                            }
                        }
                    )
                }

                is McpProbeRunner.Result.NeedsAuth -> _uiState.update { state ->
                    state.copy(
                        servers = state.servers.map {
                            if (it.entry.id == serverId) {
                                it.copy(probeState = ProbeState.NeedsAuth(result.detail))
                            } else {
                                it
                            }
                        }
                    )
                }

                is McpProbeRunner.Result.Failed -> _uiState.update { state ->
                    state.copy(
                        servers = state.servers.map {
                            if (it.entry.id == serverId) {
                                it.copy(probeState = ProbeState.Failed(result.detail))
                            } else {
                                it
                            }
                        }
                    )
                }
            }
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    // ------------------------------------------------------------------ custom servers

    /** Adds a user-defined server. Disabled on creation; enabling stays explicit. */
    fun addCustomServer(id: String, name: String, url: String) {
        val ok = registry.upsert(
            com.edgehybrid.agent.mcp.McpServerEntry(
                id = id.trim().lowercase(),
                name = name.trim().ifBlank { id },
                url = url.trim(),
                enabled = false,
                category = "custom",
                description = "User-added server"
            )
        )
        _uiState.update {
            it.copy(
                message = when {
                    ok -> "$name added — enable it when you're ready"
                    !url.startsWith("https://") -> "Only https:// endpoints are allowed"
                    else -> "Could not add the server"
                }
            )
        }
        refresh()
    }

    fun removeCustomServer(id: String) {
        val removed = registry.remove(id)
        _uiState.update {
            it.copy(message = if (removed) "Server removed" else "Curated servers cannot be removed")
        }
        refresh()
    }

    // ------------------------------------------------------------------ token dialog

    private val _tokenDraft = MutableStateFlow("")
    val tokenDraft: StateFlow<String> = _tokenDraft.asStateFlow()

    fun updateTokenDraft(value: String) {
        _tokenDraft.value = value
    }

    fun dismissTokenDialog() {
        // Clearing on dismiss means a token typed but not saved is not retained.
        _tokenDraft.value = ""
    }
}