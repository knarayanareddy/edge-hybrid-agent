package com.edgehybrid.agent.agent

import kotlinx.coroutines.delay

data class AgentPolicy(
    val maxIterations: Int = 6,
    val providerMaxRetries: Int = 3,
    val initialBackoffMs: Long = 1_000L,
    val backoffFactor: Double = 2.0,
    val maxBackoffMs: Long = 30_000L,
    val maxStreamRecoveries: Int = 2
) {
    init {
        require(maxIterations > 0) { "maxIterations must be positive" }
        require(providerMaxRetries >= 0) { "providerMaxRetries cannot be negative" }
        require(initialBackoffMs > 0) { "initialBackoffMs must be positive" }
        require(backoffFactor >= 1.0) { "backoffFactor must be at least 1.0" }
        require(maxBackoffMs >= initialBackoffMs) {
            "maxBackoffMs must be greater than or equal to initialBackoffMs"
        }
        require(maxStreamRecoveries >= 0) { "maxStreamRecoveries cannot be negative" }
    }

    fun backoffFor(retryIndex: Int): Long {
        var delayMs = initialBackoffMs.toDouble()
        repeat(retryIndex.coerceAtLeast(0)) {
            delayMs *= backoffFactor
        }
        return delayMs.toLong().coerceAtMost(maxBackoffMs)
    }
}

fun interface MonotonicClock {
    fun nowNanos(): Long
}

fun interface SuspendDelay {
    suspend fun wait(delayMs: Long)
}

object KotlinSuspendDelay : SuspendDelay {
    override suspend fun wait(delayMs: Long) {
        delay(delayMs)
    }
}