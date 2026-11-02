package com.readygym.app

import com.readygym.core.workout.WorkoutSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** Weekly progress follows local Monday boundaries and the existing completed-session count. */
class WeeklyTrainingProgressTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val sunday = LocalDate.of(2026, 10, 25)

    private fun session(start: String, finished: Boolean = true): WorkoutSession {
        val millis = Instant.parse(start).toEpochMilli()
        return WorkoutSession(
            planVersion = 1,
            dayIndex = 0,
            dayTitle = "上肢训练",
            startedAtMillis = millis,
            endedAtMillis = if (finished) millis + 600_000 else null,
        )
    }

    @Test fun mondayStartsTheLocalWeekAndUnfinishedWorkoutsDoNotCount() {
        val history = listOf(
            session("2026-10-18T15:59:00Z"), // Sunday 23:59, previous week.
            session("2026-10-18T16:00:00Z"), // Monday 00:00, this week.
            session("2026-10-25T15:00:00Z"), // Sunday 23:00, this week.
            session("2026-10-25T16:00:00Z"), // Monday 00:00, next week.
            session("2026-10-21T10:00:00Z", finished = false),
        )

        assertEquals(2, weeklyTrainingCount(history, sunday, shanghai))
    }

    @Test fun calendarWeekUsesTheUsersZoneRatherThanTheUtcDate() {
        val history = listOf(session("2026-10-18T18:00:00Z"))

        assertEquals(1, weeklyTrainingCount(history, sunday, shanghai))
        assertEquals(0, weeklyTrainingCount(history, sunday, ZoneId.of("UTC")))
    }

    @Test fun eachCompletedSessionCountsEvenWhenTwoWereOnTheSameDay() {
        val history = listOf(
            session("2026-10-20T01:00:00Z"),
            session("2026-10-20T10:00:00Z"),
        )

        assertEquals(2, weeklyTrainingCount(history, sunday, shanghai))
        assertEquals(0, weeklyTrainingCount(emptyList(), sunday, shanghai))
    }
}
