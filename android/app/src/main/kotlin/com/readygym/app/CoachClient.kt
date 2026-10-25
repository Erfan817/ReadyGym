package com.readygym.app

import com.readygym.core.*
import java.io.IOException

/** Chat transport contract shared by the coach screen and its clients. */
class CoachException(val code: String, val retryable: Boolean = false, val httpStatus: Int? = null,
    val safetyFeedback: SafetyFeedback? = null,
    /** A user-facing Chinese explanation from the server, when it sent one. */
    val detail: String? = null) : IOException(code)
data class CoachSession(val id: String, val token: String) {
    override fun toString() = "CoachSession(<redacted>)"
}
data class CoachReply(val session: CoachSession, val assistantText: String, val status: String,
    val confirmedCandidate: CoachCandidate? = null, val revision: Int? = null, val confirmed: Boolean = false,
    val responseSource: String? = null, val safetyRoute: CoachSafetyRoute? = null,
    val foundationEnabled: Boolean = false, val planProposal: FoundationPlan? = null, val activePlan: FoundationPlan? = null,
    val toolTrace: List<FoundationToolTrace> = emptyList(), val agentSource: String? = null, val agentNotice: String? = null,
    /** Gender the user has stated so far, before the full profile is ready; only drives the chat avatar. */
    val statedGender: Gender? = null)

/** Bounded safety policy, not a diagnosis or an inference from assistant prose. */
enum class CoachSafetyRoute(val wireValue: String) {
    STOP_AND_SEEK_CARE("stop_and_seek_care"),
    PROFESSIONAL_REVIEW("professional_review"),
    UNSUPPORTED_EXPERIENCE("unsupported_experience");

    fun localFeedback(): SafetyFeedback? = when (this) {
        // Core's conservative STOP guard recognizes this policy label. It does not assert a symptom.
        STOP_AND_SEEK_CARE -> SafetyFeedback(screenCompleted = true,
            currentDiscomfort = "安全路线 stop_and_seek_care：停止训练并及时就医；胸痛等危险信号处置路线，不代表症状诊断")
        PROFESSIONAL_REVIEW -> SafetyFeedback(screenCompleted = true,
            medicalRestrictions = "安全路线 professional_review：普通训练前需要专业评估，不作诊断")
        UNSUPPORTED_EXPERIENCE -> null
    }
}
data class CoachCandidate(val profile: TrainingProfile, val preferences: PlanningPreferences)
interface CoachClient {
    val supportsFoundation: Boolean get() = false
    suspend fun readFoundation(session: CoachSession): CoachReply = throw CoachException("foundation_not_supported")
    suspend fun confirmPlan(reply: CoachReply): CoachReply = throw CoachException("confirmation_not_supported")
    suspend fun confirm(reply: CoachReply): CoachReply = throw CoachException("confirmation_not_supported")
    suspend fun delete(session: CoachSession): Unit = throw CoachException("deletion_not_supported")
    suspend fun start(): CoachReply
    suspend fun message(session: CoachSession, text: String): CoachReply
}
