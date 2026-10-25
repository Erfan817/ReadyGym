package com.readygym.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.readygym.core.workout.SetLog
import com.readygym.core.workout.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The workout's thumb targets and rest dock must keep working with animations removed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun groupCanBeCompletedAndUndoneUsingTheFullTouchTarget() {
        var set by mutableStateOf(SetLog("test", 0, reps = 10))
        compose.setContent {
            ReadyGymTheme {
                CompositionLocalProvider(LocalMotionSettings provides MotionSettings.Reduced) {
                    WorkoutSetRow(1, 0, set, previous = null, timed = false, loadable = false, active = !set.done) { set = it }
                }
            }
        }
        val control = compose.onNodeWithTag("set-done-0")
        control.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assertIsOff()
        control.performClick().assertIsOn()
        compose.runOnIdle { assertEquals(true, set.done) }
        control.performClick().assertIsOff()
        compose.runOnIdle { assertEquals(false, set.done) }
        compose.onNodeWithText("当前").assertIsDisplayed()
    }

    @Test fun restDockKeepsItsBoundsWhenStartingExtendingAndSkippingRest() {
        var remaining by mutableStateOf<Long?>(null)
        compose.setContent {
            ReadyGymTheme {
                WorkoutRestDock(remaining, completedSets = 1, totalSets = 6,
                    onAddRest = { remaining = requireNotNull(remaining) + 30 }, onSkipRest = { remaining = null })
            }
        }
        val dock = compose.onNodeWithTag("workout-rest-dock")
        val idleBounds = dock.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { remaining = 30 }
        compose.onNodeWithText("00:30").assertIsDisplayed()
        assertEquals(idleBounds, dock.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("workout-rest-add").performClick()
        compose.onNodeWithText("01:00").assertIsDisplayed()
        assertEquals(idleBounds, dock.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("workout-rest-skip").performClick()
        compose.onNodeWithText("1 / 6 组").assertIsDisplayed()
        assertEquals(idleBounds, dock.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun partialTrainingSummaryCountsOnlyLoggedWorkAndCelebratesOnce() {
        val session = WorkoutSession(id = 7, planVersion = 1, dayIndex = 0, dayTitle = "下肢",
            startedAtMillis = 0, endedAtMillis = 5 * 60_000L,
            sets = listOf(SetLog("squat", 0, reps = 10, loadKg = 10.0, done = true),
                SetLog("squat", 1, reps = 10, loadKg = 10.0), SetLog("plank", 0, seconds = 30)))
        var shown = 0
        compose.setContent {
            ReadyGymTheme {
                CompositionLocalProvider(LocalMotionSettings provides MotionSettings.Reduced) {
                    WorkoutCompletionSummary(session, onCelebrated = { shown++ })
                }
            }
        }
        compose.onNodeWithText("每一组努力都已记下").assertIsDisplayed()
        compose.onNodeWithText("1/3").assertIsDisplayed()
        compose.onNodeWithText("1 个").assertIsDisplayed()
        compose.onNodeWithText("5 分钟").assertIsDisplayed()
        compose.onNodeWithText("累计负重 100 kg", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, shown) }
    }
}
