package com.readygym.core.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class BadgeRulesTest {
    private val zone = ZoneOffset.UTC
    private var nextId = 1L

    private fun session(at: LocalDateTime, allDone: Boolean = true, kg: Double? = null): WorkoutSession {
        val start = at.toInstant(zone).toEpochMilli()
        val sets = listOf(SetLog("goblet-squat", 0, reps = 10, loadKg = kg, done = true),
            SetLog("goblet-squat", 1, reps = 10, loadKg = kg, done = allDone))
        return WorkoutSession(nextId++, 1, 0, "下肢", start, start + 40 * 60_000, sets)
    }

    // 2026-10-19 is a Monday.
    private fun day(d: Int, hour: Int = 18) = LocalDateTime.of(2026, 10, d, hour, 0)

    @Test fun everyWorkoutGetsAStampAndMilestonesUnlockOnce() {
        val history = (0 until 5).map { session(day(19 + it), allDone = it != 0) }
        val awards = BadgeRules.awards(history, daysPerWeek = 3, zone = zone)
        assertEquals(listOf(1, 2, 3, 4, 5), awards.map { it.ordinal })
        assertEquals(listOf(Badge.FIRST), awards[0].unlocked)
        assertEquals(listOf(Badge.FULL_CLEAR), awards[1].unlocked)
        assertEquals(listOf(Badge.WEEK_GOAL), awards[2].unlocked)
        assertTrue("nothing new on the 4th", awards[3].unlocked.isEmpty())
        assertEquals(listOf(Badge.COUNT_5), awards[4].unlocked)
    }

    @Test fun timeOfDayStreakAndTonnage() {
        val history = listOf(
            session(day(5, hour = 6), kg = 30.0), session(day(7, hour = 22), kg = 30.0),   // week of Oct 5
            session(day(13), kg = 0.0), session(day(15)),                                  // week of Oct 12
            session(day(20)), session(day(22)),                                            // week of Oct 19
        )
        val earned = BadgeRules.earned(history, daysPerWeek = 2, zone = zone)
        assertTrue(Badge.EARLY_BIRD in earned)
        assertTrue(Badge.NIGHT_OWL in earned)
        assertTrue(Badge.STREAK_3W in earned)
        assertTrue("2 × 2 sets × 10 reps × 30 kg = 1200 kg", Badge.TONNAGE_1T in earned)
    }

    @Test fun weeksStartOnMonday() {
        assertEquals(LocalDate.of(2026, 10, 19), BadgeRules.weekOf(LocalDate.of(2026, 10, 25)))
        assertEquals(LocalDate.of(2026, 10, 19), BadgeRules.weekOf(LocalDate.of(2026, 10, 19)))
    }
}
