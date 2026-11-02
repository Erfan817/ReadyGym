package com.readygym.core.workout

import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.WorkoutPlan

/** Apply the reviewed prescription to pending sets, never rewriting work already performed. */
fun WorkoutSession.withUpdatedPlan(plan: WorkoutPlan, previousDay: PlanDay?): WorkoutSession {
    val day = plan.days.getOrNull(dayIndex) ?: return this
    if (endedAtMillis != null || sets.all { it.done } || day == previousDay) return this
    val prescribed = WorkoutSession.start(plan.version, dayIndex, day, startedAtMillis).sets
    val doneByExercise = completedSets.groupBy { it.exerciseId }
    val pendingByKey = sets.filterNot { it.done }.associateBy { it.exerciseId to it.setIndex }
    val previousItems = previousDay?.items.orEmpty().associateBy { it.exerciseId }
    val updated = day.items.flatMap { item ->
        val completed = doneByExercise[item.exerciseId].orEmpty()
        val reserved = completed.map { it.setIndex }.toSet()
        val pending = prescribed.filter { it.exerciseId == item.exerciseId && it.setIndex !in reserved }
            .take((item.sets - completed.size).coerceAtLeast(0))
            .map { target ->
                // Keep manually entered weight/reps only when this prescription did not change.
                if (previousItems[item.exerciseId] == item) pendingByKey[target.exerciseId to target.setIndex] ?: target
                else target
            }
        (completed + pending).sortedBy { it.setIndex }
    }
    val activeIds = day.items.map { it.exerciseId }.toSet()
    val archived = completedSets.filter { it.exerciseId !in activeIds }
    return copy(planVersion = plan.version, dayTitle = day.title, sets = updated + archived)
}
