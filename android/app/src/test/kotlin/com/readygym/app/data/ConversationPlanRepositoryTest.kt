package com.readygym.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationPlanRepositoryTest {
    private var now = 1_000_000L
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GymDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repo = GymRepository(db) { now }
    private val library = ExerciseLibrary.parse(File("src/main/assets/exercise-library.json").readText())
    private val profile = UserProfile(24, 175.0, 70.0, Gender.MALE, Goal.MUSCLE, Location.HOME,
        setOf(Equipment.DUMBBELL), 2, 45, Experience.BEGINNER)
    private val template = WorkoutPlan(0, 0, "初始计划", "两天轮换", 2, listOf(
        PlanDay("下肢", items = listOf(PlanItem("goblet-squat", 2, 8, 12), PlanItem("plank", 2, seconds = 20))),
        PlanDay("上肢", items = listOf(PlanItem("push-up", 2, 8, 12), PlanItem("dumbbell-row", 2, 8, 12))),
    ))

    @After fun close() = db.close()

    private suspend fun seed(): WorkoutPlan {
        repo.saveProfile(profile)
        return repo.savePlan(template)
    }

    private fun alternative(base: WorkoutPlan) = base.copy(reason = "教练聊天调整：替换缺少器械的动作",
        days = base.days.mapIndexed { index, day ->
            if (index == 0) day.copy(items = listOf(PlanItem("dumbbell-lunge", 2, 8, 12), day.items[1])) else day
        })

    @Test fun confirmationAddsOneDurableVersionAndKeepsOpenWorkoutAndOldPlanUntouched() = runTest {
        val original = seed()
        val open = repo.startSession(original, 1)
        repo.updateSets(open.id, open.sets.mapIndexed { i, set -> if (i == 0) set.copy(done = true) else set })
        val before = repo.openSession.first()
        val saved = (repo.applyConversationPlan(original, profile, alternative(original), library)
            as ConversationPlanSaveResult.Saved).plan
        assertEquals(2, saved.version)
        assertEquals(original, repo.planVersion(original.version))
        assertEquals(before, repo.openSession.first())
        assertEquals(saved, GymRepository(db).activePlan.first())
        assertEquals(2, repo.planHistory.first().size)
        assertEquals(profile, repo.profile.first())
    }

    @Test fun confirmationPreservesRotationAndCannotUnlockAnotherWorkoutToday() = runTest {
        val original = seed()
        val session = repo.startSession(original, 0)
        repo.updateSets(session.id, session.sets.map { it.copy(done = true) })
        now += 20 * 60_000L
        repo.finishSession(session.id)
        val history = repo.history.first()
        val saved = (repo.applyConversationPlan(original, profile, alternative(original), library)
            as ConversationPlanSaveResult.Saved).plan
        assertEquals(original.version, saved.progressionBaseVersion)
        assertEquals(1, GymRepository(db).nextDayIndex(saved))
        assertNull(repo.startSessionIfAvailable(saved, 1))
        assertEquals(history, repo.history.first())
        now += 24 * 60 * 60_000L
        assertEquals(1, repo.startSessionIfAvailable(saved, repo.nextDayIndex(saved))!!.dayIndex)
    }

    @Test fun replacementImmediatelyUpdatesOpenSessionAndSurvivesRepositoryReload() = runTest {
        val original = seed()
        val open = repo.startSession(original, 0)
        // A different exercise has already been performed; its exact record must survive.
        val logs = open.sets.map { if (it.exerciseId == "plank" && it.setIndex == 0) it.copy(done = true, seconds = 31) else it }
        repo.updateSets(open.id, logs)
        val saved = (repo.applyConversationPlan(original, profile, alternative(original), library)
            as ConversationPlanSaveResult.Saved).plan
        val updated = GymRepository(db).openSession.first()!!
        assertEquals(open.id, updated.id)
        assertEquals(open.startedAtMillis, updated.startedAtMillis)
        assertEquals(saved.version, updated.planVersion)
        assertEquals(open.sets.size, updated.sets.size)
        assertFalse(updated.sets.any { it.exerciseId == "goblet-squat" })
        assertEquals(2, updated.sets.count { it.exerciseId == "dumbbell-lunge" && !it.done })
        assertEquals(logs.filter { it.done }, updated.completedSets)
        assertEquals(original, repo.planVersion(original.version))
        // A delayed edit from the old screen must not resurrect the old movement.
        repo.updateSets(open.id, logs, open.planVersion)
        assertEquals(updated, repo.openSession.first())
    }

    @Test fun replacingPartiallyCompletedMovementPreservesItsActualLogs() = runTest {
        val original = seed()
        val open = repo.startSession(original, 0)
        val completed = open.sets.first().copy(done = true, reps = 9, loadKg = 17.5)
        repo.updateSets(open.id, open.sets.mapIndexed { i, set -> if (i == 0) completed else set })
        repo.applyConversationPlan(original, profile, alternative(original), library)
        val updated = repo.openSession.first()!!
        assertEquals(listOf(completed), updated.completedSets)
        assertFalse(updated.sets.any { it.exerciseId == "goblet-squat" && !it.done })
        assertTrue(updated.sets.filter { it.exerciseId == "dumbbell-lunge" }.all { it.loadKg == null && !it.done })
    }

    @Test fun allSetsAlreadyDoneAreNotReopenedByAPlanChange() = runTest {
        val original = seed()
        val open = repo.startSession(original, 0)
        repo.updateSets(open.id, open.sets.map { it.copy(done = true) })
        val completed = repo.openSession.first()
        repo.applyConversationPlan(original, profile, alternative(original), library)
        assertEquals(completed, repo.openSession.first())
    }

    @Test fun chainedChatOptionsRetainTheOriginalRotationBase() = runTest {
        val original = seed()
        repo.finishSession(repo.startSession(original, 0).id)
        val first = (repo.applyConversationPlan(original, profile, alternative(original), library)
            as ConversationPlanSaveResult.Saved).plan
        val next = first.copy(days = first.days.mapIndexed { index, day ->
            if (index == 1) day.copy(items = day.items.map { it.copy(sets = 3) }) else day
        }, progressionBaseVersion = 999)
        val second = (repo.applyConversationPlan(first, profile, next, library)
            as ConversationPlanSaveResult.Saved).plan
        assertEquals(original.version, second.progressionBaseVersion)
        assertEquals(1, repo.nextDayIndex(second))
    }

    @Test fun staleProfileOrFullPlanSnapshotCannotOverwriteCurrentData() = runTest {
        val original = seed()
        val tamperedBase = original.copy(summary = "not the reviewed snapshot")
        assertTrue(repo.applyConversationPlan(tamperedBase, profile, alternative(original), library)
            is ConversationPlanSaveResult.Rejected)
        repo.saveProfile(profile.copy(weightKg = 71.0))
        assertTrue(repo.applyConversationPlan(original, profile, alternative(original), library)
            is ConversationPlanSaveResult.Rejected)
        assertEquals(original, repo.activePlan.first())
        assertEquals(1, repo.planHistory.first().size)
    }

    @Test fun repeatedConcurrentConfirmationSavesAtMostOneVersion() = runTest {
        val original = seed()
        val results = List(4) { async { repo.applyConversationPlan(original, profile, alternative(original), library) } }.awaitAll()
        assertEquals(1, results.count { it is ConversationPlanSaveResult.Saved })
        assertEquals(3, results.count { it is ConversationPlanSaveResult.Rejected })
        assertEquals(2, repo.planHistory.first().size)
    }

    @Test fun directWriteRejectsUnknownUnsafeUnavailableDuplicateAndNonFiniteExercises() = runTest {
        val original = seed()
        val invalid = listOf(
            PlanItem("unknown-exercise", 2, 8, 12),
            PlanItem("barbell-bench-press", 2, 8, 12),
            PlanItem("plank", 2, seconds = 20),
            PlanItem("goblet-squat", 2, 8, 12, loadKg = Double.NaN),
            PlanItem("goblet-squat", 2, 8, 12, loadKg = Double.POSITIVE_INFINITY),
            PlanItem("goblet-squat", 99, 8, 12),
        )
        for (item in invalid) {
            val option = original.copy(days = original.days.mapIndexed { index, day ->
                if (index == 0) day.copy(items = listOf(item, day.items[1])) else day
            })
            assertTrue("must reject ${item.exerciseId} / ${item.loadKg}",
                repo.applyConversationPlan(original, profile, option, library) is ConversationPlanSaveResult.Rejected)
        }
        assertEquals(1, repo.planHistory.first().size)
    }

    @Test fun directWriteRejectsMissingEquipmentEvenWhenTheExerciseIsSafe() = runTest {
        val original = seed()
        val bodyweight = profile.copy(equipment = setOf(Equipment.BODYWEIGHT))
        repo.saveProfile(bodyweight)
        assertTrue(repo.applyConversationPlan(original, bodyweight, alternative(original), library)
            is ConversationPlanSaveResult.Rejected)
        assertEquals(original, repo.activePlan.first())
    }

    @Test fun noOpAndTrainingDayCountChangesLeaveTheCurrentVersionIntact() = runTest {
        val original = seed()
        assertTrue(repo.applyConversationPlan(original, profile, original.copy(reason = "only explanation changed"), library)
            is ConversationPlanSaveResult.Rejected)
        assertTrue(repo.applyConversationPlan(original, profile, alternative(original).copy(daysPerWeek = 1), library)
            is ConversationPlanSaveResult.Rejected)
        assertTrue(repo.applyConversationPlan(original, profile, alternative(original).copy(days = original.days.take(1)), library)
            is ConversationPlanSaveResult.Rejected)
        assertEquals(original, repo.activePlan.first())
    }
}
