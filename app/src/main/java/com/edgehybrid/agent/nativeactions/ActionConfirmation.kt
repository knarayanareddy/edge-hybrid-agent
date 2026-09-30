package com.edgehybrid.agent.nativeactions

/**
 * Risk tier assigned to a proposed agent action before it is allowed to run.
 *
 * The tier is decided by the host (see `com.edgehybrid.agent.agent.ConfirmationGate`),
 * never by the model. Model output can only ever *raise* scrutiny, never lower it.
 */
enum class ActionRiskTier {
    /** Read-only: no device state change, no outbound message. Runs without a dialog. */
    AUTO_APPROVE,

    /** Local side effect (alarm, timer, note, camera, flashlight). Requires a dialog. */
    CONFIRM,

    /**
     * External send or credential handling (SMS, Telegram, email, phone call, MCP
     * registration). Requires a dialog that renders the full recipient and payload.
     */
    CONFIRM_STRICT
}

/**
 * Opaque, user-facing description of a pending action.
 *
 * This is a *display* record only. It deliberately carries no executable parameters
 * and no authority: [com.edgehybrid.agent.nativeactions.ActionConfirmationRegistry]
 * holds the real arguments and is the only path that can perform the side effect, so a
 * `ActionConfirmation` cannot be forged to authorize anything.
 */
data class ActionConfirmation(
    val id: String,
    val tool: String,
    val title: String,
    val summary: String,
    val tier: ActionRiskTier = ActionRiskTier.CONFIRM,
    /** Ordered label/value pairs rendered in the dialog body. */
    val details: List<ConfirmationDetail> = emptyList(),
    /** Non-null only for [ActionRiskTier.CONFIRM_STRICT]; shown as a warning banner. */
    val warning: String? = null
) {
    val isStrict: Boolean get() = tier == ActionRiskTier.CONFIRM_STRICT
}

data class ConfirmationDetail(
    val label: String,
    val value: String
)
