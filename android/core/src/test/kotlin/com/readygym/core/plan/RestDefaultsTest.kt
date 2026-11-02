package com.readygym.core.plan

import org.junit.Assert.assertEquals
import org.junit.Test

class RestDefaultsTest {
    @Test fun normalizingALegacyPlanOnlyChangesRestAndIsIdempotent() {
        val legacy = WorkoutPlan(7, 123L, "旧计划", "每周两练", 2, listOf(
            PlanDay("下肢", "腿和核心", listOf(
                PlanItem("goblet-squat", 3, 8, 12, loadKg = 12.0, restSeconds = 90, note = "控制下放"),
                PlanItem("plank", 2, seconds = 40, restSeconds = 60),
            )),
            PlanDay("上肢", "推拉", listOf(
                PlanItem("push-up", 2, 8, 12, restSeconds = 120),
                PlanItem("dumbbell-row", 2, 10, 10, loadKg = 8.0, restSeconds = 30),
            )),
        ), progressionBaseVersion = 3)

        val normalized = legacy.withDefaultRest()
        val expected = legacy.copy(days = legacy.days.map { day ->
            day.copy(items = day.items.map { it.copy(restSeconds = 30) })
        })
        assertEquals(expected, normalized)
        assertEquals(normalized, normalized.withDefaultRest())
        assertEquals(listOf(90, 60, 120, 30), legacy.days.flatMap { it.items }.map { it.restSeconds })
        assertEquals(normalized, WorkoutPlan.fromJson(normalized.toJson()))
    }
}
