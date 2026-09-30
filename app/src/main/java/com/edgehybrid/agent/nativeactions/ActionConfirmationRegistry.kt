package com.edgehybrid.agent.nativeactions

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds pending user-approved actions and performs them exactly once.
 *
 * This class is the *only* component with the authority to execute a confirmed side
 * effect. A confirmation is:
 *  - **server-side**: the [ActionConfirmation] handed to the UI carries no parameters and
 *    cannot be used to authorize anything; the real arguments stay here, keyed by id.
 *  - **one-shot**: [consume] atomically removes the entry, so a replayed or duplicated
 *    id returns null and cannot re-execute.
 *  - **expiring**: entries older than [ttlMillis] are rejected, so a confirmation left
 *    open by a backgrounded app cannot be approved much later.
 */
@Singleton
class ActionConfirmationRegistry @Inject constructor() {

    private class PendingAction(
        val confirmation: ActionConfirmation,
        val run: suspend () -> String,
        val createdAtMillis: Long
    )

    private val pending = ConcurrentHashMap<String, PendingAction>()

    /**
     * Registers a pending action and returns the display record to show the user.
     * The returned object is inert; authority stays here until [consume].
     */
    fun register(
        tool: String,
        title: String,
        summary: String,
        tier: ActionRiskTier,
        details: List<ConfirmationDetail>,
        warning: String? = null,
        run: suspend () -> String
    ): ActionConfirmation {
        val id = java.util.UUID.randomUUID().toString()
        val confirmation = ActionConfirmation(
            id = id,
            tool = tool,
            title = title,
            summary = summary,
            tier = tier,
            details = details,
            warning = warning
        )
        pending[id] = PendingAction(
            confirmation = confirmation,
            run = run,
            createdAtMillis = System.currentTimeMillis()
        )
        return confirmation
    }

    /**
     * Atomically removes and returns the runnable action for [id], or null if the id is
     * unknown, already consumed, or expired. The removal happens before execution, so a
     * concurrent or repeated approval cannot fire the side effect twice.
     */
    fun consume(id: String): (suspend () -> String)? {
        val entry = pending.remove(id) ?: return null
        if (System.currentTimeMillis() - entry.createdAtMillis > ttlMillis) {
            return null
        }
        return entry.run
    }

    /** Looks up a still-pending confirmation for display without consuming it. */
    fun peek(id: String): ActionConfirmation? {
        val entry = pending[id] ?: return null
        if (System.currentTimeMillis() - entry.createdAtMillis > ttlMillis) {
            pending.remove(id, entry)
            return null
        }
        return entry.confirmation
    }

    /** Drops a pending action without executing it (user pressed Cancel). */
    fun cancel(id: String) {
        pending.remove(id)
    }

    /** Number of outstanding confirmations; used by tests and diagnostics. */
    val pendingCount: Int get() = pending.size

    companion object {
        /** Confirmations are only valid for two minutes after they are raised. */
        const val ttlMillis: Long = 120_000L
    }
}
