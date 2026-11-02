package com.readygym.core.workout

import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.WorkoutPlan
import org.junit.Assert.*
import org.junit.Test

class SessionPlanUpdateTest {
    private val day = PlanDay("训练", items = listOf(PlanItem("a", 3, 8, 12, loadKg = 10.0), PlanItem("b", 2, seconds = 20)))
    private fun plan(next: PlanDay) = WorkoutPlan(2, 200, "换动作", "", 1, listOf(next))
    private val open = WorkoutSession.start(1, 0, day, 100).copy(id = 4)

    @Test fun changesUnfinishedTargetsWhileKeepingCompletedActuals() {
        val logged = open.copy(sets = open.sets.mapIndexed { i, s -> if (i == 0) s.copy(done = true, loadKg = 13.0, reps = 9) else s })
        val next = day.copy(items = listOf(day.items[0].copy(sets = 2, repsMin = 6, repsMax = 8, loadKg = 8.0), day.items[1]))
        val updated = logged.withUpdatedPlan(plan(next), day)
        assertEquals(logged.completedSets, updated.completedSets)
        assertEquals(1, updated.sets.count { it.exerciseId == "a" && !it.done })
        assertEquals(SetLog("a", 1, reps = 8, loadKg = 8.0), updated.sets[1])
        assertEquals(2, updated.planVersion)
        assertEquals(open.id, updated.id)
    }

    @Test fun reducingSetCountBelowCompletedCountNeverLosesCompletedWork() {
        val logged = open.copy(sets = open.sets.map { if (it.exerciseId == "a") it.copy(done = true) else it })
        val updated = logged.withUpdatedPlan(plan(day.copy(items = listOf(day.items[0].copy(sets = 1), day.items[1]))), day)
        assertEquals(logged.completedSets, updated.completedSets)
        assertEquals(3, updated.sets.count { it.exerciseId == "a" })
    }

    @Test fun sameCountReplacementDoesNotInheritOldWeightOrManualEdits() {
        val logged = open.copy(sets = open.sets.map { if (it.exerciseId == "a") it.copy(loadKg = 30.0) else it.copy(seconds = 33) })
        val next = day.copy(items = listOf(PlanItem("c", 3, 8, 12), day.items[1]))
        val updated = logged.withUpdatedPlan(plan(next), day)
        assertEquals(open.sets.size, updated.sets.size)
        assertTrue(updated.sets.filter { it.exerciseId == "c" }.all { it.loadKg == null && !it.done })
        assertTrue(updated.sets.filter { it.exerciseId == "b" }.all { it.seconds == 33 })
    }

    @Test fun endedSessionAndFullyCompletedSessionAreNeverRewritten() {
        val next = plan(day.copy(title = "新安排"))
        val ended = open.copy(endedAtMillis = 300)
        val completed = open.copy(sets = open.sets.map { it.copy(done = true) })
        assertEquals(ended, ended.withUpdatedPlan(next, day))
        assertEquals(completed, completed.withUpdatedPlan(next, day))
    }
}
