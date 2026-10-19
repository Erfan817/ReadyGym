package com.readygym.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.readygym.app.data.GymDatabase
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The shell's routing: four tabs, the 训练 tab first, and no network call until the user consents. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppShellTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val client = object : CoachClient {
        var starts = 0
        override suspend fun start(): CoachReply { starts++; return CoachReply(CoachSession("s", "t"), "你好，先说说你的目标？", "collecting") }
        override suspend fun message(session: CoachSession, text: String) = CoachReply(session, "收到", "collecting")
    }

    @After fun closeDatabase() = GymDatabase.resetForTest()

    private fun launch() {
        compose.runOnUiThread { compose.activity.setContent { ReadyGymTheme { ReadyGymApp(client) } } }
        // Room answers on its own executor, which waitForIdle does not track.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("train-create-profile").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun opensOnTrainTabAndAsksForAProfileFirst() {
        launch()
        compose.onNodeWithTag("tab-train").assertIsSelected()
        compose.onNodeWithTag("train-create-profile").assertIsDisplayed()
    }

    @Test fun coachTabWaitsForConsentBeforeStartingTheChat() {
        launch()
        compose.onNodeWithTag("tab-coach").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("同意并开始").assertIsDisplayed()
        check(client.starts == 0) { "the chat must not start before consent" }
    }

    @Test fun consentIsAskedOnceThenTheChatStartsByItself() {
        launch()
        compose.onNodeWithTag("tab-coach").performClick()
        compose.onNodeWithText("同意并开始").performClick()
        compose.waitUntil(5_000) { client.starts == 1 }

        // A fresh launch (new screen, same install) must not show the disclosure again.
        compose.runOnUiThread { compose.activity.setContent { ReadyGymTheme { ReadyGymApp(starter) } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("tab-coach").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-coach").performClick()
        compose.waitUntil(5_000) { starter.starts == 1 }
        compose.onAllNodesWithText("同意并开始").assertCountEquals(0)
        compose.onAllNodesWithText("开始前，请你知情").assertCountEquals(0)
    }

    private val starter = object : CoachClient {
        var starts = 0
        override suspend fun start(): CoachReply { starts++; return CoachReply(CoachSession("s2", "t"), "欢迎回来", "collecting") }
        override suspend fun message(session: CoachSession, text: String) = CoachReply(session, "收到", "collecting")
    }

    @Test fun recordsAndMeTabsShowEmptyStates() {
        launch()
        compose.onNodeWithTag("tab-records").performClick()
        compose.onNodeWithTag("cal-title").assertIsDisplayed()
        compose.onNodeWithTag("tab-me").performClick()
        compose.onNodeWithTag("me-create-profile").assertIsDisplayed()
    }

    @Test fun profileFormOpensFromTrainAndBackReturns() {
        launch()
        compose.onNodeWithTag("train-create-profile").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tab-train").assertDoesNotExist()
        compose.activity.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("tab-train").assertIsSelected()
    }
}
