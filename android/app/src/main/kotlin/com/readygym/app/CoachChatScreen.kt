package com.readygym.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import com.readygym.core.*
import com.readygym.core.ai.ConversationPlanDraft
import com.readygym.core.ai.ConversationPlanOption
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Presentation source is provided by the server; never pretend a policy string is model prose. */
data class CoachBubble(
    val user: Boolean,
    val text: String,
    val source: String? = null,
    val delivery: CoachMessageDelivery = CoachMessageDelivery.SENT,
    val messageId: Long = 0,
)

/** Waiting means the message is already in the conversation, with the coach's reply pending. */
enum class CoachMessageDelivery { SENT, WAITING, FAILED }

/** Memory only; ViewModel retains this conversation over rotation, not process death. */
class CoachConversation(private val client: CoachClient) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val safetyEngine = PlanningEngine()
    var retainedSafety by mutableStateOf<SafetyFeedback?>(null)
        private set
    val localSafetyBlocked: Boolean get() = retainedSafety != null
    val localSafetyMessage: String? get() = retainedSafety?.let {
        "本次会话已记录身体风险，旧摘要已失效；不能采用普通规则计划。${safetyEngine.evaluateSafety(it).message}返回、重新建档或无风险填写不会清除本次风险。"
    }
    fun recordSafety(feedback: SafetyFeedback) {
        val priority = safetyEngine.evaluateSafety(feedback).route.localRiskPriority()
        if (priority < 2) return
        if (retainedSafety == null || priority >= safetyEngine.evaluateSafety(requireNotNull(retainedSafety)).route.localRiskPriority()) {
            retainedSafety = feedback
            publishState()
        }
    }
    private fun recordServerSafety(reply: CoachReply) {
        reply.safetyRoute?.localFeedback()?.let(::recordSafety)
    }
    val supportsFoundation: Boolean get() = client.supportsFoundation || reply?.foundationEnabled == true
    var planConfirmationUnknown by mutableStateOf(false)
        private set
    var input by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var session by mutableStateOf<CoachSession?>(null)
        private set
    var error by mutableStateOf<CoachException?>(null)
        private set
    var reply by mutableStateOf<CoachReply?>(null)
        private set
    var messages by mutableStateOf<List<CoachBubble>>(emptyList())
        private set
    var correction by mutableStateOf(false)
        private set
    /** Latest gender the user stated in this conversation, for the chat avatar. */
    var statedGender by mutableStateOf<Gender?>(null)
        private set
    private var retryAction: (() -> Unit)? = null
    private var nextMessageId = 0L

    /** Which kind of work is in flight; only [uiState] reads it. */
    private var pending by mutableStateOf(CoachPending.NONE)

    /**
     * The single source of truth for every animation in the coach loop.
     *
     * A `StateFlow` rather than a bare `mutableStateOf` so the reducer is the only writer and so a
     * screen (or a test) can collect it without depending on Compose snapshot reads. [publishState]
     * is called at the end of every mutation below; the UI never derives motion from `busy`/`error`.
     */
    private val stateFlow = MutableStateFlow(CoachUiState.IDLE)
    val uiState: StateFlow<CoachUiState> = stateFlow.asStateFlow()

    /** The latest confirmation's identity, so a card can celebrate once per new revision. */
    val confirmedMilestone: Any? get() = lastConfirmed
    private var lastConfirmed by mutableStateOf<Any?>(null)

    private fun publishState() {
        val next = CoachStateReducer.reduce(
            riskRetained = retainedSafety != null,
            pending = pending,
            error = error,
            reply = reply,
            canConfirm = canConfirmNow(),
            planConfirmationUnknown = planConfirmationUnknown,
        )
        if (next != stateFlow.value) stateFlow.value = next
    }

    /** Whether the on-screen confirm action is actually tappable; the reducer must not guess. */
    private fun canConfirmNow(): Boolean {
        val reviewed = reply ?: return false
        if (busy || retainedSafety != null || input.isNotBlank() || planConfirmationUnknown) return false
        if (reviewed.safetyRoute != null) return false
        val planConfirmable = reviewed.planProposal != null && reviewed.confirmed
        val conversationPlanConfirmable = reviewed.conversationPlan != null
        val profileConfirmable = reviewed.status == "ready" && reviewed.confirmedCandidate != null
        return planConfirmable || conversationPlanConfirmable || profileConfirmable
    }

    fun edit(text: String) { if (!busy) {
        input = text
        if (!planConfirmationUnknown) { error = null; retryAction = null }
        // The confirm action is disabled while input is unsent; the breath must follow that.
        publishState()
    } }
    fun correct() { if (!busy) { reply = reply?.copy(confirmedCandidate = null); correction = true; publishState() } }
    fun start() { if (busy || session != null) return; launch(null) }
    fun send() {
        if (busy || session == null || planConfirmationUnknown) return
        if (input.isBlank() || input.length > 1000) { error = CoachException("invalid_input"); publishState(); return }
        launch(input)
    }
    fun retry() {
        if (busy) return
        if (planConfirmationUnknown) { readFoundation(); return }
        retryAction?.invoke() ?: if (session == null) start() else send()
    }
    fun retryMessage(messageId: Long) {
        if (busy || session == null || planConfirmationUnknown) return
        val bubble = messages.firstOrNull { it.user && it.messageId == messageId && it.delivery == CoachMessageDelivery.FAILED }
            ?: return
        launch(bubble.text, bubble.messageId)
    }
    /** Selecting a validated option is still a draft; only this explicit confirmation may save it. */
    fun confirmConversationPlan(
        index: Int,
        save: suspend (ConversationPlanDraft, ConversationPlanOption) -> String?,
    ) {
        val reviewed = reply ?: return
        recordServerSafety(reviewed)
        val draft = reviewed.conversationPlan ?: return
        val option = draft.options.getOrNull(index) ?: return
        if (busy || localSafetyBlocked || input.isNotBlank() || reviewed.safetyRoute != null || planConfirmationUnknown) return
        busy = true; pending = CoachPending.BUSINESS; error = null; retryAction = null
        publishState()
        scope.launch {
            try {
                val problem = save(draft, option)
                if (problem != null) throw CoachException("plan_save_failed", detail = problem)
                reply = reviewed.copy(conversationPlan = null, confirmed = true)
                lastConfirmed = option.plan
                messages = messages + CoachBubble(false,
                    "计划已更新，当前训练中待做的动作也已同步，已完成的记录保留。")
            } catch (e: CancellationException) {
                throw e
            } catch (e: CoachException) {
                e.safetyFeedback?.let(::recordSafety)
                error = e
            } catch (_: Exception) {
                error = CoachException("plan_save_failed", detail = "这次没能确认保存结果，请查看计划后重新选择方案。")
            } finally {
                busy = false; pending = CoachPending.NONE; publishState()
            }
        }
    }
    fun dismissConversationPlan() {
        if (busy) return
        reply = reply?.copy(conversationPlan = null)
        error = null; retryAction = null
        publishState()
    }
    fun confirm(onConfirmed: (CoachCandidate) -> Unit) {
        val reviewed = reply ?: return
        recordServerSafety(reviewed)
        if (busy || localSafetyBlocked || input.isNotBlank() || reviewed.status != "ready" || reviewed.safetyRoute != null || reviewed.confirmedCandidate == null) return
        busy = true; pending = CoachPending.BUSINESS; error = null; retryAction = { confirm(onConfirmed) }
        publishState()
        scope.launch {
            try {
                val accepted = client.confirm(reviewed)
                recordServerSafety(accepted)
                if (localSafetyBlocked) throw CoachException("local_safety_block")
                if (accepted.status != "ready" || accepted.confirmedCandidate != reviewed.confirmedCandidate) throw CoachException("invalid_response")
                reply = accepted; retryAction = null
                lastConfirmed = accepted.confirmedCandidate
                if (supportsFoundation) {
                    messages = messages + CoachBubble(false, accepted.assistantText, accepted.agentSource ?: accepted.responseSource)
                } else onConfirmed(requireNotNull(accepted.confirmedCandidate))
            } catch (e: CoachException) { e.safetyFeedback?.let(::recordSafety); error = e }
            catch (_: IOException) { error = CoachException("network_error", true) }
            finally { busy = false; pending = CoachPending.NONE; publishState() }
        }
    }
    fun confirmPlan() {
        val reviewed = reply ?: return
        recordServerSafety(reviewed)
        if (busy || localSafetyBlocked || input.isNotBlank() || planConfirmationUnknown || reviewed.safetyRoute != null || reviewed.planProposal == null || !reviewed.confirmed) return
        busy = true; pending = CoachPending.BUSINESS; error = null; retryAction = { readFoundation() }
        publishState()
        scope.launch {
            try {
                val accepted = client.confirmPlan(reviewed)
                recordServerSafety(accepted)
                if (localSafetyBlocked) throw CoachException("local_safety_block")
                if (accepted.activePlan != reviewed.planProposal.copy(status = "confirmed") || accepted.planProposal != null) throw CoachException("invalid_response")
                reply = accepted; planConfirmationUnknown = false; retryAction = null
                lastConfirmed = accepted.activePlan
                messages = messages + CoachBubble(false, accepted.assistantText, accepted.agentSource ?: accepted.responseSource)
            } catch (e: CoachException) { e.safetyFeedback?.let(::recordSafety); planConfirmationUnknown = true; error = e }
            catch (_: IOException) { planConfirmationUnknown = true; error = CoachException("network_error", true) }
            finally { busy = false; pending = CoachPending.NONE; publishState() }
        }
    }
    fun readFoundation() {
        val current = session ?: return
        if (busy) return
        busy = true; pending = CoachPending.BUSINESS; error = null; retryAction = { readFoundation() }
        publishState()
        scope.launch {
            try {
                val accepted = client.readFoundation(current)
                recordServerSafety(accepted)
                reply = accepted; planConfirmationUnknown = false; retryAction = null
            } catch (e: CoachException) { e.safetyFeedback?.let(::recordSafety); error = e }
            catch (_: IOException) { error = CoachException("network_error", true) }
            finally { busy = false; pending = CoachPending.NONE; publishState() }
        }
    }
    fun clear() {
        val current = session ?: return
        if (busy) return
        busy = true; pending = CoachPending.BUSINESS; error = null; retryAction = { clear() }
        publishState()
        scope.launch {
            try {
                client.delete(current)
                session = null; reply = null; messages = emptyList(); input = ""; correction = false; retryAction = null; planConfirmationUnknown = false; statedGender = null
            } catch (e: CoachException) { e.safetyFeedback?.let(::recordSafety); error = e }
            catch (_: IOException) { error = CoachException("network_error", true) }
            finally { busy = false; pending = CoachPending.NONE; publishState() }
        }
    }
    fun restart() {
        if (busy) return
        session = null; reply = null; error = null; messages = emptyList(); correction = false; retryAction = null; planConfirmationUnknown = false; statedGender = null
        // Keep unsent input. No network until another explicit consent tap.
        publishState()
    }
    private fun launch(text: String?, retryMessageId: Long? = null) {
        val messageId = if (text == null) null else retryMessageId ?: ++nextMessageId
        if (text != null) {
            if (retryMessageId == null) {
                messages = messages + CoachBubble(true, text, delivery = CoachMessageDelivery.WAITING, messageId = requireNotNull(messageId))
                input = ""
            } else updateDelivery(retryMessageId, CoachMessageDelivery.WAITING)
        }
        busy = true; pending = CoachPending.MODEL; error = null; retryAction = { launch(text, messageId) }
        reply = reply?.copy(confirmedCandidate = null, planProposal = null, conversationPlan = null)
        publishState()
        scope.launch {
            try {
                val result = if (text == null) client.start() else client.message(requireNotNull(session), text)
                recordServerSafety(result)
                session = result.session
                messageId?.let { updateDelivery(it, CoachMessageDelivery.SENT) }
                messages = messages + CoachBubble(false, result.assistantText, result.responseSource)
                reply = result; correction = false; retryAction = null
                (result.confirmedCandidate?.profile?.gender ?: result.statedGender)?.let { statedGender = it }
            } catch (e: CoachException) {
                messageId?.let { updateDelivery(it, CoachMessageDelivery.FAILED) }
                e.safetyFeedback?.let(::recordSafety); error = e
            } catch (_: IOException) {
                messageId?.let { updateDelivery(it, CoachMessageDelivery.FAILED) }
                error = CoachException("network_error", true)
            }
            finally { busy = false; pending = CoachPending.NONE; publishState() }
        }
    }
    private fun updateDelivery(messageId: Long, delivery: CoachMessageDelivery) {
        messages = messages.map { if (it.user && it.messageId == messageId) it.copy(delivery = delivery) else it }
    }
    override fun onCleared() { scope.cancel() }
}


internal fun SafetyRoute.localRiskPriority() = when (this) {
    SafetyRoute.TEMPLATE_ALLOWED -> 0
    SafetyRoute.SCREEN_REQUIRED -> 1
    SafetyRoute.PAUSE_AND_REASSESS -> 2
    SafetyRoute.PROFESSIONAL_REVIEW -> 3
    SafetyRoute.STOP_AND_SEEK_CARE -> 4
}
