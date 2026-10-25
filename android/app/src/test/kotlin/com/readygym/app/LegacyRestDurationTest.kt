package com.readygym.app

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.readygym.app.data.GymDatabase
import com.readygym.app.data.GymRepository
import com.readygym.app.data.PlanVersionEntity
import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import com.readygym.core.ai.ChatModel
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.exercise.MusclePlanEdit
import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile
import com.readygym.core.workout.WorkoutSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Seeds the bytes saved by older apps, rather than relying on the new plan-write defaults. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyRestDurationTest {
    private var now = 1_000_000L
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GymDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repo = GymRepository(db) { now }
    private val store = ViewModelStore()
    private val library = ExerciseLibrary.parse(File("src/main/assets/exercise-library.json").readText())
    private val profile = UserProfile(24, 175.0, 70.0, Gender.MALE, Goal.MUSCLE, Location.HOME,
        setOf(Equipment.DUMBBELL), 2, 45, Experience.BEGINNER)
    private val legacy = WorkoutPlan(7, 123L, "旧计划", "每周两练", 2, listOf(
        PlanDay("下肢", "腿和核心", listOf(
            PlanItem("goblet-squat", 2, 8, 12, loadKg = 12.0, restSeconds = 90, note = "慢慢下放"),
            PlanItem("plank", 2, seconds = 20, restSeconds = 90),
        )),
        PlanDay("上肢", "推拉", listOf(
            PlanItem("push-up", 2, 8, 12, restSeconds = 120),
            PlanItem("dumbbell-row", 2, 8, 12, loadKg = 8.0, restSeconds = 90),
        )),
    ), progressionBaseVersion = 3)

    @After fun close() {
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
        // Let canceled Room queries return before closing the connection used by their collectors.
        Thread.sleep(150)
        shadowOf(Looper.getMainLooper()).idle()
        db.close()
    }

    private suspend fun seedLegacyPlan() {
        repo.saveProfile(profile)
        db.dao().insertPlan(PlanVersionEntity(legacy.version, legacy.createdAtMillis, legacy.reason, legacy.toJson()))
    }

    private fun viewModel(): GymViewModel = ViewModelProvider(store, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GymViewModel(repo, library, apiKey = { null },
                modelFor = { ChatModel { error("This regression test must not call an AI service") } },
                io = Dispatchers.IO, clock = { now }) as T
    })[GymViewModel::class.java]

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    @Test fun legacyActivePlanUsesThirtySecondsWithoutChangingItsVersionOrHistory() = runBlocking {
        seedLegacyPlan()
        val finished = repo.startSession(legacy, 0)
        repo.finishSession(finished.id)
        val recorded = repo.history.first()

        val active = repo.activePlan.first()!!
        assertTrue(active.days.flatMap { it.items }.all { it.restSeconds == 30 })
        assertEquals(legacy.version, active.version)
        assertEquals(legacy.progressionBaseVersion, active.progressionBaseVersion)
        assertEquals(1, repo.nextDayIndex(active))
        assertEquals(legacy, repo.planVersion(7))
        assertEquals(listOf(legacy), repo.planHistory.first())
        assertEquals(recorded, repo.history.first())

        val afterRestart = GymRepository(db).activePlan.first()!!
        assertEquals(active, afterRestart)
        assertEquals(1, GymRepository(db).nextDayIndex(afterRestart))
    }

    @Test fun addingAnExerciseAndSavingANewPlanCannotRestoreNinetySecondRests() = runBlocking {
        seedLegacyPlan()
        val finished = repo.startSession(legacy, 0)
        repo.finishSession(finished.id)
        val open = repo.startSession(legacy, 1)

        val added = repo.addExerciseToPlan("dumbbell-curl", 1, 7, profile, library) as MusclePlanEdit.Added
        assertEquals(8, added.plan.version)
        assertTrue(added.plan.days.flatMap { it.items }.all { it.restSeconds == 30 })
        assertEquals(legacy.progressionBaseVersion, added.plan.progressionBaseVersion)
        assertEquals(1, repo.nextDayIndex(added.plan))
        assertEquals(open, repo.openSession.first())
        assertEquals(legacy, repo.planVersion(7))
        assertEquals("dumbbell-curl", added.plan.days[1].items.last().exerciseId)

        val saved = repo.savePlan(legacy.copy(reason = "重新生成"))
        assertTrue(saved.days.flatMap { it.items }.all { it.restSeconds == 30 })
        assertEquals(saved, repo.planVersion(saved.version))
    }

    @Test fun resumedLegacyWorkoutStartsThirtySecondRestsAndKeepsLoggedSets() {
        val open = runBlocking {
            seedLegacyPlan()
            val session = repo.startSession(legacy, 0)
            val logged = session.sets.mapIndexed { index, set ->
                if (index == 0) set.copy(reps = 9, loadKg = 14.0, done = true) else set
            }
            repo.updateSets(session.id, logged)
            repo.session(session.id)!!
        }
        val vm = viewModel()
        await("resumed session") { vm.workout?.id == open.id && vm.activePlan.value.valueOrNull() != null }
        assertEquals(open, vm.workout)
        assertNull(vm.restEndsAt)
        assertEquals(7, vm.workout!!.planVersion)

        val second = vm.workout!!.sets[1]
        vm.updateSet(1, second.copy(reps = 11, loadKg = 14.0, done = true))
        assertEquals(30, vm.restTotalSeconds)
        assertEquals(now + 30_000L, vm.restEndsAt)
        vm.addRest(30)
        assertEquals(60, vm.restTotalSeconds)
        assertEquals(now + 60_000L, vm.restEndsAt)
        vm.skipRest()
        assertNull(vm.restEndsAt)

        now += 5_000L
        vm.updateSet(2, vm.workout!!.sets[2].copy(done = true))
        assertEquals("a new set must reset the timer, including after an extension", 30, vm.restTotalSeconds)
        assertEquals(now + 30_000L, vm.restEndsAt)
        val expectedSets = vm.workout!!.sets

        var finished: WorkoutSession? = null
        vm.finishWorkout { finished = it }
        await("finished workout") { finished != null }
        assertNotNull(finished!!.endedAtMillis)
        assertEquals(open.id, finished!!.id)
        assertEquals(legacy.version, finished!!.planVersion)
        assertEquals(expectedSets, finished!!.sets)
        assertEquals(9, finished!!.sets[0].reps)
        assertEquals(11, finished!!.sets[1].reps)
        assertEquals(14.0, finished!!.sets[0].loadKg!!, 0.0)
        assertEquals(280.0, finished!!.volumeKg, 0.0)
        runBlocking {
            assertEquals(listOf(finished!!), repo.history.first())
            assertNull(repo.openSession.first())
            assertEquals(legacy, repo.planVersion(7))
        }
    }
}
