package com.readygym.core.exercise

import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import com.readygym.core.MuscleGroup
import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.PlanValidator
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MuscleExercisePickerTest {
    private val library = ExerciseLibrary.parse(File("../app/src/main/assets/exercise-library.json").readText())
    private val profile = UserProfile(24, 175.0, 70.0, Gender.MALE, Goal.MUSCLE, Location.HOME,
        setOf(Equipment.DUMBBELL), 2, 45, Experience.BEGINNER)
    private val plan = WorkoutPlan(3, 123, "原计划", "每周两练", 2, listOf(
        PlanDay("下肢", items = listOf(PlanItem("goblet-squat", 2, 8, 12), PlanItem("plank", 2, seconds = 20))),
        PlanDay("上肢", items = listOf(PlanItem("push-up", 2, 8, 12), PlanItem("dumbbell-row", 2, 8, 12))),
    ))

    @Test fun recommendationsUseTheSameEligibilityAsTheCoach() {
        val allowed = library.availableFor(profile.location, profile.equipment, profile.experience).map { it.id }.toSet()
        MuscleGroup.entries.forEach { muscle ->
            val choices = MuscleExercisePicker.recommend(library, profile, muscle)
            assertTrue(choices.all { it.id in allowed && (it.primary == muscle || muscle in it.secondary) })
            assertTrue(choices.all { it.gifAsset != null || it.gifPath != null })
            val primaryCount = choices.count { it.primary == muscle }
            assertTrue(choices.take(primaryCount).all { it.primary == muscle })
            assertTrue(choices.drop(primaryCount).none { it.primary == muscle })
        }
        assertTrue(MuscleExercisePicker.recommend(library, profile, MuscleGroup.BICEPS).isNotEmpty())
    }

    @Test fun novicesAndReturningUsersCannotBrowseHighRiskMoves() {
        val gym = profile.copy(location = Location.GYM, equipment = Equipment.entries.toSet())
        listOf< UserProfile?>(null, gym, gym.copy(experience = Experience.RETURNING)).forEach { p ->
            val choices = MuscleGroup.entries.flatMap { MuscleExercisePicker.recommend(library, p, it) }
            assertFalse(choices.any { it.risk == ExerciseRisk.HIGH_RISK })
            if (p?.experience != Experience.RETURNING) assertFalse(choices.any { it.level == ExerciseLevel.ADVANCED })
        }
        assertTrue(MuscleExercisePicker.recommend(library, gym.copy(experience = Experience.EXPERIENCED), MuscleGroup.CHEST)
            .any { it.id == "barbell-bench-press" })
    }

    @Test fun noDemoMeansNoRecommendationAndNoAddition() {
        val noDemo = library["dumbbell-curl"]!!.copy(gifAsset = null, gifPath = null)
        val modified = ExerciseLibrary(1, "", listOf(noDemo))
        assertTrue(MuscleExercisePicker.recommend(modified, profile, noDemo.primary).isEmpty())
        assertTrue(MuscleExercisePicker.addToPlan(modified, profile, plan, noDemo.id, 1) is MusclePlanEdit.Rejected)
    }

    @Test fun conservativeDefaultsRespectTheRealPlanValidator() {
        val allowed = library.availableFor(profile.location, profile.equipment, profile.experience)
        listOf("dumbbell-curl", "side-plank").forEach { id ->
            val item = MuscleExercisePicker.defaultItem(library[id]!!)
            assertEquals(2, item.sets)
            assertEquals(30, item.restSeconds)
            assertNull(item.loadKg)
            val changed = (MuscleExercisePicker.addToPlan(library, profile, plan, id, 1) as MusclePlanEdit.Added).plan
            assertTrue(PlanValidator(library, allowed.map { it.id }.toSet()).problems(changed.days).isEmpty())
            if (library[id]!!.measure == Measure.SECONDS) {
                assertEquals(20, item.seconds)
                assertNull(item.repsMin)
            } else {
                assertEquals(8, item.repsMin)
                assertEquals(12, item.repsMax)
                assertNull(item.seconds)
            }
        }
    }

    @Test fun appendingOnlyChangesTheChosenDayAndPreservesTheRotation() {
        val changed = (MuscleExercisePicker.addToPlan(library, profile, plan, "dumbbell-curl", 1) as MusclePlanEdit.Added).plan
        assertEquals(plan.days[0], changed.days[0])
        assertEquals(plan.days[1].items, changed.days[1].items.dropLast(1))
        assertEquals("dumbbell-curl", changed.days[1].items.last().exerciseId)
        assertEquals(2, plan.days[1].items.size)
        assertEquals(3, changed.progressionBaseVersion)
        val second = (MuscleExercisePicker.addToPlan(library, profile, changed, "side-plank", 0) as MusclePlanEdit.Added).plan
        assertEquals(3, second.progressionBaseVersion)
        assertEquals(second, WorkoutPlan.fromJson(second.toJson()))
    }

    @Test fun duplicatesFullDaysUnknownIdsAndIneligibleMovesAreRejected() {
        listOf("push-up" to 1, "missing" to 1, "dumbbell-curl" to -1, "dumbbell-curl" to 2,
            "barbell-bench-press" to 1).forEach { (id, day) ->
            assertTrue("$id at $day", MuscleExercisePicker.addToPlan(library, profile, plan, id, day) is MusclePlanEdit.Rejected)
        }
        val full = plan.copy(days = listOf(plan.days[0], plan.days[1].copy(items = List(8) { PlanItem("push-up", 2, 8, 12) })))
        assertTrue(MuscleExercisePicker.addToPlan(library, profile, full, "dumbbell-curl", 1) is MusclePlanEdit.Rejected)
    }
}
