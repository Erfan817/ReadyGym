package com.readygym.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import com.readygym.core.SafetyFeedback
import com.readygym.core.ai.ConversationPlanDraft
import com.readygym.core.ai.ConversationPlanOption
import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sending is visible before the response, and retries keep the same bubble and unsent draft. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoachMessageDeliveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private var voice: VoiceInputController? = null

    private class Client : CoachClient {
        val session = CoachSession("send-test", "token")
        val replies = ArrayDeque<CompletableDeferred<CoachReply>>()
        val sent = mutableListOf<String>()
        var initialDraft: ConversationPlanDraft? = null
        override suspend fun start() = CoachReply(session, "说说想调整什么。", "collecting", conversationPlan = initialDraft)
        override suspend fun message(session: CoachSession, text: String): CoachReply {
            sent += text
            return replies.removeFirst().await()
        }
        fun reply(text: String = "收到，我来调整。") = CoachReply(session, text, "collecting")
    }

    private fun show(client: Client): CoachConversation {
        val coach = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CoachConversation(client) as T
        })[CoachConversation::class.java]
        voice = VoiceInputController(compose.activity, serviceBaseUrl = "")
        compose.setContent {
            ReadyGymTheme {
                CompositionLocalProvider(LocalVoiceInput provides requireNotNull(voice),
                    LocalMotionSettings provides MotionSettings.Reduced) {
                    CoachChatScreen(coach, serviceConfigured = true, onConfirmed = {}, consented = true)
                }
            }
        }
        compose.waitUntil(5_000) { coach.session != null && !coach.busy }
        return coach
    }

    @After fun close() {
        compose.runOnUiThread {
            compose.activity.setContent { }
            store.clear()
            voice?.dispose()
        }
    }

    private fun send(text: String) {
        compose.onNodeWithTag("coach-input").performTextInput(text)
        compose.onNodeWithTag("coach-send").performClick()
    }

    private fun draft(): ConversationPlanDraft {
        val profile = UserProfile(24, 175.0, 70.0, Gender.MALE, Goal.MUSCLE, Location.HOME,
            setOf(Equipment.DUMBBELL), 1, 30, Experience.BEGINNER)
        val base = WorkoutPlan(4, 0, "原计划", "上肢", 1, listOf(PlanDay("上肢", items = listOf(
            PlanItem("push-up", 2, 8, 12), PlanItem("dumbbell-row", 2, 8, 12)))))
        return ConversationPlanDraft(base, profile, listOf(ConversationPlanOption("不用器械", "换成徒手动作",
            base.copy(days = listOf(base.days.single().copy(items = listOf(
                PlanItem("push-up", 2, 8, 12), PlanItem("plank", 2, seconds = 30))))))))
    }

    @Test fun sentMessageIsAlreadyInABubbleWhileCoachIsThinking() {
        val client = Client()
        val gate = CompletableDeferred<CoachReply>()
        client.replies += gate
        val coach = show(client)
        send("我们健身房没有腿弯举器械")
        compose.waitUntil(5_000) { coach.busy }
        compose.onNodeWithText("我们健身房没有腿弯举器械").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("coach-message-sent-1").assertExists()
        compose.onNodeWithTag("coach-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("coach-skeleton").assertDoesNotExist()
        assertEquals(1, coach.messages.count { it.user })
        assertEquals(CoachMessageDelivery.WAITING, coach.messages.single { it.user }.delivery)
        gate.complete(client.reply())
        compose.waitUntil(5_000) { !coach.busy }
        assertEquals(CoachMessageDelivery.SENT, coach.messages.single { it.user }.delivery)
        compose.onAllNodesWithText("我们健身房没有腿弯举器械").assertCountEquals(1)
    }

    @Test fun failedMessageCanBeRetriedWithoutCreatingAnotherBubble() {
        val client = Client()
        val first = CompletableDeferred<CoachReply>()
        val second = CompletableDeferred<CoachReply>()
        client.replies += first
        client.replies += second
        val coach = show(client)
        send("我想换掉罗马尼亚硬拉")
        first.completeExceptionally(CoachException("network_error", true))
        compose.waitUntil(5_000) { !coach.busy && coach.error != null }
        assertEquals(CoachMessageDelivery.FAILED, coach.messages.single { it.user }.delivery)
        val id = coach.messages.single { it.user }.messageId
        compose.onNodeWithTag("coach-message-retry-1").performScrollTo().performClick()
        compose.waitUntil(5_000) { coach.busy }
        assertEquals(id, coach.messages.single { it.user }.messageId)
        assertEquals(CoachMessageDelivery.WAITING, coach.messages.single { it.user }.delivery)
        second.complete(client.reply())
        compose.waitUntil(5_000) { !coach.busy }
        assertEquals(listOf("我想换掉罗马尼亚硬拉", "我想换掉罗马尼亚硬拉"), client.sent)
        compose.onAllNodesWithText("我想换掉罗马尼亚硬拉").assertCountEquals(1)
        compose.onNodeWithTag("coach-message-retry-1").assertDoesNotExist()
    }

    @Test fun retryingAFailedBubbleDoesNotOverwriteANewUnsentDraft() {
        val client = Client()
        val first = CompletableDeferred<CoachReply>()
        val second = CompletableDeferred<CoachReply>()
        client.replies += first
        client.replies += second
        val coach = show(client)
        send("没有腿弯举器械")
        first.completeExceptionally(CoachException("network_error", true))
        compose.waitUntil(5_000) { !coach.busy && coach.error != null }
        compose.onNodeWithTag("coach-input").performTextInput("不过我们有弹力带")
        compose.onNodeWithTag("coach-message-retry-1").performScrollTo().performClick()
        compose.waitUntil(5_000) { coach.busy }
        assertEquals("不过我们有弹力带", coach.input)
        second.complete(client.reply())
        compose.waitUntil(5_000) { !coach.busy }
        compose.onNodeWithTag("coach-input").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("不过我们有弹力带")))
        assertEquals(1, coach.messages.count { it.user })
    }

    @Test fun rejectedOversizedInputStaysInTheComposerWithoutSendingABubble() {
        val client = Client()
        val coach = show(client)
        compose.runOnIdle { coach.edit("练".repeat(1001)); coach.send() }
        assertEquals(1001, coach.input.length)
        assertTrue(client.sent.isEmpty())
        assertTrue(coach.messages.none { it.user })
        assertEquals("invalid_input", coach.error?.code)
    }

    @Test fun planConfirmationSavesOnlyTheChosenDraftOnceAndAcknowledgesAfterSuccess() {
        val proposed = draft()
        val client = Client().apply { initialDraft = proposed }
        val coach = show(client)
        val gate = CompletableDeferred<String?>()
        var writes = 0
        assertEquals(CoachUiState.AWAITING_CONFIRM, coach.uiState.value)
        assertEquals(0, writes)
        compose.runOnIdle {
            coach.confirmConversationPlan(0) { actual, option ->
                assertEquals(proposed, actual)
                assertEquals(proposed.options.single(), option)
                writes++
                gate.await()
            }
            coach.confirmConversationPlan(0) { _, _ -> writes++; null }
        }
        compose.waitUntil(5_000) { coach.busy }
        assertEquals(CoachUiState.TOOL_RUNNING, coach.uiState.value)
        assertEquals(1, writes)
        assertFalse(coach.messages.any { "计划已更新" in it.text })
        gate.complete(null)
        compose.waitUntil(5_000) { !coach.busy }
        assertNull(coach.reply?.conversationPlan)
        assertEquals(CoachUiState.SUCCESS, coach.uiState.value)
        assertTrue(coach.messages.any { "计划已更新" in it.text })
    }

    @Test fun sendingANewerInstructionImmediatelyInvalidatesThePreviousDraft() {
        val client = Client().apply { initialDraft = draft() }
        val gate = CompletableDeferred<CoachReply>()
        client.replies += gate
        val coach = show(client)
        send("不用这个方案，再换一个")
        compose.waitUntil(5_000) { coach.busy }
        assertNull(coach.reply?.conversationPlan)
        var writes = 0
        compose.runOnIdle { coach.confirmConversationPlan(0) { _, _ -> writes++; null } }
        assertEquals(0, writes)
        gate.complete(client.reply())
        compose.waitUntil(5_000) { !coach.busy }
    }

    @Test fun retainedBodyRiskRefusesPlanConfirmation() {
        val coach = show(Client().apply { initialDraft = draft() })
        var writes = 0
        compose.runOnIdle {
            coach.recordSafety(SafetyFeedback(screenCompleted = true, currentDiscomfort = "胸痛"))
            coach.confirmConversationPlan(0) { _, _ -> writes++; null }
        }
        assertEquals(0, writes)
        assertEquals(CoachUiState.RISK_BLOCKED, coach.uiState.value)
    }

    @Test fun failedPlanSaveKeepsTheDraftAndDoesNotClaimSuccess() {
        val proposed = draft()
        val coach = show(Client().apply { initialDraft = proposed })
        compose.runOnIdle {
            coach.confirmConversationPlan(0) { _, _ -> "计划刚刚更新了，请重新选择方案。" }
        }
        compose.waitUntil(5_000) { !coach.busy && coach.error != null }
        assertEquals(proposed, coach.reply?.conversationPlan)
        assertFalse(coach.reply?.confirmed == true)
        assertFalse(coach.messages.any { "计划已更新" in it.text })
        assertEquals("计划刚刚更新了，请重新选择方案。", coach.error?.detail)
        assertFalse(requireNotNull(coach.error).retryable)
        compose.runOnIdle { coach.dismissConversationPlan() }
        assertNull(coach.reply?.conversationPlan)
        assertNull(coach.error)
    }
}
