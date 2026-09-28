package com.edgehybrid.agent.tool

import android.util.Log
import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.data.model.ToolDefinition
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ToolCatalog(
    val definitions: List<ToolDefinition>,
    val localToolNames: Set<String>,
    val remoteToolNames: Set<String>
)

@Singleton
class ToolGateway @Inject constructor(
    private val skillLoader: SkillLoader,
    private val mcpClient: McpClient
) {
    suspend fun loadCatalog(): ToolCatalog {
        val localDefinitions = skillLoader.listTools()
        val remoteDefinitions = try {
            mcpClient.listTools()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Log.w(
                "ToolGateway",
                "MCP tool discovery failed; continuing with local skills",
                exception
            )
            emptyList()
        }

        val seenNames = mutableSetOf<String>()
        val definitions = buildList {
            addAll(localDefinitions)
            addAll(remoteDefinitions)
        }.filter { definition ->
            definition.function.name.isNotBlank() &&
                seenNames.add(definition.function.name)
        }

        return ToolCatalog(
            definitions = definitions,
            localToolNames = localDefinitions.mapTo(mutableSetOf()) {
                it.function.name
            },
            remoteToolNames = remoteDefinitions.mapTo(mutableSetOf()) {
                it.function.name
            }
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

                in catalog.remoteToolNames ->
                    mcpClient.callTool(call).let { result ->
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