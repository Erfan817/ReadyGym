package com.readygym.app

import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile
import com.readygym.core.workout.SetLog
import com.readygym.core.workout.WorkoutSession
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePrimaryStateTest {
    private val monday = LocalDate.of(2026, 10, 26)
    private val profile = UserProfile(
        20, 175.0, 70.0, Gender.MALE, Goal.MUSCLE, Location.GYM,
        setOf(Equipment.MACHINE), 3, 40, Experience.BEGINNER,
        trainingDays = setOf(1, 3, 5),
    )
    private val plan = WorkoutPlan(
        5, 0L, "初次生成", "本周训练", 3,
        listOf(
            PlanDay("推的训练", items = listOf(PlanItem("push", 3, 10, 12))),
            PlanDay("腿部训练", items = listOf(PlanItem("legs", 3, 10, 12), PlanItem("calves", 2, 12, 15))),
        ),
    )

    @Test fun firstVisitLeadsToTheCoachBeforePromisingAPlan() {
        val state = homePrimaryState(null, null, null, 0, monday, false)
        assertEquals(HomeDestination.COACH, state.destination)
        assertEquals("和教练聊聊", state.action)
        assertFalse(state.detail.contains("已完成"))
    }

    @Test fun savedProfileWithoutPlanLeadsToPlanGeneration() {
        val state = homePrimaryState(profile, null, null, 0, monday, true)
        assertEquals(HomeDestination.PLAN, state.destination)
        assertEquals("去生成计划", state.action)
        assertTrue(state.hint.contains("确认"))
    }

    @Test fun plannedDayUsesTheNextRotationDayAndItsCounts() {
        val state = homePrimaryState(profile, plan, null, 1, monday, true)
        assertEquals(HomeDestination.TRAINING, state.destination)
        assertEquals("今天的训练", state.label)
        assertEquals("腿部训练", state.title)
        assertEquals("2 个动作 · 5 组", state.detail)
    }

    @Test fun restDayPresentsNextSessionWithoutClaimingItIsTodaysPrescription() {
        val state = homePrimaryState(profile, plan, null, 0, monday.plusDays(1), true)
        assertEquals("下一次训练", state.label)
        assertTrue(state.hint.contains("今天可以休息"))
        assertEquals(HomeDestination.TRAINING, state.destination)
    }

    @Test fun flexibleScheduleDoesNotInventARestDay() {
        val state = homePrimaryState(profile.copy(trainingDays = emptySet()), plan, null, 0, monday, true)
        assertEquals("下一次训练", state.label)
        assertTrue(state.hint.contains("由你安排"))
        assertFalse(state.hint.contains("休息"))
    }

    @Test fun unfinishedSessionKeepsItsOriginalArrangementAfterPlanReplacement() {
        val session = WorkoutSession(
            id = 7, planVersion = 1, dayIndex = 4, dayTitle = "上次的全身训练", startedAtMillis = 0L,
            sets = listOf(
                SetLog("old-a", 0, reps = 10, done = true),
                SetLog("old-a", 1, reps = 10),
                SetLog("old-b", 0, reps = 12),
            ),
        )
        val state = homePrimaryState(profile, plan, session, 1, monday, true)
        assertEquals("上次的全身训练", state.title)
        assertEquals("2 个动作 · 已完成 1/3 组", state.detail)
        assertEquals("继续训练", state.action)
        assertEquals(HomeDestination.TRAINING, state.destination)
    }

    @Test fun resumingTrainingHasPriorityEvenIfProfileIsMissing() {
        val session = WorkoutSession(7, 1, 4, "继续上次", 0L, sets = listOf(SetLog("old-a", 0)))
        val state = homePrimaryState(null, null, session, 0, monday, false)
        assertEquals(HomeDestination.TRAINING, state.destination)
        assertEquals("继续上次", state.title)
    }
}
