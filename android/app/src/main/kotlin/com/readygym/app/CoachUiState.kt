package com.readygym.app

/**
 * The coach loop as one enumerated state.
 *
 * This is the only thing the UI is allowed to animate from: the chat screen, the cards and the
 * composer all read the same `StateFlow<CoachUiState>` and never infer "what should be playing"
 * from `busy`/`error`/`reply`. Adding a new animation means adding a state here first.
 *
 * [wire] matches the review document's snake_case vocabulary, so a server that starts emitting
 * `coach_state` is understood without a second state source.
 */
enum class CoachUiState(val wire: String) {
    /** Nothing pending; the coach asked something and the user can type. */
    IDLE("idle"),
    /** Sent; the model is composing its answer. */
    THINKING("thinking"),
    /** Deterministic business work in flight (confirm, plan read-back, the agent's tool loop). */
    TOOL_RUNNING("tool_running"),
    /** A summary card or plan draft is on screen but confirmation is not available yet. */
    PROPOSING("proposing"),
    /** The same card, with its confirm action available: the one looping "you can confirm" cue. */
    AWAITING_CONFIRM("awaiting_confirm"),
    /** Confirmed and read back from the server. */
    SUCCESS("success"),
    /** Provider/network failure, or input the coach refused. */
    FAIL("fail"),
    /** Deterministic template draft after a model failure; still a draft, still needs confirmation. */
    RULE_FALLBACK("rule_fallback"),
    /** A retained body-risk route. Overrides every other state until the activity ends. */
    RISK_BLOCKED("risk_blocked");

    /** True while work is in flight; the composer uses this to disable sending, not to animate. */
    val isWorking: Boolean get() = this == THINKING || this == TOOL_RUNNING

    companion object {
        fun fromWire(value: String?): CoachUiState? = entries.firstOrNull { it.wire == value }
    }
}

/** What the conversation is currently waiting on; only the reducer reads this. */
internal enum class CoachPending { NONE, MODEL, BUSINESS }

/**
 * The single reducer: a snapshot of the conversation folded into one state.
 *
 * Pure, so the transitions are unit-testable without Compose and there is exactly one place where
 * priority is decided:
 *
 *     risk > work-in-flight > failure > blocked > draft > confirmed > idle
 *
 * `riskRetained` carries the money rule: once a body-risk route is latched for the activity,
 * nothing the model later says can move the UI out of [CoachUiState.RISK_BLOCKED].
 */
internal object CoachStateReducer {

    fun reduce(
        riskRetained: Boolean,
        pending: CoachPending = CoachPending.NONE,
        error: CoachException? = null,
        reply: CoachReply? = null,
        /** A card is on screen and its confirm action is actually tappable right now. */
        canConfirm: Boolean = false,
        planConfirmationUnknown: Boolean = false,
        /** A state the server named explicitly; honoured only when it means "stuck for good". */
        serverState: CoachUiState? = null,
    ): CoachUiState {
        // Risk outranks everything, including a server that claims otherwise.
        if (riskRetained) return CoachUiState.RISK_BLOCKED
        if (serverState == CoachUiState.RISK_BLOCKED) return CoachUiState.RISK_BLOCKED
        if (pending == CoachPending.MODEL) return CoachUiState.THINKING
        if (pending == CoachPending.BUSINESS) return CoachUiState.TOOL_RUNNING
        if (error != null && error.isRetryableFailure()) return CoachUiState.FAIL
        // A confirmation whose outcome is unknown must not be re-shown as a success: the plan may or
        // may not have been written, and the next allowed action is "read it back", not "confirm".
        if (planConfirmationUnknown) return CoachUiState.AWAITING_CONFIRM
        when {
            reply?.status == "blocked" -> return when (reply.safetyRoute) {
                // Not a body risk: the template set just does not cover this experience.
                CoachSafetyRoute.UNSUPPORTED_EXPERIENCE -> CoachUiState.FAIL
                else -> CoachUiState.RISK_BLOCKED
            }
            reply?.planProposal != null -> {
                if (reply.planProposal.source == "rule_fallback") return CoachUiState.RULE_FALLBACK
                return if (canConfirm) CoachUiState.AWAITING_CONFIRM else CoachUiState.PROPOSING
            }
            // A profile summary is confirmed by the same button pattern; once accepted it is a success.
            reply?.confirmedCandidate != null && !reply.confirmed ->
                return if (canConfirm) CoachUiState.AWAITING_CONFIRM else CoachUiState.PROPOSING
            reply != null && reply.confirmed -> return CoachUiState.SUCCESS
            // A non-retryable error (a blank/oversized input, a local refusal) already returned
            // above as "not a failure": it must not put the screen into the failure state.
        }
        return CoachUiState.IDLE
    }
}

/**
 * A failure worth a shake. [CoachException] carries both real failures and local policy refusals
 * (unsupported input, a blocked session); only the former should read as "something broke".
 */
internal fun CoachException.isRetryableFailure(): Boolean = when (code) {
    "invalid_input", "invalid_session", "local_safety_block", "confirmation_not_supported",
    "foundation_not_supported", "deletion_not_supported" -> false
    else -> true
}
