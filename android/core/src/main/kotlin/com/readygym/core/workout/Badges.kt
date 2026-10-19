package com.readygym.core.workout

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** A one-time achievement. [icon] is an emoji so badges need no image assets. */
enum class Badge(val title: String, val icon: String, val hint: String) {
    FIRST("初次登场", "🎉", "完成第一次训练"),
    COUNT_5("渐入佳境", "💪", "累计完成 5 次训练"),
    COUNT_10("十次达成", "🏅", "累计完成 10 次训练"),
    COUNT_30("习惯养成", "🏆", "累计完成 30 次训练"),
    COUNT_100("百练成钢", "💯", "累计完成 100 次训练"),
    FULL_CLEAR("一组不落", "✅", "一次训练里完成全部计划组数"),
    EARLY_BIRD("早起鸟", "🌅", "早上 7 点前开始训练"),
    NIGHT_OWL("夜练达人", "🌙", "晚上 9 点后开始训练"),
    WEEK_GOAL("周目标达成", "📅", "一周内练满计划的天数"),
    STREAK_3W("三周不断", "🔥", "连续 3 周达成周目标"),
    TONNAGE_1T("一吨力量", "🏋️", "累计训练容量达到 1000 kg"),
}

/** What one finished workout earned: always a check-in stamp, plus any badges first unlocked by it. */
data class SessionAward(val sessionId: Long, val ordinal: Int, val unlocked: List<Badge>)

/**
 * Badges are derived from the workout history alone, so nothing extra is stored and the same rules
 * can run on the phone and on the server. A badge belongs to the session that first met its rule.
 */
object BadgeRules {

    fun awards(history: List<WorkoutSession>, daysPerWeek: Int, zone: ZoneId = ZoneId.systemDefault()): List<SessionAward> {
        val finished = history.filter { it.endedAtMillis != null && it.completedSets.isNotEmpty() }.sortedBy { it.startedAtMillis }
        val earned = mutableSetOf<Badge>()
        val perWeek = mutableMapOf<LocalDate, Int>()
        var tonnage = 0.0
        return finished.mapIndexed { i, s ->
            val count = i + 1
            val start = Instant.ofEpochMilli(s.startedAtMillis).atZone(zone)
            val week = weekOf(start.toLocalDate())
            perWeek[week] = (perWeek[week] ?: 0) + 1
            tonnage += s.volumeKg
            val goal = daysPerWeek.coerceAtLeast(1)

            val met = buildList {
                if (count >= 1) add(Badge.FIRST)
                if (count >= 5) add(Badge.COUNT_5)
                if (count >= 10) add(Badge.COUNT_10)
                if (count >= 30) add(Badge.COUNT_30)
                if (count >= 100) add(Badge.COUNT_100)
                if (s.sets.isNotEmpty() && s.sets.all { it.done }) add(Badge.FULL_CLEAR)
                if (start.hour < 7) add(Badge.EARLY_BIRD)
                if (start.hour >= 21) add(Badge.NIGHT_OWL)
                if (perWeek.getValue(week) >= goal) add(Badge.WEEK_GOAL)
                if ((0..2).all { (perWeek[week.minusWeeks(it.toLong())] ?: 0) >= goal }) add(Badge.STREAK_3W)
                if (tonnage >= 1000) add(Badge.TONNAGE_1T)
            }
            val fresh = met.filter { earned.add(it) }
            SessionAward(s.id, count, fresh)
        }
    }

    fun earned(history: List<WorkoutSession>, daysPerWeek: Int, zone: ZoneId = ZoneId.systemDefault()): Set<Badge> =
        awards(history, daysPerWeek, zone).flatMap { it.unlocked }.toSet()

    /** Monday of the week containing [date]; weeks run Monday to Sunday. */
    fun weekOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}
