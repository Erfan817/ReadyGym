package com.readygym.app

import android.content.Context
import java.time.Instant
import java.time.ZoneId

/**
 * The user's reminder rule: on which weekdays, at what local time, to be nudged to train.
 * Days use the app-wide convention 1 = 周一 … 7 = 周日, which is also [java.time.DayOfWeek.getValue].
 */
data class ReminderSettings(
    val enabled: Boolean = false,
    val hour: Int = 19,
    val minute: Int = 0,
    val days: Set<Int> = setOf(1, 3, 5),
) {
    init {
        require(hour in 0..23) { "hour $hour" }
        require(minute in 0..59) { "minute $minute" }
        require(days.all { it in 1..7 }) { "days $days" }
    }
}

/**
 * Pure clock math for the reminder: the first firing strictly after [nowMillis] on a chosen weekday
 * at the chosen local time. Kept free of Android types so the boundaries (today later vs. tomorrow
 * vs. next week, DST) are unit-testable.
 */
object ReminderPlanner {

    fun nextTriggerMillis(nowMillis: Long, zone: ZoneId, settings: ReminderSettings): Long? {
        if (!settings.enabled || settings.days.isEmpty()) return null
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        (0L..7L).forEach { offset ->
            val date = now.toLocalDate().plusDays(offset)
            if (date.dayOfWeek.value in settings.days) {
                val fire = date.atTime(settings.hour, settings.minute).atZone(zone).toInstant().toEpochMilli()
                if (fire > nowMillis) return fire
            }
        }
        return null // unreachable: any weekday recurs within 7 days
    }
}

/** Reminder settings in plain SharedPreferences: three small values, nothing worth a database. */
internal class ReminderStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("readygym.reminders", Context.MODE_PRIVATE)

    fun load(): ReminderSettings = ReminderSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        hour = prefs.getInt(KEY_HOUR, 19),
        minute = prefs.getInt(KEY_MINUTE, 0),
        days = prefs.getString(KEY_DAYS, "1,3,5")!!.split(",").filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }.toSet(),
    )

    fun save(settings: ReminderSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putInt(KEY_HOUR, settings.hour)
            .putInt(KEY_MINUTE, settings.minute)
            .putString(KEY_DAYS, settings.days.sorted().joinToString(","))
            .apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_HOUR = "hour"
        const val KEY_MINUTE = "minute"
        const val KEY_DAYS = "days"
    }
}
