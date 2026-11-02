package com.readygym.app

import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import com.readygym.core.PlanningPreferences
import com.readygym.core.SafetyFeedback
import com.readygym.core.TrainingExperience
import com.readygym.core.TrainingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reducer is the only place the UI's motion state is decided, so its priority order is the
 * part of the change most worth pinning down: risk beats everything, work beats failure, and a
 * proposal only "awaits confirmation" when its confirm action is genuinely tappable.
 */
class CoachUiStateReducerTest {

    private fun candidate(days: Int = 3) = CoachCandidate(
        TrainingProfile(age = 30, heightCm = 175.0, weightKg = 70.0, gender = Gender.MALE,
            goal = Goal.MUSCLE, location = Location.GYM, equipment = setOf(Equipment.DUMBBELL)),
        PlanningPreferences(days, 45, TrainingExperience.BEGINNER, SafetyFeedback(screenCompleted = true)),
    )

    private fun plan(source: String = "agent_rule_tool", status: String = "draft", revision: Int = 1) = FoundationPlan(
        id = "p1", revision = revision, status = status, basedOnRevision = null, title = "一周基础抗阻计划",
        daysPerWeek = 3, minutesPerSession = 30, trainingDays = listOf(1, 3, 5), unavailableDays = listOf(),
        equipment = listOf("bodyweight"), source = source,
        week = List(7) { i -> FoundationDay(i + 1, false, emptyList()) },
        changes = emptyList(), notices = emptyList(),
    )

    private fun reply(
        status: String = "collecting",
        confirmed: Boolean = false,
        confirmedCandidate: CoachCandidate? = null,
        planProposal: FoundationPlan? = null,
        activePlan: FoundationPlan? = null,
        safetyRoute: CoachSafetyRoute? = null,
    ) = CoachReply(CoachSession("s", "t"), "好的", status, confirmedCandidate = confirmedCandidate,
        confirmed = confirmed, safetyRoute = safetyRoute, planProposal = planProposal, activePlan = activePlan)

    @Test fun nothingPendingAndNoReplyIsIdle() {
        assertEquals(CoachUiState.IDLE, CoachStateReducer.reduce(riskRetained = false))
    }

    @Test fun modelWorkIsThinkingAndBusinessWorkIsToolRunning() {
        assertEquals(CoachUiState.THINKING,
            CoachStateReducer.reduce(riskRetained = false, pending = CoachPending.MODEL))
        assertEquals(CoachUiState.TOOL_RUNNING,
            CoachStateReducer.reduce(riskRetained = false, pending = CoachPending.BUSINESS))
    }

    @Test fun retainedRiskOutranksEveryOtherStateIncludingAnInFlightSuccess() {
        for (pending in CoachPending.entries) {
            assertEquals("pending=$pending", CoachUiState.RISK_BLOCKED, CoachStateReducer.reduce(
                riskRetained = true, pending = pending,
                reply = reply(confirmed = true, activePlan = plan(status = "confirmed")),
            ))
        }
    }

    @Test fun serverStopRouteIsTreatedAsRetainedRisk() {
        assertEquals(CoachUiState.RISK_BLOCKED, CoachStateReducer.reduce(
            riskRetained = false, reply = reply(status = "blocked", safetyRoute = CoachSafetyRoute.STOP_AND_SEEK_CARE),
        ))
        assertEquals(CoachUiState.RISK_BLOCKED, CoachStateReducer.reduce(
            riskRetained = false, reply = reply(status = "blocked", safetyRoute = CoachSafetyRoute.PROFESSIONAL_REVIEW),
        ))
    }

    /** Unsupported experience is not a body risk; it must not borrow the serious card. */
    @Test fun unsupportedExperienceIsAFailureNotARisk() {
        assertEquals(CoachUiState.FAIL, CoachStateReducer.reduce(
            riskRetained = false, reply = reply(status = "blocked", safetyRoute = CoachSafetyRoute.UNSUPPORTED_EXPERIENCE),
        ))
    }

    @Test fun aConfirmableDraftAwaitsConfirmationAndAnUnconfirmableOneIsStillProposing() {
        val draft = reply(confirmed = true, planProposal = plan())
        assertEquals(CoachUiState.PROPOSING,
            CoachStateReducer.reduce(riskRetained = false, reply = draft, canConfirm = false))
        assertEquals(CoachUiState.AWAITING_CONFIRM,
            CoachStateReducer.reduce(riskRetained = false, reply = draft, canConfirm = true))
    }

    @Test fun aRuleFallbackDraftKeepsItsOwnStateAndIsStillConfirmable() {
        val rule = reply(confirmed = true, planProposal = plan(source = "rule_fallback"))
        assertEquals(CoachUiState.RULE_FALLBACK,
            CoachStateReducer.reduce(riskRetained = false, reply = rule, canConfirm = true))
    }

    @Test fun aProfileSummaryIsProposingUntilItCanBeConfirmed() {
        val summary = reply(status = "ready", confirmedCandidate = candidate())
        assertEquals(CoachUiState.PROPOSING, CoachStateReducer.reduce(riskRetained = false, reply = summary))
        assertEquals(CoachUiState.AWAITING_CONFIRM,
            CoachStateReducer.reduce(riskRetained = false, reply = summary, canConfirm = true))
    }

    @Test fun aReadBackConfirmationIsSuccess() {
        val accepted = reply(status = "ready", confirmed = true, activePlan = plan(status = "confirmed"))
        assertEquals(CoachUiState.SUCCESS, CoachStateReducer.reduce(riskRetained = false, reply = accepted))
    }

    @Test fun retryableFailureShakesAndLocalRefusalDoesNot() {
        assertEquals(CoachUiState.FAIL, CoachStateReducer.reduce(
            riskRetained = false, error = CoachException("network_error", retryable = true),
        ))
        // A blank/oversized input is refused by the client; nothing "broke", so nothing shakes.
        assertEquals(CoachUiState.IDLE, CoachStateReducer.reduce(
            riskRetained = false, error = CoachException("invalid_input"),
        ))
        assertTrue(CoachException("network_error").isRetryableFailure())
        assertTrue(!CoachException("invalid_input").isRetryableFailure())
    }

    /** Work in flight outranks the previous failure: retrying must not shake the whole time. */
    @Test fun aRetryInFlightStopsShowingTheFailure() {
        assertEquals(CoachUiState.THINKING, CoachStateReducer.reduce(
            riskRetained = false, pending = CoachPending.MODEL,
            error = CoachException("network_error", retryable = true),
        ))
    }

    @Test fun anUnknownConfirmationKeepsTheConfirmStateWithoutInventingSuccess() {
        assertEquals(CoachUiState.AWAITING_CONFIRM, CoachStateReducer.reduce(
            riskRetained = false, reply = reply(status = "ready", confirmed = true), planConfirmationUnknown = true,
        ))
    }

    @Test fun wireNamesRoundTripAndAreStable() {
        for (state in CoachUiState.entries) {
            assertEquals(state, CoachUiState.fromWire(state.wire))
        }
        assertNull(CoachUiState.fromWire("celebrating"))
        assertEquals("risk_blocked", CoachUiState.RISK_BLOCKED.wire)
    }
}
