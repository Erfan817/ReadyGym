package com.readygym.core.workout

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RecoveryCheckInTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 11, 2)
    private fun session(id: Long, date: LocalDate = day.minusDays(1)) = WorkoutSession(id, 1, 0, "训练",
        date.atStartOfDay(zone).toInstant().toEpochMilli(), date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
        listOf(SetLog("row", 0, reps = 10, done = true)))

    @Test fun asksAfterThreeRealWorkoutsAndWaitsUntilTheNextLocalDay() {
        val history = listOf(session(1), session(2), session(3, day))
        assertNull(recoveryCheckInDue(history.take(2), 0, day, zone))
        assertNull(recoveryCheckInDue(history, 0, day, zone))
        assertEquals(3L, recoveryCheckInDue(history, 0, day.plusDays(1), zone)?.id)
    }

    @Test fun skipOrSubmissionStartsAnotherThreeWorkoutCycle() {
        val history = (1L..5L).map { session(it, day.minusDays(6 - it)) }
        assertNull(recoveryCheckInDue(history, 3, day, zone))
        assertEquals(6L, recoveryCheckInDue(history + session(6), 3, day, zone)?.id)
    }

    @Test fun unfinishedAndEmptySessionsDoNotCountAndDatesUseTheGivenTimezone() {
        val history = listOf(session(1), session(2), session(3).copy(endedAtMillis = null), session(4).copy(sets = emptyList()))
        assertNull(recoveryCheckInDue(history, 0, day, zone))
        val midnight = day.atTime(0, 30).atZone(zone).toInstant().toEpochMilli()
        val third = session(5).copy(endedAtMillis = midnight)
        assertNull(recoveryCheckInDue(history + third, 0, day, zone))
        assertNotNull(recoveryCheckInDue(history + third, 0, day, ZoneId.of("UTC")))
    }
}
