package com.edgehybrid.agent.agent

import com.edgehybrid.agent.nativeactions.ActionConfirmation
import com.edgehybrid.agent.nativeactions.ActionConfirmationRegistry
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bridges the agent's streaming loop to the UI: the loop suspends on a pending
 * confirmation, and the user answers from a dialog.
 *
 * Fail-closed contract: if the user never answers, the await times out and resolves to
 * **denied**. There is no path in which a missing answer becomes approval.
 *
 * This class only signals intent. The executable action stays in
 * [ActionConfirmationRegistry] until [ConfirmationGate.runApproved] consumes it, so
 * approving here is necessary but not sufficient — the gate is the only caller that can
 * actually fire the side effect, and it will only do so for a live registry entry.
 */
@Singleton
class ConfirmationCoordinator @Inject constructor(
    private val registry: ActionConfirmationRegistry
) {

    private class Pending(
        val confirmationId: String,
        val deferred: CompletableDeferred<Boolean>
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    /**
     * Suspends until the user answers for [callId].
     *
     * @return true if approved, false if declined or timed out.
     */
    suspend fun requestApproval(callId: String, confirmation: ActionConfirmation): Boolean {
        val entry = Pending(
            confirmationId = confirmation.id,
            deferred = CompletableDeferred()
        )
        pending[callId] = entry
        try {
            val answer = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { entry.deferred.await() }
            if (answer != true) {
                // Declined or timed out: drop the executable entry so it can never run.
                registry.cancel(entry.confirmationId)
            }
            return answer == true
        } finally {
            pending.remove(callId)
        }
    }

    /** Called from the UI when the user taps "Confirm & Execute". */
    fun approve(callId: String): Boolean =
        pending[callId]?.deferred?.complete(true) ?: false

    /** Called from the UI when the user dismisses or declines. */
    fun decline(callId: String): Boolean {
        val entry = pending[callId] ?: return false
        registry.cancel(entry.confirmationId)
        return entry.deferred.complete(false)
    }

    /** True if a dialog is currently awaiting an answer for this call. */
    fun isAwaiting(callId: String): Boolean = pending.containsKey(callId)

    /** Cancels every outstanding request; used when generation is aborted. */
    fun cancelAll() {
        pending.entries.forEach { (callId, entry) ->
            registry.cancel(entry.confirmationId)
            entry.deferred.complete(false)
            pending.remove(callId)
        }
    }

    companion object {
        /** A confirmation the user ignores for two minutes is treated as declined. */
        const val REQUEST_TIMEOUT_MS: Long = 120_000L
    }
}
