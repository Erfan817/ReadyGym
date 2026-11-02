package com.readygym.core.exercise

import com.readygym.core.MuscleGroup
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.PlanLimits
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile

/** The same eligibility rules used by the coach, with primary muscles listed before helpers. */
object MuscleExercisePicker {
    fun recommend(library: ExerciseLibrary, profile: UserProfile?, muscle: MuscleGroup): List<Exercise> {
        val available = profile?.let {
            library.availableFor(it.location, it.equipment, it.experience)
        } ?: library.exercises.filter { it.allowedFor(Experience.BEGINNER) }
        return available.asSequence()
            .filter { it.primary == muscle || muscle in it.secondary }
            .filter { !it.gifAsset.isNullOrBlank() || !it.gifPath.isNullOrBlank() }
            .sortedWith(compareBy<Exercise> { it.primary != muscle }
                .thenBy { it.level.ordinal }
                .thenBy { it.gifAsset.isNullOrBlank() }
                .thenBy { it.name })
            .toList()
    }

    /** An unweighted starting prescription; the user can record a comfortable load during training. */
    fun defaultItem(exercise: Exercise): PlanItem = when (exercise.measure) {
        Measure.REPS -> PlanItem(exercise.id, sets = 2, repsMin = 8, repsMax = 12)
        Measure.SECONDS -> PlanItem(exercise.id, sets = 2, seconds = 20)
    }

    /** Validates again at the write boundary; browsing a recommendation never grants permission. */
    fun addToPlan(
        library: ExerciseLibrary,
        profile: UserProfile,
        plan: WorkoutPlan,
        exerciseId: String,
        dayIndex: Int,
    ): MusclePlanEdit {
        val day = plan.days.getOrNull(dayIndex)
            ?: return MusclePlanEdit.Rejected("这一天已不在计划里，请返回计划页重新选择。")
        val exercise = library[exerciseId]
            ?: return MusclePlanEdit.Rejected("没找到这个动作，请重新选择一个。")
        if (library.availableFor(profile.location, profile.equipment, profile.experience).none { it.id == exerciseId }) {
            return MusclePlanEdit.Rejected("这个动作不适合你当前的器械或训练经验，请换一个。")
        }
        if (exercise.gifAsset.isNullOrBlank() && exercise.gifPath.isNullOrBlank()) {
            return MusclePlanEdit.Rejected("这个动作还没有演示，请先选一个有演示的动作。")
        }
        if (day.items.any { it.exerciseId == exerciseId }) {
            return MusclePlanEdit.Rejected("这一天已经有这个动作了，可以选其他训练日。")
        }
        if (day.items.size >= PlanLimits.ITEMS_PER_DAY.last) {
            return MusclePlanEdit.Rejected("这一天已有 ${PlanLimits.ITEMS_PER_DAY.last} 个动作，请选其他训练日。")
        }
        val updated = day.copy(items = day.items + defaultItem(exercise))
        return MusclePlanEdit.Added(plan.copy(
            reason = "手动添加：${exercise.name}",
            days = plan.days.mapIndexed { index, original -> if (index == dayIndex) updated else original },
            progressionBaseVersion = plan.progressionBaseVersion ?: plan.version,
        ))
    }
}

sealed interface MusclePlanEdit {
    data class Added(val plan: WorkoutPlan) : MusclePlanEdit
    data class Rejected(val message: String) : MusclePlanEdit
}
