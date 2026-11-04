package com.readygym.core.workout

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Three real workouts per check-in; the latest workout gets until the next local day to recover. */
fun recoveryCheckInDue(history: List<WorkoutSession>, handledThroughId: Long, today: LocalDate,
                       zone: ZoneId = ZoneId.systemDefault()): WorkoutSession? {
    val pending = history.filter { it.id > handledThroughId && it.endedAtMillis != null && it.completedSets.isNotEmpty() }
    if (pending.size < 3) return null
    val latest = pending.maxWith(compareBy<WorkoutSession> { it.endedAtMillis }.thenBy { it.id })
    return latest.takeIf { Instant.ofEpochMilli(requireNotNull(it.endedAtMillis)).atZone(zone).toLocalDate().isBefore(today) }
}
