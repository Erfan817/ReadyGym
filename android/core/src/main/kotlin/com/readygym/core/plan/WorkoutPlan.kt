package com.readygym.core.plan

import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.exercise.Measure
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One prescribed exercise. Reps-measured items use [repsMin]..[repsMax];
 * seconds-measured items use [seconds]. [loadKg] is only meaningful for loadable exercises.
 */
@Serializable
data class PlanItem(
    val exerciseId: String,
    val sets: Int,
    val repsMin: Int? = null,
    val repsMax: Int? = null,
    val seconds: Int? = null,
    val loadKg: Double? = null,
    val restSeconds: Int = PlanLimits.DEFAULT_REST_SECONDS,
    val note: String? = null,
) {
    val target: String get() = when {
        seconds != null -> "$sets 组 × $seconds 秒"
        repsMin == repsMax || repsMax == null -> "$sets 组 × $repsMin 次"
        else -> "$sets 组 × $repsMin–$repsMax 次"
    }
    val loadText: String? get() = loadKg?.let { "${formatKg(it)} kg" }
}

@Serializable
data class PlanDay(val title: String, val focus: String = "", val items: List<PlanItem>)

/** A full training plan. Every adjustment creates a new [version]; old versions are kept. */
@Serializable
data class WorkoutPlan(
    val version: Int,
    val createdAtMillis: Long,
    val reason: String,
    val summary: String,
    val daysPerWeek: Int,
    val days: List<PlanDay>,
    /** Manual additions continue this rotation; an AI-generated replacement starts a fresh one. */
    val progressionBaseVersion: Int? = null,
) {
    fun toJson(): String = PlanJson.encodeToString(serializer(), this)

    /** Apply the current rest setting without changing the plan version or training prescription. */
    fun withDefaultRest(): WorkoutPlan {
        if (days.all { day -> day.items.all { it.restSeconds == PlanLimits.DEFAULT_REST_SECONDS } }) return this
        return copy(days = days.map { day ->
            if (day.items.all { it.restSeconds == PlanLimits.DEFAULT_REST_SECONDS }) day
            else day.copy(items = day.items.map { item ->
                if (item.restSeconds == PlanLimits.DEFAULT_REST_SECONDS) item
                else item.copy(restSeconds = PlanLimits.DEFAULT_REST_SECONDS)
            })
        })
    }

    companion object {
        fun fromJson(text: String): WorkoutPlan = PlanJson.decodeFromString(serializer(), text)
    }
}

internal val PlanJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun formatKg(kg: Double): String = if (kg % 1.0 == 0.0) kg.toInt().toString() else "%.1f".format(kg)

/** Hard limits every plan must respect, whoever wrote it. */
object PlanLimits {
    const val DEFAULT_REST_SECONDS = 30
    val DAYS = 1..7
    val ITEMS_PER_DAY = 2..8
    val SETS = 1..6
    val REPS = 1..30
    val SECONDS = 10..180
    val REST = 20..300
    const val MAX_LOAD_KG = 300.0
}

/**
 * Checks a plan (typically from the model) against the exercise library and the user's
 * available exercises. Returns human-readable problems; empty means the plan is usable.
 */
class PlanValidator(private val library: ExerciseLibrary, private val allowedIds: Set<String>) {
    fun problems(days: List<PlanDay>): List<String> = buildList {
        if (days.size !in PlanLimits.DAYS) add("训练日数量 ${days.size} 不在 ${PlanLimits.DAYS}")
        days.forEachIndexed { d, day ->
            val where = "第 ${d + 1} 天"
            if (day.title.isBlank()) add("$where 缺少标题")
            if (day.items.size !in PlanLimits.ITEMS_PER_DAY) add("$where 动作数 ${day.items.size} 不在 ${PlanLimits.ITEMS_PER_DAY}")
            val ids = day.items.map { it.exerciseId }
            if (ids.size != ids.toSet().size) add("$where 有重复动作")
            day.items.forEach { item -> addAll(itemProblems(where, item)) }
        }
    }

    private fun itemProblems(where: String, item: PlanItem): List<String> = buildList {
        val ex = library[item.exerciseId]
        if (ex == null) { add("$where 动作 ${item.exerciseId} 不在动作库"); return@buildList }
        if (item.exerciseId !in allowedIds) add("$where ${ex.name} 不在当前可安排的动作里（受场地、器械或经验等级限制，新手不安排硬拉等高风险动作）")
        if (item.sets !in PlanLimits.SETS) add("$where ${ex.name} 组数 ${item.sets} 超出范围")
        if (item.restSeconds !in PlanLimits.REST) add("$where ${ex.name} 休息 ${item.restSeconds} 秒超出范围")
        when (ex.measure) {
            Measure.REPS -> {
                val lo = item.repsMin; val hi = item.repsMax ?: item.repsMin
                if (lo == null || hi == null || lo !in PlanLimits.REPS || hi !in PlanLimits.REPS || lo > hi)
                    add("$where ${ex.name} 次数无效")
                if (item.seconds != null) add("$where ${ex.name} 是计次动作，不能给秒数")
            }
            Measure.SECONDS -> if (item.seconds == null || item.seconds !in PlanLimits.SECONDS) add("$where ${ex.name} 秒数无效")
        }
        item.loadKg?.let {
            if (!ex.loadable) add("$where ${ex.name} 是徒手动作，不应有重量")
            else if (it < 0 || it > PlanLimits.MAX_LOAD_KG) add("$where ${ex.name} 重量 $it 超出范围")
        }
    }
}
