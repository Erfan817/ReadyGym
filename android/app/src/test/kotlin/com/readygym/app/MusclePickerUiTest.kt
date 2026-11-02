package com.readygym.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsActions
import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import com.readygym.core.MuscleGroup
import com.readygym.core.exercise.Exercise
import com.readygym.core.exercise.ExerciseLevel
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.exercise.ExercisePattern
import com.readygym.core.exercise.ExerciseRisk
import com.readygym.core.exercise.Measure
import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the selection → demo → chosen day flow without a network or a real AI client. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusclePickerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val pushUp = Exercise(
        id = "push-up", name = "俯卧撑", nameEn = "Push-up", primary = MuscleGroup.CHEST,
        secondary = listOf(MuscleGroup.TRICEPS), equipment = Equipment.BODYWEIGHT,
        locations = setOf(Location.HOME, Location.GYM), pattern = ExercisePattern.HORIZONTAL_PUSH,
        level = ExerciseLevel.BEGINNER, measure = Measure.REPS,
        tips = listOf("收紧腹部，身体保持一条直线"), gifAsset = "exercises/push-up.gif",
    )
    private val library = ExerciseLibrary(2, "", listOf(
        pushUp,
        pushUp.copy(id = "high-risk", name = "高风险卧推", equipment = Equipment.BARBELL,
            risk = ExerciseRisk.HIGH_RISK),
        pushUp.copy(id = "missing-equipment", name = "器械推胸", equipment = Equipment.MACHINE),
        pushUp.copy(id = "advanced", name = "高阶俯卧撑", level = ExerciseLevel.ADVANCED),
        pushUp.copy(id = "missing-gif", name = "没有演示的动作", gifAsset = null),
        pushUp.copy(id = "squat", name = "徒手深蹲", primary = MuscleGroup.QUADRICEPS,
            secondary = emptyList(), pattern = ExercisePattern.SQUAT, gifAsset = "exercises/bodyweight-squat.gif"),
    ))
    private val profile = UserProfile(
        age = 20, heightCm = 175.0, weightKg = 70.0, gender = Gender.MALE,
        goal = Goal.MUSCLE, location = Location.HOME,
        equipment = setOf(Equipment.BODYWEIGHT, Equipment.BARBELL), daysPerWeek = 2,
        minutesPerSession = 30, experience = Experience.BEGINNER,
    )
    private val plan = WorkoutPlan(
        version = 7, createdAtMillis = 1L, reason = "测试计划", summary = "每周两练",
        daysPerWeek = 2, days = listOf(
            PlanDay("全身训练 A", items = listOf(PlanItem("squat", 2, 8, 12), PlanItem("row", 2, 8, 12))),
            PlanDay("全身训练 B", items = listOf(PlanItem("squat", 2, 8, 12), PlanItem("row", 2, 8, 12))),
        ),
    )

    private fun show(
        savedProfile: UserProfile? = profile,
        savedPlan: WorkoutPlan? = plan,
        onOpenPlan: () -> Unit = {},
        onCreateProfile: () -> Unit = {},
        onAdd: suspend (String, Int, Int) -> String? = { _, _, _ -> null },
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                ReadyGymTheme {
                    MuscleExerciseScreen(
                        library = library, profile = savedProfile, plan = savedPlan, nextDay = 0,
                        onClose = {}, onCreateProfile = onCreateProfile, onOpenPlan = onOpenPlan,
                        onAddExercise = onAdd,
                    )
                }
            }
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("muscle-picker-list").performScrollToNode(hasTestTag(tag))
        // The body map plus expanded names can be taller than the viewport. LazyColumn first
        // finds its containing item, then ScrollTo brings the particular control into view.
        compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun selectChestAndOpenDemo(verifyEquipmentFilter: Boolean = true) {
        scrollTo("muscle-name-toggle")
        compose.onNodeWithTag("muscle-name-toggle").performClick()
        scrollTo("muscle-chip-CHEST")
        compose.onNodeWithTag("muscle-chip-CHEST").performClick()
        scrollTo("muscle-exercise-push-up")
        val excluded = listOf("high-risk", "advanced", "missing-gif", "squat") +
            if (verifyEquipmentFilter) listOf("missing-equipment") else emptyList()
        excluded.forEach { id ->
            compose.onNodeWithTag("muscle-exercise-$id").assertDoesNotExist()
        }
        compose.onNodeWithTag("muscle-exercise-push-up").performClick()
        // Let the sheet reach its expanded anchor before measuring its scrolled controls.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun clickSheetAction(tag: String) {
        // ModalBottomSheet owns a separate Android window. Robolectric's synthetic touch
        // coordinates can miss its scrolled buttons; invoke the same accessible click action
        // after proving that the control is visible and enabled in that window.
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    @Test fun addsToChosenDayAndShowsSuccessOnlyAfterTheSaveFinishes() {
        val save = CompletableDeferred<String?>()
        var requested: Triple<String, Int, Int>? = null
        show(onAdd = { id, day, version ->
            requested = Triple(id, day, version)
            save.await()
        })
        selectChestAndOpenDemo()
        clickSheetAction("muscle-day-1")
        clickSheetAction("muscle-add-confirm")
        compose.waitUntil(5_000) { requested != null }
        assertEquals(Triple("push-up", 1, 7), requested)
        compose.onNodeWithTag("muscle-add-success").assertDoesNotExist()
        compose.onNodeWithTag("muscle-add-confirm").assertIsNotEnabled()

        compose.runOnIdle { save.complete(null) }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("muscle-add-success").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("muscle-add-success").assertIsDisplayed()
    }

    @Test fun rejectedSaveKeepsTheDemoOpenAndDoesNotClaimSuccess() {
        val error = "计划已更新，请返回计划页再试。"
        show(onAdd = { _, _, _ -> error })
        selectChestAndOpenDemo()
        clickSheetAction("muscle-add-confirm")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("muscle-add-confirm").assertExists()
        compose.onNodeWithTag("muscle-add-success").assertDoesNotExist()
    }

    @Test fun missingPlanOffersThePlanPageInsteadOfPretendingToAdd() {
        var openedPlan = false
        show(savedPlan = null, onOpenPlan = { openedPlan = true })
        selectChestAndOpenDemo()
        compose.onNodeWithTag("muscle-add-confirm").assertDoesNotExist()
        clickSheetAction("muscle-open-plan")
        compose.waitUntil(5_000) { openedPlan }
        assertEquals(true, openedPlan)
    }

    @Test fun missingProfileOffersProfileCreationWhileTheDemoRemainsBrowsable() {
        var openedProfile = false
        show(savedProfile = null, savedPlan = null, onCreateProfile = { openedProfile = true })
        selectChestAndOpenDemo(verifyEquipmentFilter = false)
        compose.onNodeWithTag("muscle-add-confirm").assertDoesNotExist()
        clickSheetAction("muscle-create-profile")
        compose.waitUntil(5_000) { openedProfile }
        assertEquals(true, openedProfile)
    }

    @Test fun duplicateAndFullDaysCannotBeSelectedOrSubmitted() {
        var calls = 0
        val blockedPlan = plan.copy(days = listOf(
            PlanDay("已有该动作", items = listOf(PlanItem("push-up", 2, 8, 12), PlanItem("squat", 2, 8, 12))),
            PlanDay("动作已满", items = (1..8).map { PlanItem("existing-$it", 2, 8, 12) }),
        ))
        show(savedPlan = blockedPlan, onAdd = { _, _, _ -> calls++; null })
        selectChestAndOpenDemo()
        compose.onNodeWithTag("muscle-day-0").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("muscle-day-1").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("muscle-add-confirm").performScrollTo().assertIsNotEnabled()
        assertEquals(0, calls)
    }
}
