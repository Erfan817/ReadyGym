package com.readygym.core.workout

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.readygym.core.profile.UserProfile

/** An unspecified schedule is flexible, not seven rest days. */
fun UserProfile?.isRestDay(today: LocalDate): Boolean =
    this != null && trainingDays.isNotEmpty() && today.dayOfWeek.value !in trainingDays

fun WorkoutSession.startedOn(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean =
    Instant.ofEpochMilli(startedAtMillis).atZone(zone).toLocalDate() == today

fun UserProfile.weekScheduleText(): String {
    if (trainingDays.isEmpty()) return "训练时间由你安排"
    val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val training = trainingDays.sorted().joinToString("、") { labels[it - 1] }
    val rest = (1..7).filterNot { it in trainingDays }.joinToString("、") { labels[it - 1] }
    return "训练：$training" + if (rest.isEmpty()) "" else "；休息：$rest"
}

/** Completion belongs to the local day it finished, including workouts spanning midnight. */
fun latestWorkoutOnDate(
    history: List<WorkoutSession>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): WorkoutSession? = history.asSequence()
    .filter { session ->
        session.completedSets.isNotEmpty() && session.endedAtMillis?.let {
            Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today
        } == true
    }
    .maxByOrNull { it.endedAtMillis ?: Long.MIN_VALUE }
