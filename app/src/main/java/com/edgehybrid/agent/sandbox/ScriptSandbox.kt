package com.edgehybrid.agent.sandbox

/**
 * Contract for executing isolated JavaScript/TypeScript starter skills in a secure sandbox.
 */
interface ScriptSandbox {
    suspend fun executeScript(
        scriptName: String,
        inputJson: String,
        networkOrigins: List<String> = emptyList()
    ): Result<String>
}
