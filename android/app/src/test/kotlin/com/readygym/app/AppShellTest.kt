package com.readygym.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.readygym.app.data.GymDatabase
import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import com.readygym.core.PlanningPreferences
import com.readygym.core.SafetyFeedback
import com.readygym.core.TrainingExperience
import com.readygym.core.TrainingProfile
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shell's routing: five destinations, 首页 first, the coach behind a task, and no network call
 * until the user consents.
 *
 * Room answers on its own executor, which `waitForIdle` does not track, so every screen that reads
 * the database is reached with [awaitTag] rather than a bare click.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppShellTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun skipExternalUpdateCheck() {
        // Navigation tests use fake chat clients; the silent update check must also stay offline.
        PgyerUpdateStore(compose.activity).record(PgyerUpdate.CheckResult.UpToDate, java.time.LocalDate.now().toString())
    }

    private val client = object : CoachClient {
        var starts = 0
        override suspend fun start(): CoachReply { starts++; return CoachReply(CoachSession("s", "t"), "你好，先说说你的目标？", "collecting") }
        override suspend fun message(session: CoachSession, text: String) = CoachReply(session, "收到", "collecting")
    }

    /**
     * JUnit rules wrap `@After`, so the content is still composed when the database would be closed
     * — and the collectors still running against it throw "no current transaction" into the next
     * test. Clearing the ViewModelStore cancels the viewModelScope collectors first, then a short
     * beat lets any Room query already in flight on its executor come back (cancellation does not
     * interrupt it mid-query) before the database is torn down.
     */
    @After fun closeDatabase() {
        compose.runOnUiThread {
            compose.activity.setContent { }
            compose.activity.viewModelStore.clear()
        }
        compose.waitForIdle()
        Thread.sleep(250)
        GymDatabase.resetForTest()
    }

    private fun awaitTag(tag: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun launch(testClient: CoachClient = client) {
        compose.runOnUiThread { compose.activity.setContent { ReadyGymTheme { ReadyGymApp(testClient) } } }
        awaitTag("home-open-coach")
    }

    @Test fun opensOnHomeWhichIsTheCoachEntry() {
        launch()
        compose.onNodeWithTag("home-open-coach").assertIsDisplayed()
        // No plan yet, so the home card must not claim one exists.
        compose.onAllNodesWithText("当前计划").assertCountEquals(0)
    }

    @Test fun everyDestinationIsReachableFromTheBottomBar() {
        launch()
        compose.onNodeWithTag("tab-plan").performClick()
        awaitTag("train-create-profile")
        compose.onNodeWithTag("train-create-profile").assertIsDisplayed()

        compose.onNodeWithTag("tab-stats").performClick()
        // The leaderboard now opens the 统计 page; metrics and the calendar sit below it.
        awaitTag("cal-title")
        compose.onNodeWithTag("metric-训练次数").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cal-title").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("tab-me").performClick()
        awaitTag("me-create-profile")
        compose.onNodeWithTag("me-create-profile").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("tab-home").performClick()
        compose.onNodeWithTag("home-open-coach").assertIsDisplayed()
    }

    @Test fun coachIsATaskThatWaitsForConsentAndCanBeClosed() {
        launch()
        compose.onNodeWithTag("home-open-coach").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("同意并开始").assertIsDisplayed()
        check(client.starts == 0) { "the chat must not start before consent" }
        compose.onNodeWithTag("coach-close").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("home-open-coach").assertIsDisplayed()
    }

    @Test fun consentIsAskedOnceThenTheChatStartsByItself() {
        launch()
        compose.onNodeWithTag("home-open-coach").performClick()
        compose.onNodeWithText("同意并开始").performClick()
        compose.waitUntil(5_000) { client.starts == 1 }

        // A fresh launch (new screen, same install) must not show the disclosure again.
        compose.runOnUiThread { compose.activity.setContent { ReadyGymTheme { ReadyGymApp(starter) } } }
        awaitTag("tab-home")
        compose.onNodeWithTag("home-open-coach").performClick()
        compose.waitUntil(5_000) { starter.starts == 1 }
        compose.onAllNodesWithText("同意并开始").assertCountEquals(0)
        compose.onAllNodesWithText("开始前，请你知情").assertCountEquals(0)
    }

    private val starter = object : CoachClient {
        var starts = 0
        override suspend fun start(): CoachReply { starts++; return CoachReply(CoachSession("s2", "t"), "欢迎回来", "collecting") }
        override suspend fun message(session: CoachSession, text: String) = CoachReply(session, "收到", "collecting")
    }

    @Test fun profileFormOpensFromPlanAndBackReturns() {
        launch()
        compose.onNodeWithTag("tab-plan").performClick()
        awaitTag("train-create-profile")
        compose.onNodeWithTag("train-create-profile").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tab-plan").assertDoesNotExist()
        compose.activity.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("train-create-profile").assertIsDisplayed()
    }

    @Test fun startPageChatActionOpensTheCoachAndClosingReturnsToStart() {
        launch()
        compose.onNodeWithTag("tab-start").performClick()
        awaitTag("start-create-profile")
        compose.onNodeWithText("和 AI 教练聊着建档").performScrollTo().performClick()

        awaitTag("coach-close")
        compose.onNodeWithText("同意并开始").assertIsDisplayed()
        compose.onNodeWithTag("tab-start").assertDoesNotExist()
        assertEquals(0, client.starts)

        compose.onNodeWithTag("coach-close").performClick()
        awaitTag("start-create-profile")
        compose.onNodeWithTag("tab-start").assertIsSelected()
        compose.onNodeWithTag("start-create-profile").assertIsDisplayed()
    }

    @Test fun historyShortcutOpensRecordsAndClosingReturnsToStatistics() {
        launch()
        compose.onNodeWithTag("tab-stats").performClick()
        awaitTag("stats-history")
        compose.onNodeWithTag("stats-history").performScrollTo().performClick()

        awaitTag("training-records-screen")
        compose.onNodeWithTag("training-records-screen").assertIsDisplayed()
        compose.onNodeWithTag("tab-stats").assertDoesNotExist()
        compose.onNodeWithTag("records-close").performScrollTo().performClick()

        awaitTag("stats-history")
        compose.onNodeWithTag("tab-stats").assertIsSelected()
        compose.onNodeWithTag("training-records-screen").assertDoesNotExist()
    }

    @Test fun confirmingChatProfileContinuesToThePlanGenerationStep() {
        val candidate = CoachCandidate(
            TrainingProfile(age = 20, heightCm = 175.0, weightKg = 70.0, gender = Gender.MALE,
                goal = Goal.MUSCLE, location = Location.GYM, equipment = setOf(Equipment.DUMBBELL)),
            PlanningPreferences(3, 40, TrainingExperience.BEGINNER, SafetyFeedback(screenCompleted = true)),
        )
        val ready = CoachReply(CoachSession("ready-session", "fake-token"), "资料整理好了，请你确认。",
            "ready", confirmedCandidate = candidate)
        val readyClient = object : CoachClient {
            var confirmations = 0
            override suspend fun start() = ready
            override suspend fun message(session: CoachSession, text: String) = ready
            override suspend fun confirm(reply: CoachReply): CoachReply {
                confirmations++
                return reply.copy(confirmed = true)
            }
        }
        launch(readyClient)
        compose.onNodeWithTag("home-open-coach").performClick()
        compose.onNodeWithText("同意并开始").performClick()
        awaitTag("coach-confirm-profile")
        compose.onNodeWithTag("coach-confirm-profile").performScrollTo().assertIsEnabled().performClick()

        // Room saves asynchronously: wait for both the handoff notice and the usable next action.
        awaitTag("plan-profile-saved")
        awaitTag("train-generate")
        assertEquals(1, readyClient.confirmations)
        compose.onNodeWithTag("plan-profile-saved").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("train-generate").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("tab-plan").assertIsSelected()
        compose.onNodeWithTag("tab-home").assertIsNotSelected()
        compose.onNodeWithTag("coach-confirm-profile").assertDoesNotExist()
    }

    @Test fun backFromAnyTabReturnsHome() {
        launch()
        compose.onNodeWithTag("tab-me").performClick()
        awaitTag("me-create-profile")
        compose.activity.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("home-open-coach").assertIsDisplayed()
    }

    /** Opening 我的 must not upload anything: the leaderboard moved to 统计 and loads only on tap. */
    @Test fun meTabDoesNotTouchTheNetworkOnItsOwn() {
        launch()
        compose.onNodeWithTag("tab-me").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("board-open").assertDoesNotExist()
        // The leaderboard now lives on 统计, still behind an explicit tap there.
        compose.onNodeWithTag("tab-stats").performClick()
        awaitTag("board-open")
        compose.onNodeWithTag("board-me").assertDoesNotExist()
    }

    /** The equipment guide: opens from home, search narrows the list, a card expands its how-to. */
    @Test fun equipmentGuideSearchesAndExpandsFromTheHomeEntry() {
        launch()
        // The primary home card can put this entry below Robolectric's small viewport.
        compose.onNodeWithTag("home-equipment-row").performScrollTo().assertIsDisplayed().performClick()
        awaitTag("equipment-item-lat-pulldown")

        compose.onNodeWithTag("equipment-search").performScrollTo().performTextInput("跑步")
        compose.waitForIdle()
        compose.onNodeWithTag("equipment-item-lat-pulldown").assertDoesNotExist()
        compose.onNodeWithTag("equipment-item-treadmill").performScrollTo().performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("跟着做").fetchSemanticsNodes().isNotEmpty()
        }
        // The treadmill guide must warn about the safety clip (rendered with a "• " bullet prefix).
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("安全磁扣是急停的最后保障", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // The list is scrolled deep by now, so bring the header close button back into view first.
        compose.onNodeWithTag("equipment-close").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("home-open-coach").performScrollTo().assertIsDisplayed()
    }

    /** 我的 → 训练提醒: enabling it schedules, the days are editable, and the next firing shows. */
    @Test fun reminderPanelEnablesShowsNextFiringAndEditsDays() {
        launch()
        compose.onNodeWithTag("tab-me").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("reminder-toggle").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("reminder-time").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reminder-next").performScrollTo().assertExists()

        // Deselect 周三 from the default 周一/周三/周五 and the next firing must update to a surviving day.
        compose.onNodeWithText("周三").performScrollTo().performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("下一次提醒：周五", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("下一次提醒：周一", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
