package com.readygym.core.profile

import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class Experience(val label: String) { BEGINNER("新手"), RETURNING("恢复训练"), EXPERIENCED("有经验") }

/** Everything the coach knows about the user: body data plus training preferences. */
@Serializable
data class UserProfile(
    val age: Int,
    val heightCm: Double,
    val weightKg: Double,
    val gender: Gender,
    val goal: Goal,
    val location: Location,
    val equipment: Set<Equipment>,
    val daysPerWeek: Int,
    val minutesPerSession: Int,
    val experience: Experience,
    /** Free text such as old injuries or movements to avoid; passed to the model as-is. */
    val limitations: String = "",
    /** Weekdays the user trains on, 1 = Monday … 7 = Sunday. Empty means "any days". */
    val trainingDays: Set<Int> = emptySet(),
) {
    init {
        require(age in 14..90) { "age $age" }
        require(heightCm in 100.0..250.0) { "height $heightCm" }
        require(weightKg in 30.0..250.0) { "weight $weightKg" }
        require(daysPerWeek in 1..7) { "days $daysPerWeek" }
        require(minutesPerSession in 10..180) { "minutes $minutesPerSession" }
        require(equipment.isNotEmpty()) { "equipment" }
        require(trainingDays.all { it in 1..7 }) { "weekday" }
        require(trainingDays.isEmpty() || trainingDays.size == daysPerWeek) { "trainingDays must match daysPerWeek" }
    }

    /** Short Chinese description used in prompts and the profile screen. */
    fun describe(): String = buildString {
        append("${age}岁，${gender.label}，${formatNumber(heightCm)}cm，${formatNumber(weightKg)}kg；")
        append("目标${goal.label}，在${location.label}练，器械：${equipment.joinToString("、") { it.label }}；")
        append("每周${daysPerWeek}练")
        if (trainingDays.isNotEmpty()) append("（${trainingDays.sorted().joinToString("、") { weekdayLabel(it) }}）")
        append("，每次约${minutesPerSession}分钟，${experience.label}")
        if (limitations.isNotBlank()) append("；注意：$limitations")
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(text: String): UserProfile = json.decodeFromString(serializer(), text)
    }
}

/** 周一 … 周日 for 1 … 7. */
fun weekdayLabel(day: Int): String = "周" + "一二三四五六日"[day - 1]

/** A sensible spread of [count] weekdays, e.g. 3 → Mon/Wed/Fri, used before the user picks. */
fun defaultTrainingDays(count: Int): Set<Int> = when (count) {
    1 -> setOf(3); 2 -> setOf(2, 5); 3 -> setOf(1, 3, 5); 4 -> setOf(1, 2, 4, 5)
    5 -> setOf(1, 2, 3, 4, 5); 6 -> setOf(1, 2, 3, 4, 5, 6); else -> (1..7).toSet()
}

private fun formatNumber(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
