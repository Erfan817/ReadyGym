package com.readygym.app

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.readygym.app.data.GymDatabase
import com.readygym.app.data.WeightRepository
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the actual sheet → ViewModel → Room → chart/history loop without any network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeightTrackingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GymDatabase::class.java).build()
    private val store = ViewModelStore()
    private val weights by lazy {
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WeightViewModel(WeightRepository(db)) as T
        })[WeightViewModel::class.java]
    }

    @After fun close() {
        compose.runOnUiThread { compose.activity.setContent { }; store.clear() }
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(150)
        db.close()
    }

    private fun awaitTag(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitStoredState(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        // Room completes on its own executor, then resumes the ViewModel and invalidation
        // collectors through Android's main looper. Compose's virtual frame clock alone
        // does not drain those Android messages in Robolectric's paused-looper mode.
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
            check(System.currentTimeMillis() < deadline) {
                "state=${weights.state.value}, saving=${weights.saving}, error=${weights.error}"
            }
            Thread.sleep(5)
        }
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.clickInSheet() {
        compose.mainClock.advanceTimeBy(500)
        assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    @Test fun recordSetTargetEditAndDeleteAreVisibleWithoutReopeningStatistics() {
        compose.setContent {
            ReadyGymTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    WeightTrackingPanel(profileWeightKg = 70.0, model = weights)
                }
            }
        }
        awaitTag("weight-card")
        compose.onNodeWithTag("weight-chart-empty").assertExists()
        assertEquals(emptyList<Any>(), weights.state.value.entries)
        assertNull(weights.state.value.targetKg)
        compose.onNodeWithTag("weight-record").performScrollTo().performClick()
        compose.onNodeWithTag("weight-input").performTextReplacement("70.0")
        compose.onNodeWithTag("weight-save").clickInSheet()
        awaitStoredState { weights.state.value.entries.size == 1 && !weights.saving }
        compose.onNodeWithTag("weight-value-sheet").assertDoesNotExist()
        compose.onNodeWithTag("weight-chart").assertExists()

        compose.onNodeWithTag("weight-target").performScrollTo().performClick()
        compose.onNodeWithTag("weight-input").performTextReplacement("69.1")
        compose.onNodeWithTag("weight-save").clickInSheet()
        awaitStoredState { weights.state.value.targetKg == 69.1 && !weights.saving }
        compose.onNodeWithTag("weight-goal-summary").performScrollTo().assertIsDisplayed()

        val date = LocalDate.now()
        compose.onNodeWithTag("weight-history").performScrollTo().performClick()
        compose.onNodeWithTag("weight-history-entry-$date").clickInSheet()
        compose.onNodeWithTag("weight-input").performTextReplacement("69.8")
        compose.onNodeWithTag("weight-save").clickInSheet()
        awaitStoredState { weights.state.value.entries.singleOrNull()?.kilograms == 69.8 && !weights.saving }
        compose.onNodeWithTag("weight-history-sheet").assertExists()
        compose.onNodeWithText("69.8 kg").assertIsDisplayed()
        compose.onNodeWithTag("weight-history-delete-$date").clickInSheet()
        compose.onNodeWithTag("weight-delete-confirm").clickInSheet()
        awaitStoredState { weights.state.value.entries.isEmpty() && !weights.saving }
        compose.onNodeWithTag("weight-history-close").clickInSheet()
        compose.onNodeWithTag("weight-chart-empty").assertExists()
        assertEquals(69.1, weights.state.value.targetKg!!, 0.0)
    }
}
