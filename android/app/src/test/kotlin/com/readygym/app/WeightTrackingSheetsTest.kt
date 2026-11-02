package com.readygym.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
import com.readygym.core.weight.WeightEntry
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeightTrackingSheetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun awaitExpandedSheet() {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun clickInSheet(tag: String, scrollIntoView: Boolean = false) {
        // ModalBottomSheet and AlertDialog have their own Android windows. As in
        // MusclePickerUiTest, Robolectric's coordinate injection can miss those windows;
        // prove the real action is reachable and enabled, then use its accessible click.
        val action = compose.onNodeWithTag(tag)
        if (scrollIntoView) action.performScrollTo()
        action.assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    @Test fun targetChangesRemainLocalUntilExplicitlySaved() {
        var saved: Double? = null
        var dismissed = 0
        compose.setContent {
            ReadyGymTheme {
                WeightValueSheet("目标体重", 69.1, isTarget = true,
                    onDismiss = { dismissed++ }, onSave = { _, kg -> saved = kg })
            }
        }
        awaitExpandedSheet()
        clickInSheet("weight-step-5", scrollIntoView = true)
        compose.runOnIdle { assertNull(saved) }
        clickInSheet("weight-save")
        compose.runOnIdle { assertEquals(69.6, requireNotNull(saved), 0.0) }
        clickInSheet("weight-sheet-cancel")
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun invalidPrecisionAndNonNumbersCannotBeCommitted() {
        var saved: Double? = null
        compose.setContent {
            ReadyGymTheme {
                WeightValueSheet("记录体重", 70.0, isTarget = false,
                    onDismiss = {}, onSave = { _, kg -> saved = kg })
            }
        }
        awaitExpandedSheet()
        for (invalid in listOf("70.12", "NaN", "70,1", "29.9", "250.1")) {
            compose.onNodeWithTag("weight-input").performTextReplacement(invalid)
            compose.onNodeWithTag("weight-save").assertIsNotEnabled()
            compose.runOnIdle { assertNull(saved) }
        }
        compose.onNodeWithTag("weight-input").performTextReplacement("70.1")
        clickInSheet("weight-save")
        compose.runOnIdle { assertEquals(70.1, requireNotNull(saved), 0.0) }
    }

    @Test fun editingKeepsTheOriginalDateAndRefusesFutureDates() {
        val future = LocalDate.now().plusDays(1)
        var saved: LocalDate? = null
        compose.setContent {
            ReadyGymTheme {
                WeightValueSheet("修改称重", 70.0, isTarget = false, initialDate = future,
                    hasExistingEntry = true, onDismiss = {}, onSave = { date, _ -> saved = date })
            }
        }
        awaitExpandedSheet()
        compose.onNodeWithTag("weight-date").assertIsNotEnabled()
        compose.onNodeWithTag("weight-save").assertIsNotEnabled()
        compose.runOnIdle { assertNull(saved) }
    }

    @Test fun deletingARecordRequiresItsOwnConfirmationAndCanBeCanceled() {
        val entry = WeightEntry(LocalDate.now(), 70.0)
        var deleted: WeightEntry? = null
        compose.setContent {
            ReadyGymTheme {
                WeightHistorySheet(listOf(entry), onDismiss = {}, onEdit = {}, onDelete = { deleted = it })
            }
        }
        awaitExpandedSheet()
        clickInSheet("weight-history-delete-${entry.date}")
        compose.runOnIdle { assertNull(deleted) }
        clickInSheet("weight-delete-cancel")
        compose.runOnIdle { assertNull(deleted) }
        clickInSheet("weight-history-delete-${entry.date}")
        clickInSheet("weight-delete-confirm")
        compose.runOnIdle { assertEquals(entry, deleted) }
    }
}
