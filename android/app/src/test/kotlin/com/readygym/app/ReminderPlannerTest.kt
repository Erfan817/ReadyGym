package com.readygym.app

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The reminder clock math. Fixed instants in a fixed zone (Asia/Shanghai has no DST, which is also
 * the realistic locale for this app); the boundaries that matter are "later today", "time already
 * passed today" and "wrap around the week".
 */
class ReminderPlannerTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    /** All cases are phrased relative to one verified Monday, so a wrong anchor fails on its own. */
    private val monday = LocalDate.of(2026, 10, 19)

    @Test fun anchorIsAMonday() {
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
    }

    private fun millis(date: LocalDate, time: String): Long = LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()
    private fun settings(days: Set<Int>, hour: Int = 19, minute: Int = 0, enabled: Boolean = true) =
        ReminderSettings(enabled, hour, minute, days)

    @Test fun disabledOrDaylessReminderNeverFires() {
        val now = millis(monday, "08:00:00")
        assertNull(ReminderPlanner.nextTriggerMillis(now, zone, settings(setOf(1, 3, 5), enabled = false)))
        assertNull(ReminderPlanner.nextTriggerMillis(now, zone, settings(emptySet())))
    }

    @Test fun firesLaterTodayWhenTheTimeIsStillAhead() {
        val now = millis(monday, "08:00:00")
        val next = ReminderPlanner.nextTriggerMillis(now, zone, settings(setOf(1, 3, 5)))
        assertEquals(millis(monday, "19:00:00"), next)
    }

    @Test fun rollsToTheNextChosenDayWhenTodayIsAlreadyPast() {
        val now = millis(monday, "20:00:00") // the 19:00 slot is gone
        val next = ReminderPlanner.nextTriggerMillis(now, zone, settings(setOf(1, 3, 5)))
        assertEquals(millis(monday.plusDays(2), "19:00:00"), next) // Wednesday
    }

    @Test fun skipsDaysThatAreNotChosen() {
        val now = millis(monday, "20:00:00")
        val next = ReminderPlanner.nextTriggerMillis(now, zone, settings(setOf(3, 5)))
        assertEquals(millis(monday.plusDays(2), "19:00:00"), next) // straight to Wednesday
    }

    @Test fun wrapsAroundTheWeekToTheSingleChosenDay() {
        val now = millis(monday.plusDays(1), "20:00:00") // Tuesday evening
        val next = ReminderPlanner.nextTriggerMillis(now, zone, settings(setOf(1)))
        assertEquals(millis(monday.plusDays(7), "19:00:00"), next) // next Monday
    }

    @Test fun firingExactlyNowCountsAsPassedAndMovesToTheNextOccurrence() {
        val now = millis(monday, "19:00:00")
        val next = ReminderPlanner.nextTriggerMillis(now, zone, settings(setOf(1, 3, 5)))
        assertEquals(millis(monday.plusDays(2), "19:00:00"), next)
    }

    @Test fun everyWeekdayIsReachableWhenAllDaysAreChosen() {
        (0L..6L).forEach { offset ->
            val now = millis(monday.plusDays(offset), "20:00:00")
            val next = ReminderPlanner.nextTriggerMillis(now, zone, settings((1..7).toSet()))
            assertEquals(monday.plusDays(offset + 1).atTime(19, 0), LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(next!!), zone))
        }
    }
}
