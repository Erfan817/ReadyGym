package com.readygym.app

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
import com.readygym.core.ai.ChatModel
import com.readygym.core.ai.ConversationPlanDraft
import com.readygym.core.ai.ConversationPlanOption
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationPlanPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    // No test may fetch server media. Bundled media still exercises the actual image component.
    private val library = ExerciseLibrary.parse(File("src/main/assets/exercise-library.json").readText()).let {
        it.copy(exercises = it.exercises.map { exercise -> exercise.copy(gifPath = null) })
    }
    private val store = ViewModelStore()
    private var database: GymDatabase? = null
    private val profile = UserProfile(24, 175.0, 70.0, Gender.MALE, Goal.MUSCLE, Location.HOME,
        setOf(Equipment.DUMBBELL), 2, 45, Experience.BEGINNER)
    private val template = WorkoutPlan(1, 0, "原计划", "两天轮换", 2, listOf(
        PlanDay("原下肢", items = listOf(PlanItem("goblet-squat", 2, 8, 12), PlanItem("plank", 2, seconds = 20))),
        PlanDay("原上肢", items = listOf(PlanItem("push-up", 2, 8, 12), PlanItem("dumbbell-row", 2, 8, 12))),
    ))

    private fun draft(base: WorkoutPlan = template): ConversationPlanDraft {
        val first = base.copy(progressionBaseVersion = base.progressionBaseVersion ?: base.version,
            days = base.days.mapIndexed { index, day -> if (index == 0) day.copy(title = "方案甲下肢",
                items = listOf(PlanItem("dumbbell-lunge", 2, 8, 12), day.items[1])) else day })
        val second = base.copy(progressionBaseVersion = base.progressionBaseVersion ?: base.version,
            days = base.days.mapIndexed { index, day -> if (index == 0) day.copy(title = "方案乙核心",
                items = listOf(day.items[0], PlanItem("side-plank", 2, seconds = 20))) else day })
        return ConversationPlanDraft(base, profile, listOf(
            ConversationPlanOption("使用哑铃弓步", "保留其他动作", first),
            ConversationPlanOption("改用侧平板支撑", "换一种核心训练", second),
        ))
    }

    @After fun close() {
        compose.runOnUiThread { compose.activity.setContent { }; store.clear() }
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(150)
        shadowOf(Looper.getMainLooper()).idle()
        database?.close()
    }

    private fun choices(
        enabled: Boolean = true,
        stale: Boolean = false,
        onConfirm: (Int) -> Unit = {},
        onDismiss: () -> Unit = {},
        reviewed: ConversationPlanDraft = draft(),
        catalog: ExerciseLibrary = library,
    ) {
        compose.setContent {
            ReadyGymTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ConversationPlanChoices(reviewed, catalog, enabled, stale = stale,
                        onConfirm = onConfirm, onDismiss = onDismiss)
                }
            }
        }
    }

    @Test fun newActionDemonstrationsAreVisibleBeforeSelectingOrConfirming() {
        choices(onConfirm = { error("Watching a demo must not save") })
        compose.onNodeWithTag("coach-plan-demo-0-dumbbell-lunge").performScrollTo().assertIsDisplayed()
        awaitStoredState {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("${library["dumbbell-lunge"]!!.name}动作演示"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("${library["dumbbell-lunge"]!!.name}动作演示").assertExists()
        compose.onNodeWithTag("coach-plan-demo-1-side-plank").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("coach-plan-demo-0-goblet-squat").assertDoesNotExist()
        compose.onNodeWithTag("coach-plan-demo-0-plank").assertDoesNotExist()
        compose.onNodeWithTag("coach-plan-confirm").assertDoesNotExist()
    }

    @Test fun multipleNewActionsCanBeBrowsedAndRepeatedDaysShareOneDemo() {
        val changed = template.copy(days = template.days.map { day -> day.copy(items = listOf(
            PlanItem("dumbbell-lunge", 2, 8, 12), PlanItem("side-plank", 2, seconds = 20))) })
        val reviewed = ConversationPlanDraft(template, profile, listOf(ConversationPlanOption("整周调整", "", changed)))
        choices(reviewed = reviewed, onConfirm = { error("Browsing must not save") })
        compose.onNodeWithTag("coach-plan-demo-0-dumbbell-lunge").assertExists()
        compose.onNodeWithTag("coach-plan-demos-0")
            .performScrollToNode(hasTestTag("coach-plan-demo-0-side-plank"))
        compose.onNodeWithTag("coach-plan-demo-0-side-plank").assertExists()
        compose.onNodeWithText("第 1、2 天 · 徒手").assertExists()
        compose.onNodeWithTag("coach-plan-confirm").assertDoesNotExist()
    }

    @Test fun volumeChangesAndRemovalsDoNotShowNewActionDemos() {
        val changed = template.copy(days = template.days.mapIndexed { i, day ->
            if (i == 0) day.copy(items = listOf(day.items[0].copy(sets = 3))) else day
        })
        choices(reviewed = ConversationPlanDraft(template, profile,
            listOf(ConversationPlanOption("调整组数", "", changed))))
        compose.onNodeWithText("新动作演示").assertDoesNotExist()
        compose.onNodeWithTag("coach-plan-select-0").performScrollTo().assertIsDisplayed()
    }

    @Test fun missingMediaKeepsSelectionAvailableAndFailedMediaCanBeRetried() {
        val catalog = library.copy(exercises = library.exercises.map { exercise ->
            when (exercise.id) {
                "dumbbell-lunge" -> exercise.copy(gifAsset = "exercises/not-present.gif")
                "side-plank" -> exercise.copy(gifAsset = null)
                else -> exercise
            }
        })
        choices(catalog = catalog, onConfirm = { error("A media retry must not save") })
        awaitStoredState {
            compose.onAllNodes(androidx.compose.ui.test.hasText("重新加载")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("重新加载").performScrollTo().performClick()
        compose.onNodeWithTag("coach-plan-select-0").performScrollTo().performClick()
        compose.onNodeWithTag("coach-plan-confirm").assertExists()
        compose.onNodeWithText("这个动作暂时没有演示，可以先看动作名称和说明。").assertExists()
    }

    @Test fun selectionIsOnlyAPreviewAndExplicitConfirmationUsesTheSelectedIndex() {
        val confirmations = mutableListOf<Int>()
        choices(onConfirm = { confirmations += it })
        compose.onNodeWithTag("coach-plan-confirm").assertDoesNotExist()
        compose.onNodeWithTag("coach-plan-select-1").performScrollTo().performClick()
        compose.onNodeWithText("第 1 天 · 方案乙核心").performScrollTo().assertIsDisplayed()
        assertTrue(confirmations.isEmpty())
        compose.onNodeWithTag("coach-plan-confirm").performScrollTo().performClick()
        assertEquals(listOf(1), confirmations)
    }

    @Test fun switchingOptionsChangesTheWholeWeekPreviewWithoutSaving() {
        choices(onConfirm = { error("Preview must never save") })
        compose.onNodeWithTag("coach-plan-select-0").performScrollTo().performClick()
        compose.onNodeWithText("第 1 天 · 方案甲下肢").assertExists()
        compose.onNodeWithText("第 2 天 · 原上肢").assertExists()
        compose.onNodeWithTag("coach-plan-select-1").performScrollTo().performClick()
        compose.onNodeWithText("第 1 天 · 方案甲下肢").assertDoesNotExist()
        compose.onNodeWithText("第 1 天 · 方案乙核心").assertExists()
        compose.onNodeWithText("第 2 天 · 原上肢").assertExists()
    }

    @Test fun staleDraftDisablesThePreviouslySelectedConfirmation() {
        var stale by mutableStateOf(false)
        val reviewed = draft()
        compose.setContent {
            ReadyGymTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ConversationPlanChoices(reviewed, library, enabled = !stale, stale = stale,
                        onConfirm = { error("A stale option must not save") }, onDismiss = {})
                }
            }
        }
        compose.onNodeWithTag("coach-plan-select-0").performScrollTo().performClick()
        compose.runOnIdle { stale = true }
        compose.onNodeWithTag("coach-plan-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("coach-plan-select-1").assertIsNotEnabled()
        compose.onNodeWithText("档案或计划刚刚更新了。请告诉教练重新给一组选项，再来确认。").assertExists()
    }

    @Test fun dismissingAnOptionDoesNotConfirmIt() {
        var dismissals = 0
        choices(onConfirm = { error("Dismiss must not save") }, onDismiss = { dismissals++ })
        compose.onNodeWithTag("coach-plan-select-0").performScrollTo().performClick()
        compose.onNodeWithTag("coach-plan-dismiss").performScrollTo().performClick()
        assertEquals(1, dismissals)
    }

    @Test fun realPanelSavesOnlyOnConfirmationAndUpdatesPendingWorkoutSets() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GymDatabase::class.java).build()
        database = db
        val repo = GymRepository(db)
        val base = runBlocking { repo.saveProfile(profile); repo.savePlan(template) }
        val open = runBlocking {
            val session = repo.startSession(base, 0)
            repo.updateSets(session.id, session.sets.mapIndexed { index, set -> if (index == 0) set.copy(done = true) else set })
            requireNotNull(repo.session(session.id))
        }
        val reviewed = draft(base)
        val client = object : CoachClient {
            override suspend fun start() = CoachReply(CoachSession("local-test", "test-TOKEN"), "先选择，再确认。",
                "collecting", conversationPlan = reviewed)
            override suspend fun message(session: CoachSession, text: String): CoachReply = error("No network needed")
        }
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                CoachConversation::class.java -> CoachConversation(client)
                GymViewModel::class.java -> GymViewModel(repo, library, apiKey = { null },
                    modelFor = { ChatModel { error("Saving an existing draft must not call AI") } })
                else -> error("Unexpected ViewModel")
            } as T
        })
        val coach = provider[CoachConversation::class.java]
        val gym = provider[GymViewModel::class.java]
        compose.setContent {
            ReadyGymTheme {
                CompositionLocalProvider(LocalMotionSettings provides MotionSettings.Reduced) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        ConversationPlanPanel(coach, gym)
                    }
                }
            }
        }
        compose.runOnIdle { coach.start() }
        awaitStoredState { coach.reply?.conversationPlan != null && gym.activePlan.value.valueOrNull() == base &&
            gym.profile.value.valueOrNull() == profile && gym.workout == open }
        assertEquals(1, gym.activePlan.value.valueOrNull()!!.version)
        compose.onNodeWithTag("coach-plan-select-0").performScrollTo().performClick()
        assertEquals(1, gym.activePlan.value.valueOrNull()!!.version)
        assertEquals(1, runBlocking { repo.planHistory.first().size })
        compose.onNodeWithTag("coach-plan-confirm").performScrollTo().performClick()
        awaitStoredState { gym.activePlan.value.valueOrNull()?.version == 2 && !coach.busy }
        assertNull(coach.error)
        assertNull(coach.reply?.conversationPlan)
        assertEquals("dumbbell-lunge", gym.activePlan.value.valueOrNull()!!.days[0].items[0].exerciseId)
        assertEquals(open.id, gym.workout!!.id)
        assertEquals(open.completedSets, gym.workout!!.completedSets)
        assertEquals(2, gym.workout!!.planVersion)
        assertTrue(gym.workout!!.sets.any { it.exerciseId == "dumbbell-lunge" && !it.done })
        assertEquals(gym.workout, runBlocking { repo.openSession.first() })
        assertEquals(base, runBlocking { repo.planVersion(1) })
        assertEquals(2, runBlocking { repo.planHistory.first().size })
    }

    private fun awaitStoredState(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the chat plan state" }
            Thread.sleep(5)
        }
        compose.waitForIdle()
    }
}
