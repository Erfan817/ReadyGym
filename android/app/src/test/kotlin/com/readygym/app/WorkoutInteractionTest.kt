package com.readygym.app

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.readygym.app.data.GymDatabase
import com.readygym.app.data.GymRepository
import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile
import com.readygym.core.workout.SetLog
import com.readygym.core.workout.WorkoutSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Set logging stays usable after removing rest controls, including with animations removed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var db: GymDatabase? = null
    private val store = ViewModelStore()

    @After fun close() {
        compose.runOnUiThread { compose.activity.setContent { }; store.clear() }
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(150)
        db?.close()
    }

    private fun withLegacyPlan(finished: Boolean = false): GymViewModel {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GymDatabase::class.java).build()
        db = database
        val now = System.currentTimeMillis()
        val repo = GymRepository(database) { now }
        runBlocking {
            repo.saveProfile(UserProfile(24, 175.0, 70.0, Gender.MALE, Goal.MUSCLE, Location.HOME,
                setOf(Equipment.DUMBBELL), 2, 45, Experience.BEGINNER))
            val plan = repo.savePlan(WorkoutPlan(1, now, "旧计划", "训练安排", 2, listOf(
                PlanDay("第一天", items = listOf(PlanItem("test", 2, 8, 12, restSeconds = 90))),
                PlanDay("第二天", items = listOf(PlanItem("next", 2, 8, 12, restSeconds = 120))),
            )))
            val session = repo.startSession(plan, 0)
            if (finished) {
                repo.updateSets(session.id, session.sets.map { it.copy(done = true) })
                repo.finishSession(session.id)
            }
        }
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GymViewModel(repo, ExerciseLibrary(1, "", emptyList()), apiKey = { null },
                    modelFor = { error("This UI regression must not call an AI service") }, clock = { now }) as T
        })[GymViewModel::class.java]
    }

    private fun awaitStoredState(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for stored workout state" }
            Thread.sleep(5)
        }
        compose.waitForIdle()
    }

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

    @Test fun completingAndUndoingASetKeepsTheWorkoutFreeOfRestControls() {
        val vm = withLegacyPlan()
        compose.setContent {
            ReadyGymTheme {
                CompositionLocalProvider(LocalMotionSettings provides MotionSettings.Reduced) {
                    WorkoutScreen(vm, onClose = {}, onFinished = {})
                }
            }
        }
        awaitStoredState { vm.workout != null }
        compose.onNodeWithTag("workout-demo-toggle").performClick()
        val done = compose.onNodeWithTag("set-done-0").performScrollTo()
        done.performClick().assertIsOn()
        compose.onNodeWithTag("workout-exercise-progress").assertTextEquals("1 / 2 组")
        compose.onNodeWithText("组间休息").assertDoesNotExist()
        compose.onNodeWithTag("workout-rest-dock").assertDoesNotExist()
        compose.onNodeWithTag("workout-rest-add").assertDoesNotExist()
        compose.onNodeWithTag("workout-rest-skip").assertDoesNotExist()
        done.performClick().assertIsOff()
        compose.onNodeWithTag("workout-exercise-progress").assertTextEquals("0 / 2 组")
        compose.onNodeWithTag("workout-finish").assertIsDisplayed()
    }

    @Test fun completedPlanHidesStartActionsUntilTheNextDayButRemainsBrowsable() {
        val vm = withLegacyPlan(finished = true)
        compose.setContent {
            ReadyGymTheme {
                PlanScreen(vm, onCreateProfile = {}, onOpenCoach = {}, onStartDay = { error("Same-day start must be unavailable") })
            }
        }
        awaitStoredState { vm.completedToday != null && vm.activePlan.value.valueOrNull() != null }
        compose.onNodeWithText("今天已经训练完了").assertIsDisplayed()
        compose.onNodeWithTag("plan-start-0").assertDoesNotExist()
        compose.onNodeWithTag("plan-start-1").assertDoesNotExist()
        compose.onNodeWithText("第一天").performScrollTo().performClick()
        compose.onNodeWithText("test").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("休息", substring = true).assertDoesNotExist()
        compose.runOnIdle { vm.refreshDate(vm.today.plusDays(1)) }
        compose.onNodeWithTag("plan-completed-today").assertDoesNotExist()
        compose.onNodeWithTag("plan-start-1").performScrollTo().assertIsDisplayed()
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

    @Test fun finishingShowsSummaryWithoutFeedbackAndAllowsVoluntaryRecovery() {
        val vm = withLegacyPlan(finished = true)
        awaitStoredState { vm.history.value.isNotEmpty() }
        compose.runOnIdle { vm.openReview(vm.history.value.first().id) }
        awaitStoredState { vm.reviewing != null }
        var summary by mutableStateOf(true)
        compose.setContent { ReadyGymTheme {
            ReviewScreen(vm, onDone = {}, completionOnly = summary, onRecovery = { summary = false })
        } }
        compose.onNodeWithTag("review-summary").assertIsDisplayed()
        compose.onNodeWithTag("feedback-adjust").assertDoesNotExist()
        compose.onNodeWithTag("completion-done").assertIsDisplayed()
        compose.onNodeWithTag("completion-recovery").performClick()
        compose.onNodeWithText("恢复回访").assertIsDisplayed()
        compose.onNodeWithTag("feedback-skip").assertIsDisplayed()
        compose.onNodeWithTag("review-summary").assertDoesNotExist()
    }

    @Test fun homeCheckInAppearsAfterThreeWorkoutsOnTheNextDayAndSkipKeepsRecords() {
        val vm = withLegacyPlan(finished = true)
        awaitStoredState { vm.history.value.size == 1 && vm.activePlan.value.valueOrNull() != null }
        val repo = GymRepository(db!!)
        val plan = vm.activePlan.value.valueOrNull()!!
        runBlocking {
            repeat(2) {
                val s = repo.startSession(plan, 0)
                repo.updateSets(s.id, s.sets.map { it.copy(done = true) })
                repo.finishSession(s.id)
            }
        }
        awaitStoredState { vm.history.value.size == 3 }
        compose.setContent { ReadyGymTheme { CoachHomeScreen(vm, "测试", true, false, {}, {}) } }
        compose.onNodeWithTag("home-recovery-due").assertDoesNotExist()
        compose.runOnIdle { vm.refreshDate(vm.today.plusDays(1)) }
        compose.onNodeWithTag("home-recovery-due").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("home-recovery-skip").performScrollTo().performClick()
        compose.onNodeWithTag("home-recovery-due").assertDoesNotExist()
        compose.onNodeWithTag("home-recovery-manual").performScrollTo().assertIsDisplayed()
        assertEquals(3, vm.history.value.size)
    }

    @Test fun visibleWorkoutChangesExerciseEvenWhenSessionIdAndSetCountStayTheSame() {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GymDatabase::class.java).build()
        db = database
        val repo = GymRepository(database)
        val library = ExerciseLibrary.parse(java.io.File("src/main/assets/exercise-library.json").readText())
        val profile = UserProfile(24, 175.0, 70.0, Gender.MALE, Goal.MUSCLE, Location.HOME,
            setOf(Equipment.DUMBBELL), 2, 45, Experience.BEGINNER)
        val base = runBlocking {
            repo.saveProfile(profile)
            repo.savePlan(WorkoutPlan(0, 0, "", "", 2, listOf(
                PlanDay("下肢", items = listOf(PlanItem("goblet-squat", 2, 8, 12), PlanItem("plank", 2, seconds = 20))),
                PlanDay("上肢", items = listOf(PlanItem("push-up", 2, 8, 12), PlanItem("dumbbell-row", 2, 8, 12))),
            )))
        }
        runBlocking { repo.startSession(base, 0) }
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GymViewModel(repo, library, apiKey = { null }, modelFor = { error("No network") }) as T
        })[GymViewModel::class.java]
        compose.setContent { ReadyGymTheme { WorkoutScreen(vm, onClose = {}, onFinished = {}) } }
        awaitStoredState { vm.workout != null && vm.activePlan.value.valueOrNull() != null && vm.profile.value.valueOrNull() != null }
        compose.onNodeWithText(library["goblet-squat"]!!.name).assertIsDisplayed()
        val option = base.copy(days = base.days.mapIndexed { i, day ->
            if (i == 0) day.copy(items = listOf(PlanItem("dumbbell-lunge", 2, 6, 8), day.items[1])) else day
        })
        var saved = false
        compose.runOnIdle {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                assertEquals(null, vm.confirmChatPlan(base, profile, option))
                saved = true
            }
        }
        awaitStoredState { saved }
        compose.onNodeWithText(library["dumbbell-lunge"]!!.name).assertIsDisplayed()
        compose.onNodeWithText(library["goblet-squat"]!!.name).assertDoesNotExist()
        compose.onNodeWithTag("workout-exercise-progress").assertTextEquals("0 / 2 组")
        compose.onNodeWithContentDescription("第1组次数").assertTextEquals("8")
    }

    @Test fun restDayIsVisibleAcrossAllThreePagesEvenWithAnOpenWorkout() {
        val vm = withLegacyPlan()
        awaitStoredState { vm.workout != null && vm.profile.value.valueOrNull() != null && vm.activePlan.value.valueOrNull() != null }
        val session = vm.workout!!
        val weekday = java.time.LocalDate.now().dayOfWeek.value
        val profile = vm.profile.value.valueOrNull()!!.copy(trainingDays = setOf(weekday % 7 + 1, (weekday + 1) % 7 + 1))
        runBlocking { GymRepository(db!!).saveProfile(profile) }
        awaitStoredState { vm.profile.value.valueOrNull() == profile }
        var page by mutableStateOf(0)
        compose.setContent { ReadyGymTheme {
            when (page) {
                0 -> CoachHomeScreen(vm, "测试", true, false, {}, {})
                1 -> StartScreen(vm, {}, {}, {})
                else -> PlanScreen(vm, {}, {}, {})
            }
        } }
        compose.onNodeWithText("今天休息").assertIsDisplayed()
        compose.onNodeWithText("训练进行中").assertDoesNotExist()
        compose.onNodeWithTag("home-pending-workout").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { page = 1 }
        compose.onNodeWithText("今天休息").assertIsDisplayed()
        compose.onNodeWithText("未结束的训练").assertIsDisplayed()
        compose.runOnIdle { page = 2 }
        compose.onNodeWithText("今天休息").assertIsDisplayed()
        assertEquals(session, vm.workout)
    }
}
