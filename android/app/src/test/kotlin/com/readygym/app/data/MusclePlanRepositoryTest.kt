package com.readygym.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.exercise.MusclePlanEdit
import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile
import java.io.File
import kotlinx.coroutines.async
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
class MusclePlanRepositoryTest {
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

    @Test fun additionCommitsANewVersionAndLeavesExistingSessionAndHistoryUntouched() = runTest {
        repo.saveProfile(profile)
        val original = repo.savePlan(template)
        val open = repo.startSession(original, 1)
        val result = repo.addExerciseToPlan("dumbbell-curl", 1, original.version, profile, library) as MusclePlanEdit.Added
        assertEquals(2, result.plan.version)
        assertEquals(result.plan, repo.activePlan.first())
        assertEquals(original, repo.planVersion(1))
        assertEquals(open, repo.openSession.first())
        assertEquals(original.days[1].items.size + 1, result.plan.days[1].items.size)
        assertEquals(2, repo.planHistory.first().size)
        // A fresh repository sees the persisted addition; it is not merely a ViewModel update.
        assertEquals("dumbbell-curl", GymRepository(db).activePlan.first()!!.days[1].items.last().exerciseId)
    }

    @Test fun manualVersionsContinueTheTrainingRotationAcrossRestartAndAiReplacementResetsIt() = runTest {
        repo.saveProfile(profile)
        val original = repo.savePlan(template)
        repo.finishSession(repo.startSession(original, 0).id)
        assertEquals(1, repo.nextDayIndex(original))
        val added = (repo.addExerciseToPlan("dumbbell-curl", 1, 1, profile, library) as MusclePlanEdit.Added).plan
        assertEquals(1, GymRepository(db).nextDayIndex(added))
        now += 1_000
        repo.finishSession(repo.startSession(added, 1).id)
        val addedAgain = (repo.addExerciseToPlan("side-plank", 0, 2, profile, library) as MusclePlanEdit.Added).plan
        assertEquals(0, repo.nextDayIndex(addedAgain))
        now += 1_000
        repo.finishSession(repo.startSession(addedAgain, 0).id)
        assertEquals(1, repo.nextDayIndex(addedAgain))
        val replacement = repo.savePlan(addedAgain.copy(reason = "教练调整"))
        assertNull(replacement.progressionBaseVersion)
        assertEquals(0, repo.nextDayIndex(replacement))
    }

    @Test fun staleVersionOrProfileCannotOverwriteAnUpdatedPlan() = runTest {
        repo.saveProfile(profile)
        repo.savePlan(template)
        val updated = (repo.addExerciseToPlan("dumbbell-curl", 1, 1, profile, library) as MusclePlanEdit.Added).plan
        assertTrue(repo.addExerciseToPlan("side-plank", 0, 1, profile, library) is MusclePlanEdit.Rejected)
        repo.saveProfile(profile.copy(equipment = setOf(Equipment.BODYWEIGHT)))
        assertTrue(repo.addExerciseToPlan("side-plank", 0, 2, profile, library) is MusclePlanEdit.Rejected)
        assertEquals(updated, repo.activePlan.first())
        assertEquals(2, repo.planHistory.first().size)
    }

    @Test fun concurrentSubmissionsAppendAtMostOnceAndNeverLoseAnEdit() = runTest {
        repo.saveProfile(profile)
        repo.savePlan(template)
        val first = async { repo.addExerciseToPlan("dumbbell-curl", 1, 1, profile, library) }
        val second = async { repo.addExerciseToPlan("side-plank", 0, 1, profile, library) }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it is MusclePlanEdit.Added })
        assertEquals(1, results.count { it is MusclePlanEdit.Rejected })
        assertEquals(2, repo.planHistory.first().size)
        assertEquals(template.days.sumOf { it.items.size } + 1, repo.activePlan.first()!!.days.sumOf { it.items.size })
    }

    @Test fun directWriteCannotBypassSafetyOrRequireMissingProfileAndPlan() = runTest {
        assertTrue(repo.addExerciseToPlan("dumbbell-curl", 1, 1, profile, library) is MusclePlanEdit.Rejected)
        repo.saveProfile(profile)
        assertTrue(repo.addExerciseToPlan("dumbbell-curl", 1, 1, profile, library) is MusclePlanEdit.Rejected)
        val gym = profile.copy(location = Location.GYM, equipment = Equipment.entries.toSet())
        repo.saveProfile(gym)
        repo.savePlan(template)
        assertTrue(repo.addExerciseToPlan("barbell-bench-press", 1, 1, gym, library) is MusclePlanEdit.Rejected)
        assertTrue(repo.addExerciseToPlan("push-up", 1, 1, gym, library) is MusclePlanEdit.Rejected)
        assertEquals(1, repo.planHistory.first().size)
    }
}
