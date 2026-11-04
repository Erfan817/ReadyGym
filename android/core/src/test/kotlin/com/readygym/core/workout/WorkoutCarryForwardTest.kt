package com.readygym.core.workout

import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import org.junit.Assert.*
import org.junit.Test

class WorkoutCarryForwardTest {
    @Test fun sparseCompletedGroupsKeepTheirIndicesAndActualRepsAndLoad() {
        val day = PlanDay("训练", items = listOf(PlanItem("row", 3, 8, 12, loadKg = 10.0)))
        val actual = listOf(SetLog("row", 2, reps = 7, loadKg = 14.5, done = true),
            SetLog("row", 0, reps = 99, loadKg = 99.0, done = false))
        val sets = WorkoutSession.start(1, 0, day, 0, mapOf("row" to actual)).sets
        assertEquals(listOf(12, 12, 7), sets.map { it.reps })
        assertEquals(listOf(10.0, 10.0, 14.5), sets.map { it.loadKg })
        assertTrue(sets.none { it.done })
    }

    @Test fun timedGroupsCarrySecondsButNeverRepsFromADifferentMeasure() {
        val day = PlanDay("核心", items = listOf(PlanItem("plank", 2, seconds = 30)))
        val actual = listOf(SetLog("plank", 0, seconds = 47, done = true), SetLog("plank", 1, reps = 8, done = true))
        val sets = WorkoutSession.start(1, 0, day, 0, mapOf("plank" to actual)).sets
        assertEquals(listOf(47, 30), sets.map { it.seconds })
        assertTrue(sets.all { it.reps == null })
    }
}
