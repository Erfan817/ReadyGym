package com.readygym.core.workout

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyTrainingTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 28)

    private fun session(day: LocalDate = today, hour: Int = 19, id: Long = 1): WorkoutSession {
        val end = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        return WorkoutSession(id, 1, 0, "第1天", end - 30 * 60_000L, end,
            listOf(SetLog("squat", 0, done = true), SetLog("squat", 1)))
    }

    @Test fun completedWorkoutBelongsToTodayAcrossAnyPlanVersion() {
        val finished = session().copy(planVersion = 3)
        assertEquals(finished, latestWorkoutOnDate(listOf(finished), today, zone))
    }

    @Test fun nextCalendarDayUnlocksTheNextTrainingEvenBeforeTwentyFourHours() {
        val finished = session(hour = 23)
        assertEquals(finished, latestWorkoutOnDate(listOf(finished), today, zone))
        assertNull(latestWorkoutOnDate(listOf(finished), today.plusDays(1), zone))
    }

    @Test fun overnightWorkoutCountsOnItsCompletionDate() {
        val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val finished = session().copy(startedAtMillis = midnight - 30 * 60_000L, endedAtMillis = midnight + 15 * 60_000L)
        assertEquals(finished, latestWorkoutOnDate(listOf(finished), today, zone))
        assertNull(latestWorkoutOnDate(listOf(finished), today.minusDays(1), zone))
    }

    @Test fun unfinishedAndEmptyEndedSessionsDoNotClaimTodaysTrainingIsComplete() {
        val open = session().copy(endedAtMillis = null)
        val empty = session().copy(sets = listOf(SetLog("squat", 0)))
        assertNull(latestWorkoutOnDate(listOf(open, empty), today, zone))
    }

    @Test fun choosesLatestCompletionWithoutAssumingHistorySortOrder() {
        val old = session(hour = 8, id = 2)
        val latest = session(hour = 20, id = 3)
        assertEquals(latest, latestWorkoutOnDate(listOf(old, session(hour = 12), latest), today, zone))
    }

    @Test fun midnightIsInclusiveAndTomorrowIsExcluded() {
        val midnight = session(hour = 0)
        val tomorrow = session(today.plusDays(1), hour = 0, id = 2)
        assertEquals(midnight, latestWorkoutOnDate(listOf(tomorrow, midnight), today, zone))
    }

    @Test fun sameInstantUsesTheCurrentLocalTimezone() {
        val finished = session(hour = 1)
        val utc = ZoneId.of("UTC")
        assertEquals(finished, latestWorkoutOnDate(listOf(finished), today, zone))
        assertNull(latestWorkoutOnDate(listOf(finished), today, utc))
        assertEquals(finished, latestWorkoutOnDate(listOf(finished), today.minusDays(1), utc))
    }
}
