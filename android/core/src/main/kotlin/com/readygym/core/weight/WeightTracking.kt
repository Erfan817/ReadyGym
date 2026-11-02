package com.readygym.core.weight

import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/** A user-entered measurement. A profile's weight never creates a dated measurement implicitly. */
data class WeightEntry(val date: LocalDate, val kilograms: Double) {
    init { weightInTenths(kilograms) }
}

object WeightLimits {
    const val MIN_KG = 30.0
    const val MAX_KG = 250.0
    const val STEP_KG = 0.1
}

/** Validate before rounding: NaN, infinities and values outside the supported range are rejected. */
fun weightInTenths(kilograms: Double): Int {
    require(kilograms.isFinite() && kilograms in WeightLimits.MIN_KG..WeightLimits.MAX_KG) {
        "体重请输入 30–250 kg 之间的数字。"
    }
    return (kilograms * 10).roundToInt()
}

fun normalizedWeightKg(kilograms: Double): Double = weightInTenths(kilograms) / 10.0

/** Stable decimal punctuation for numeric inputs and chart labels regardless of phone locale. */
fun formatWeightKg(kilograms: Double): String = String.format(Locale.ROOT, "%.1f", normalizedWeightKg(kilograms))
