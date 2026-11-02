package com.readygym.core.plan

import com.readygym.core.Equipment
import com.readygym.core.Location
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.workout.WorkoutSession
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlanTest {
    private val library = ExerciseLibrary.parse(File("../app/src/main/assets/exercise-library.json").readText())
    private val homeDumbbell = library.available(Location.HOME, setOf(Equipment.DUMBBELL)).map { it.id }.toSet()
    private val validator = PlanValidator(library, homeDumbbell)

    private val goblet = PlanItem("goblet-squat", 3, 10, 10, loadKg = 12.0, restSeconds = 90)
    private val pushUp = PlanItem("push-up", 3, 12, 12)
    private val press = PlanItem("dumbbell-bench-press", 3, 10, 10, loadKg = 10.0)
    private val plank = PlanItem("plank", 3, seconds = 40)
    private fun plan(v: Int, vararg items: PlanItem) =
        WorkoutPlan(v, 0, "test", "s", 3, listOf(PlanDay("腿 + 推", items = items.toList())))

    @Test fun validPlanHasNoProblems() {
        assertEquals(emptyList<String>(), validator.problems(plan(1, goblet, pushUp, press, plank).days))
    }

    @Test fun rejectsUnknownUnavailableAndOutOfRangeItems() {
        val bad = listOf(PlanDay("x", items = listOf(
            PlanItem("made-up", 3, 10, 10),
            PlanItem("leg-press", 3, 10, 10, loadKg = 80.0),
            PlanItem("goblet-squat", 12, 10, 10),
            PlanItem("plank", 3, 10, 10),
            PlanItem("push-up", 3, 10, 10, loadKg = 5.0),
        )))
        val problems = validator.problems(bad).joinToString("\n")
        assertTrue(problems, "made-up 不在动作库" in problems)
        assertTrue(problems, "倒蹬 不在当前可安排的动作里" in problems)
        assertTrue(problems, "组数 12" in problems)
        assertTrue(problems, "平板支撑 秒数无效" in problems)
        assertTrue(problems, "俯卧撑 是徒手动作" in problems)
    }

    @Test fun rejectsDuplicateAndTooFewItems() {
        assertTrue(validator.problems(listOf(PlanDay("x", items = listOf(goblet, goblet)))).any { "重复" in it })
        assertTrue(validator.problems(listOf(PlanDay("x", items = listOf(goblet)))).any { "动作数" in it })
    }

    @Test fun diffMatchesMockupScenario() {
        val v2 = plan(2, goblet, pushUp, press)
        val v3 = plan(3, goblet.copy(loadKg = 14.0), PlanItem("incline-push-up", 3, 10, 10), press)
        val changes = PlanDiff.between(v2, v3)
        assertEquals(listOf(ChangeKind.LOAD_UP, ChangeKind.REPLACED, ChangeKind.KEPT), changes.map { it.kind })
        assertEquals("push-up", changes[1].before?.exerciseId)
        assertTrue(PlanDiff.hasChanges(v2, v3))
        assertFalse(PlanDiff.hasChanges(v2, v2))
    }

    @Test fun diffReportsAddedRemovedAndVolume() {
        val changes = PlanDiff.between(plan(1, goblet, pushUp, press), plan(2, goblet.copy(sets = 4), press.copy(repsMin = 8, repsMax = 8)))
        assertEquals(listOf(ChangeKind.VOLUME_UP, ChangeKind.VOLUME_DOWN, ChangeKind.REMOVED), changes.map { it.kind })
        val added = PlanDiff.between(plan(1, goblet), plan(2, goblet, plank))
        assertEquals(ChangeKind.ADDED, added.last().kind)
    }

    @Test fun planRoundTripsThroughJson() {
        val p = plan(1, goblet, plank)
        assertEquals(p, WorkoutPlan.fromJson(p.toJson()))
        assertEquals("3 组 × 10 次", goblet.target)
        assertEquals("3 组 × 40 秒", plank.target)
        assertEquals("12 kg", goblet.loadText)
    }

    @Test fun sessionPrefillsSetsAndCarriesLastLoad() {
        val day = PlanDay("d", items = listOf(goblet, plank))
        val last = mapOf("goblet-squat" to listOf(com.readygym.core.workout.SetLog("goblet-squat", 0, 10, loadKg = 16.0)))
        val s = WorkoutSession.start(1, 0, day, 1000, last)
        assertEquals(6, s.sets.size)
        assertEquals(16.0, s.sets[0].loadKg)
        assertEquals(12.0, s.sets[1].loadKg)
        assertEquals(40, s.sets.last().seconds)
        val done = s.copy(sets = s.sets.mapIndexed { i, l -> if (i == 0) l.copy(done = true) else l })
        assertEquals(160.0, done.volumeKg, 0.0)
    }
}
