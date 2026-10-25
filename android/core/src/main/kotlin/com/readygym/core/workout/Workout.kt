package com.readygym.core.workout

import com.readygym.core.plan.PlanDay
import kotlinx.serialization.Serializable

/** One logged set. [reps] or [seconds] is filled depending on the exercise's measure. */
@Serializable
data class SetLog(
    val exerciseId: String,
    val setIndex: Int,
    val reps: Int? = null,
    val seconds: Int? = null,
    val loadKg: Double? = null,
    val done: Boolean = false,
)

@Serializable
data class WorkoutSession(
    val id: Long = 0,
    val planVersion: Int,
    val dayIndex: Int,
    val dayTitle: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val sets: List<SetLog> = emptyList(),
) {
    val completedSets: List<SetLog> get() = sets.filter { it.done }
    val durationMinutes: Int? get() = endedAtMillis?.let { ((it - startedAtMillis) / 60_000).toInt() }
    /** Total kg moved: sum of load × reps over completed loaded sets. */
    val volumeKg: Double get() = completedSets.sumOf { (it.loadKg ?: 0.0) * (it.reps ?: 0) }

    companion object {
        /**
         * Pre-fills every set from the plan. [last] should only hold sets logged under the same plan
         * version: there the user's actual load wins; after a plan change the new prescription wins.
         */
        fun start(planVersion: Int, dayIndex: Int, day: PlanDay, nowMillis: Long, last: Map<String, List<SetLog>> = emptyMap()) =
            WorkoutSession(planVersion = planVersion, dayIndex = dayIndex, dayTitle = day.title, startedAtMillis = nowMillis,
                sets = day.items.flatMap { item ->
                    (0 until item.sets).map { i ->
                        val prev = last[item.exerciseId]?.getOrNull(i)
                        SetLog(item.exerciseId, i,
                            reps = if (item.seconds == null) item.repsMax ?: item.repsMin else null,
                            seconds = item.seconds,
                            loadKg = prev?.loadKg ?: item.loadKg)
                    }
                })
    }
}

enum class Effort(val label: String) { TOO_EASY("太轻松"), JUST_RIGHT("刚好"), TIRING("有点累"), TOO_HARD("太累了") }
enum class Difficulty(val label: String) { EASY("偏易"), RIGHT("合适"), HARD("偏难") }
enum class BodyArea(val label: String) { SHOULDER("肩膀"), KNEE("膝盖"), BACK("腰背"), WRIST("手腕"), ELBOW("手肘"), OTHER("其他") }

@Serializable
data class PostWorkoutFeedback(
    val sessionId: Long,
    val effort: Effort,
    val discomfort: Set<BodyArea> = emptySet(),
    val perExercise: Map<String, Difficulty> = emptyMap(),
    val note: String = "",
) {
    val hasDiscomfort: Boolean get() = discomfort.isNotEmpty()
}
