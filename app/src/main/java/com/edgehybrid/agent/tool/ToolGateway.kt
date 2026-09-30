package com.edgehybrid.agent.tool

import android.util.Log
import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.data.model.ToolDefinition
import com.edgehybrid.agent.jev.JevEvaluator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ToolCatalog(
    val definitions: List<ToolDefinition>,
    val localToolNames: Set<String>,
    val remoteToolNames: Set<String>,
    /** Which enabled server each remote tool name came from. */
    val remoteToolOwners: Map<String, String> = emptyMap()
) {
    /**
     * Narrows [this] to the tools relevant to [prompt], for a smaller model payload.
     *
     * Only [definitions] is narrowed. [localToolNames] and [remoteToolNames] are left
     * complete so dispatch is unaffected: if the model calls a tool that was pruned out of
     * the prompt, it still executes rather than failing as "unknown". That asymmetry is
     * deliberate — pruning is a latency optimization, and a wrong prune must never become a
     * broken tool call.
     *
     * @return this catalog unchanged when pruning is unavailable or not worthwhile.
     */
    fun prunedFor(selection: List<String>, maxTools: Int): ToolCatalog {
        if (selection.isEmpty()) return this
        if (definitions.size <= maxTools) return this

        val keep = selection.toSet()
        val narrowed = definitions.filter { it.function.name in keep }
        if (narrowed.isEmpty()) return this

        return copy(definitions = narrowed)
    }

}

/**
 * Single dispatch point between the agent loop and everything it can call.
 *
 * Local skills come from [SkillLoader]. Remote tools come from whichever MCP servers are
 * **currently enabled** in [McpMultiServerRouter] — the same registry the Settings UI
 * writes to — so toggling a server in Settings changes what the agent can call without a
 * restart. The single-endpoint [mcpClient] remains as a fallback for a build configured
 * with `MCP_ENDPOINT` at compile time, which is how development builds attach one server.
 */
@Singleton
class ToolGateway @Inject constructor(
    private val skillLoader: SkillLoader,
    private val mcpRouter: McpMultiServerRouter,
    private val mcpClient: McpClient,
    private val jevClient: JevEvaluator
) {
    /**
     * Narrows the tool payload for [prompt] using Jev's `choice` primitive.
     *
     * Returns the catalog unchanged when Jev is not configured, the call fails, or the
     * catalog is already small. Pruning is strictly an optimization: a failed or unsure
     * selection sends the full set rather than a guess.
     *
     * [ToolCatalog.prunedFor] keeps dispatch intact, so a tool that was pruned out of the
     * prompt still executes if the model calls it anyway.
     */
    suspend fun pruneCatalogFor(prompt: String, catalog: ToolCatalog): ToolCatalog {
        if (!jevClient.isConfigured()) return catalog
        if (catalog.definitions.size <= MIN_TOOLS_BEFORE_PRUNING) return catalog

        val candidates = catalog.definitions.associate { definition ->
            definition.function.name to definition.function.description.take(MAX_DESCRIPTION_CHARS)
        }

        val selection = jevClient.selectTools(
            prompt = prompt,
            candidates = candidates,
            limit = MAX_TOOLS_AFTER_PRUNING
        )

        return catalog.prunedFor(selection, MAX_TOOLS_AFTER_PRUNING)
    }

    private companion object {
        /** Below this size the schema payload is not worth a network round trip. */
        const val MIN_TOOLS_BEFORE_PRUNING = 12

        /** Enough to cover the common single-intent case without bloating the prompt. */
        const val MAX_TOOLS_AFTER_PRUNING = 8

        /** Option rubrics stay terse; these are hints, not documentation. */
        const val MAX_DESCRIPTION_CHARS = 120
    }
    suspend fun loadCatalog(): ToolCatalog {
        val localDefinitions = skillLoader.listTools()

        // Ask the router which enabled servers advertise what, so execution can be routed
        // to the same server that advertised the tool rather than re-probing on every call.
        val remoteByServer: Map<String, List<ToolDefinition>> = try {
            mcpRouter.discoverToolsByServer()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Log.w(
                "ToolGateway",
                "MCP discovery failed; continuing with local skills",
                exception
            )
            emptyMap()
        }

        // A build-time endpoint is used only when the user has enabled no servers, so the
        // registry stays the source of truth in normal use.
        val remoteDefinitions: List<ToolDefinition> =
            if (remoteByServer.isEmpty()) {
                try {
                    mcpClient.listTools()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Log.w("ToolGateway", "Build-time MCP endpoint unavailable", exception)
                    emptyList()
                }
            } else {
                remoteByServer.values.flatten()
            }

        val owners = buildMap {
            remoteByServer.forEach { (serverId, tools) ->
                tools.forEach { putIfAbsent(it.function.name, serverId) }
            }
        }

        val seenNames = mutableSetOf<String>()
        val definitions = buildList {
            // Local tools win a name collision: they are first-party and always available.
            addAll(localDefinitions)
            addAll(remoteDefinitions)
        }.filter { definition ->
            definition.function.name.isNotBlank() &&
                seenNames.add(definition.function.name)
        }

        val localNames = localDefinitions.mapTo(mutableSetOf()) { it.function.name }

        return ToolCatalog(
            definitions = definitions,
            localToolNames = localNames,
            remoteToolNames = remoteDefinitions.mapTo(mutableSetOf()) { it.function.name }
                .minus(localNames),
            remoteToolOwners = owners
        )
    }

    suspend fun execute(
        call: ModelToolCall,
        catalog: ToolCatalog
    ): ToolExecutionOutcome =
        try {
            when (call.function.name) {
                in catalog.localToolNames ->
                    skillLoader.execute(call)

                in catalog.remoteToolNames -> {
                    // Prefer the server that advertised this tool. The router re-checks
                    // that the server is still enabled before dispatching.
                    val owner = catalog.remoteToolOwners[call.function.name]
                    val routed = mcpRouter.callOn(owner, call)
                    val result = routed ?: mcpClient.callTool(call)
                    ToolExecutionOutcome(
                        content = result.content,
                        isError = result.isError
                    )
                }

                else -> ToolExecutionOutcome(
                    content = errorContent("Unknown tool: ${call.function.name}"),
                    isError = true
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            ToolExecutionOutcome(
                content = errorContent(
                    exception.message ?: "Tool execution failed"
                ),
                isError = true
            )
        }

    private fun errorContent(message: String): String =
        buildJsonObject {
            put("error", message)
        }.toString()
}