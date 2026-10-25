package com.readygym.app

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import com.readygym.core.PlanningPreferences
import com.readygym.core.SafetyFeedback
import com.readygym.core.TrainingExperience
import com.readygym.core.TrainingProfile
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The state → motion mapping, observed from the UI.
 *
 * The point is not "an animation played" — a Robolectric frame cannot prove an animation is
 * pleasant. It is that each state reaches the right surface, that the *state machine* (not a
 * local flag) decides which surface that is, and that the app still works with
 * `Animator duration scale = 0`, which is the review gate's "关闭动画时功能不受阻".
 *
 * Assertions use `assertExists` rather than `assertIsDisplayed` because these screens are taller
 * than Robolectric's default 320×470dp viewport; the card under test is composed and present but
 * may sit below the fold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoachMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun candidate() = CoachCandidate(
        TrainingProfile(age = 30, heightCm = 175.0, weightKg = 70.0, gender = Gender.MALE,
            goal = Goal.MUSCLE, location = Location.GYM, equipment = setOf(Equipment.DUMBBELL)),
        PlanningPreferences(3, 45, TrainingExperience.BEGINNER, SafetyFeedback(screenCompleted = true)),
    )

    private fun summaryReply() = CoachReply(CoachSession("s", "t"), "确认一下", "ready", confirmedCandidate = candidate())

    /** A client whose reply can be held open, so THINKING is observable rather than instantaneous. */
    private class GatedClient(private val reply: CoachReply) : CoachClient {
        val release = CompletableDeferred<Unit>()
        override suspend fun start(): CoachReply { release.await(); return reply }
        override suspend fun message(session: CoachSession, text: String) = reply
        override suspend fun confirm(reply: CoachReply) = reply.copy(confirmed = true)
    }

    /**
     * The chat column is taller than Robolectric's 320×470dp viewport and auto-scrolls to the
     * newest message, so a surface can exist but sit above the fold. Scrolling first is what a
     * user would do; asserting "displayed" without it would just be asserting the viewport size.
     */
    private fun assertOnScreen(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }

    private fun show(coach: CoachConversation, reduceMotion: Boolean = false, voice: VoiceInputController = VoiceInputController(compose.activity, serviceBaseUrl = "")) {
        compose.runOnUiThread {
            compose.activity.setContent {
                ReadyGymTheme {
                    CompositionLocalProvider(
                        LocalMotionSettings provides MotionSettings(reduceMotion),
                        LocalVoiceInput provides voice,
                    ) {
                        CoachChatScreen(coach, serviceConfigured = true, onConfirmed = {}, consented = true)
                    }
                }
            }
        }
    }

    @Test fun thinkingIsObservableWhileTheModelIsAnswering() {
        val client = GatedClient(summaryReply())
        val coach = CoachConversation(client)
        show(coach)
        coach.start()
        // The request is still open, so the reducer must be in a working state and the composer
        // must be disabled; nothing is inferred from `busy` by the UI.
        compose.waitUntil(5_000) { coach.uiState.value == CoachUiState.THINKING }
        compose.onNodeWithTag("coach-state-thinking").assertIsDisplayed()
        assertOnScreen("coach-working")
        compose.onNodeWithTag("coach-voice-toggle").assertExists() // blank input shows the mic, not 发送
        client.release.complete(Unit)
        compose.waitUntil(5_000) { coach.uiState.value == CoachUiState.AWAITING_CONFIRM }
    }

    @Test fun aSummaryCardEntersAndItsConfirmActionRemainsAvailable() {
        val client = GatedClient(summaryReply())
        val coach = CoachConversation(client)
        show(coach)
        coach.start()
        client.release.complete(Unit)
        compose.waitUntil(5_000) { coach.uiState.value == CoachUiState.AWAITING_CONFIRM }
        assertOnScreen("coach-profile-card")
        compose.onNodeWithTag("coach-confirm-profile").assertIsEnabled()
        compose.onNodeWithTag("coach-skeleton").assertDoesNotExist()
        compose.onNodeWithTag("coach-working").assertDoesNotExist()
    }

    /** Reduced motion must not remove the card, its text, or the ability to confirm. */
    @Test fun reducedMotionKeepsEveryActionUsable() {
        val client = GatedClient(summaryReply())
        val coach = CoachConversation(client)
        show(coach, reduceMotion = true)
        coach.start()
        client.release.complete(Unit)
        compose.waitUntil(5_000) { coach.uiState.value == CoachUiState.AWAITING_CONFIRM }
        assertOnScreen("coach-profile-card")
        // The card's own text must survive reduced motion; only the movement is skipped.
        compose.onNodeWithText("请确认你的资料").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("coach-confirm-profile").assertIsEnabled()
        compose.onNodeWithTag("coach-input").assertIsDisplayed()
    }

    @Test fun unsentInputDisablesConfirmationAndReturnsToProposing() {
        val client = GatedClient(summaryReply())
        val coach = CoachConversation(client)
        show(coach)
        coach.start()
        client.release.complete(Unit)
        compose.waitUntil(5_000) { coach.uiState.value == CoachUiState.AWAITING_CONFIRM }
        compose.onNodeWithTag("coach-input").performTextInput("等一下，我先改个体重")
        compose.waitUntil(5_000) { coach.uiState.value == CoachUiState.PROPOSING }
        compose.onNodeWithTag("coach-confirm-profile").assertIsNotEnabled()
    }

    /** A retained body-risk route wins over everything, and shows the serious card instead. */
    @Test fun aRetainedRiskRouteTakesOverTheScreen() {
        val blocked = CoachReply(CoachSession("s", "t"), "请停止训练", "blocked",
            safetyRoute = CoachSafetyRoute.STOP_AND_SEEK_CARE)
        val client = GatedClient(blocked)
        val coach = CoachConversation(client)
        show(coach)
        coach.start()
        client.release.complete(Unit)
        compose.waitUntil(5_000) { coach.uiState.value == CoachUiState.RISK_BLOCKED }
        assertOnScreen("coach-blocked-card")
        compose.onNodeWithTag("coach-confirm-profile").assertDoesNotExist()
        assertEquals(CoachUiState.RISK_BLOCKED, coach.uiState.value)
    }

    /**
     * 语音识别成功后必须自动落回输入框：用户要能直接看到转写文字、核对识别得准不准，
     * 再点发送。发送按钮只在会话建立后出现，所以先走完自动建档再模拟提交。
     */
    @Test fun recognizedSpeechLandsInTheTextFieldForReview() {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val voice = VoiceInputController(compose.activity, serviceBaseUrl = "")
        val client = GatedClient(summaryReply())
        val coach = CoachConversation(client)
        show(coach, voice = voice) // the screen auto-starts the conversation (line 119)
        client.release.complete(Unit)
        compose.waitUntil(5_000) { coach.uiState.value == CoachUiState.AWAITING_CONFIRM }
        compose.onNodeWithTag("coach-voice-toggle").performClick()
        compose.onNodeWithTag("coach-hold-talk").assertExists()
        // A press is what wires the composer's commit callback onto the controller.
        compose.onNodeWithTag("coach-hold-talk").performTouchInput { down(center); up() }
        compose.runOnIdle { voice.onFinal?.invoke("帮我排个肩部计划") ?: error("press did not wire onFinal") }
        compose.onNodeWithTag("coach-hold-talk").assertDoesNotExist() // back to the text field
        compose.onNodeWithTag("coach-input").assertTextContains("帮我排个肩部计划")
        compose.onNodeWithTag("coach-send").assertExists() // ready to review → 发送 is one tap away
    }

    /** A failed recognition keeps the hold-to-talk surface so the user can simply retry. */
    @Test fun failedRecognitionStaysInVoiceMode() {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val voice = VoiceInputController(compose.activity, serviceBaseUrl = "")
        val client = GatedClient(summaryReply())
        val coach = CoachConversation(client)
        show(coach, voice = voice)
        client.release.complete(Unit)
        compose.waitUntil(5_000) { coach.uiState.value == CoachUiState.AWAITING_CONFIRM }
        compose.onNodeWithTag("coach-voice-toggle").performClick()
        compose.onNodeWithTag("coach-hold-talk").performTouchInput { down(center); up() }
        compose.runOnIdle { voice.onFinal = null } // errors never invoke onFinal; simulate that
        compose.waitUntil(5_000) { voice.state.value is VoiceInputController.State.Error }
        compose.onNodeWithTag("coach-hold-talk").assertExists() // still voice mode, can retry
        compose.onNodeWithTag("coach-input").assertDoesNotExist()
    }

    @Test fun readingEarlierMessagesWhileWaitingDoesNotJumpToTheNewReply() {
        val release = CompletableDeferred<Unit>()
        val client = object : CoachClient {
            override suspend fun start() = CoachReply(CoachSession("reading", "token"),
                "这是之前的训练建议，慢慢阅读。".repeat(100), "collecting")
            override suspend fun message(session: CoachSession, text: String): CoachReply {
                release.await()
                return CoachReply(session, "这是新回复", "collecting")
            }
        }
        val coach = CoachConversation(client)
        show(coach, reduceMotion = true)
        compose.waitUntil(5_000) { coach.session != null && !coach.busy }
        compose.onNodeWithTag("coach-input").performTextInput("我想问一个问题")
        compose.onNodeWithTag("coach-send").performClick()
        compose.waitUntil(5_000) { coach.busy }
        compose.onNodeWithTag("coach-messages").performTouchInput { swipeDown() }
        compose.waitForIdle()
        release.complete(Unit)
        compose.waitUntil(5_000) { !coach.busy }
        compose.onNodeWithTag("coach-new-reply").assertIsDisplayed().performClick()
        compose.onNodeWithTag("coach-new-reply").assertDoesNotExist()
        compose.onNodeWithText("这是新回复").assertIsDisplayed()
    }

    /** 确认保存后，旧资料卡片不再留在对话中。 */
    @Test fun aConfirmedProfileCardLeavesTheTranscript() {
        val client = GatedClient(summaryReply())
        val coach = CoachConversation(client)
        show(coach)
        coach.start()
        client.release.complete(Unit)
        compose.waitUntil(5_000) { coach.uiState.value == CoachUiState.AWAITING_CONFIRM }
        assertOnScreen("coach-profile-card")
        compose.onNodeWithTag("coach-confirm-profile").performClick()
        compose.waitUntil(5_000) { coach.reply?.confirmed == true }
        compose.onNodeWithTag("coach-profile-card").assertDoesNotExist()
    }
}
