package com.readygym.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.MuscleGroup
import com.readygym.core.MuscleTaxonomy
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.exercise.label
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.workout.Badge
import com.readygym.core.workout.BadgeRules
import com.readygym.core.workout.WorkoutSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Personal progress first, analysis next, and optional social achievements after that.
 *
 * Every number is derived from stored sessions. Empty weeks draw as empty tiles, never as a smooth
 * line, and a tile with no data source says so rather than showing a plausible zero.
 */
@Composable
fun StatsScreen(vm: GymViewModel, onOpenRecords: () -> Unit) {
    val history by vm.history.collectAsState()
    val profile by vm.profile.collectAsState()
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val p = profile.valueOrNull()

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = GymSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
    ) {
        Spacer(Modifier.height(GymSpacing.Md))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("统计", fontSize = 29.sp, lineHeight = 35.sp, fontWeight = FontWeight.ExtraBold,
                    color = ReadyGymColors.Text)
                Text("进度与历史", fontSize = 14.sp, color = ReadyGymColors.MutedText)
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(ReadyGymColors.Surface)
                    .clickable(role = Role.Button, onClick = onOpenRecords).testTag("stats-history"),
                contentAlignment = Alignment.Center,
            ) { Icon(GymIcons.History, "完整的训练记录", Modifier.size(20.dp), tint = ReadyGymColors.Text) }
        }

        Spacer(Modifier.height(GymSpacing.Xs))
        MetricGrid(history, today, zone, p?.daysPerWeek)
        WeightTrackingPanel(profileWeightKg = p?.weightKg)
        ActivityCard(history, today, zone)
        MuscleBalanceCard(history, vm.library, today, zone, vm.activePlan.collectAsState().value.valueOrNull())

        SectionHeader("坚持的足迹")
        BadgeSummary(history, p?.daysPerWeek ?: 3)
        // Viewing the board remains an explicit action; opening statistics never uploads data.
        WeeklyBoard(vm)

        // The calendar moved here on purpose: it answers "what did I do", a follow-up question,
        // and it is the tallest block on the page.
        SectionHeader("训练日历", Modifier.padding(top = GymSpacing.Sm))
        RecordsCalendar(vm)
        Spacer(Modifier.height(GymSpacing.Xxl))
    }
}

// ------------------------------------------------------------------ metric tiles

@Composable
private fun MetricGrid(history: List<WorkoutSession>, today: LocalDate, zone: ZoneId, daysPerWeek: Int?) {
    val finished = remember(history) { history.filter { it.endedAtMillis != null } }
    val thisMonth = finished.count {
        val d = Instant.ofEpochMilli(it.startedAtMillis).atZone(zone).toLocalDate()
        d.year == today.year && d.monthValue == today.monthValue
    }
    val weekDone = weeklyTrainingCount(finished, today, zone)
    val goal = daysPerWeek?.coerceAtLeast(1)
    val streak = goal?.let { streakOf(finished, it, today, zone) }
    val remaining = goal?.let { (it - weekDone).coerceAtLeast(0) }

    Panel(modifier = Modifier.testTag("metric-本周"), container = ReadyGymColors.AccentWash) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("本周训练", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = ReadyGymColors.AccentText, modifier = Modifier.weight(1f))
            if (remaining == 0) Text("已达成 ✓", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = ReadyGymColors.AccentText)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$weekDone", fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold,
                color = ReadyGymColors.Text)
            Text(goal?.let { " / $it 次" } ?: " 次", fontSize = 17.sp, color = ReadyGymColors.MutedText,
                modifier = Modifier.padding(bottom = 5.dp))
        }
        if (goal != null) {
            Box(Modifier.fillMaxWidth().height(7.dp).clip(GymShapes.Xs)
                .background(ReadyGymColors.Surface)) {
                Box(Modifier.fillMaxWidth((weekDone.toFloat() / goal).coerceIn(0f, 1f)).height(7.dp)
                    .clip(GymShapes.Xs).background(ReadyGymColors.AccentText))
            }
        }
        Text(
            when {
                remaining == null -> "建档后，这里会显示你的每周目标。"
                remaining == 0 -> "本周目标已达成，休息也是训练的一部分。"
                else -> "再完成 $remaining 次，就达到本周目标。按自己的节奏来。"
            },
            fontSize = 13.sp, lineHeight = 20.sp, color = ReadyGymColors.AccentText,
            modifier = Modifier.testTag("stats-week-summary"),
        )
    }
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
            MetricValue("连续达标", streak?.toString() ?: "—", "周", "连续周数", Modifier.weight(1f))
            MetricValue("本月训练", "$thisMonth", "次", "本月", Modifier.weight(1f))
            MetricValue("累计训练", "${finished.size}", "次", "训练次数", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricValue(label: String, value: String, unit: String, tag: String, modifier: Modifier) {
    Column(modifier.testTag("metric-$tag"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 12.sp, color = ReadyGymColors.MutedText)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.Text)
            Text(unit, fontSize = 11.sp, color = ReadyGymColors.MutedText,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        }
    }
}

/** Same session-count convention as the existing metrics; an unfinished workout is never counted. */
internal fun weeklyTrainingCount(history: List<WorkoutSession>, today: LocalDate, zone: ZoneId): Int {
    val week = BadgeRules.weekOf(today)
    return history.count { session ->
        session.endedAtMillis != null &&
            BadgeRules.weekOf(Instant.ofEpochMilli(session.startedAtMillis).atZone(zone).toLocalDate()) == week
    }
}

// ------------------------------------------------------------------ activity heat map

/**
 * A year of day tiles, one column per week, fitted to the card.
 *
 * The week count is derived from the measured width rather than scrolling a fixed year: a
 * horizontally scrolled grid always ends on a half column, and a sliver of tiles at the card edge
 * reads as a rendering fault. Fitting means the grid is always whole, and the heading states the
 * span it actually draws ("近 6 个月", not a year it only pretends to cover).
 *
 * Tile opacity is that day's completed sets against the busiest day shown, and today always
 * carries a ring, so a brand-new account can still read where "now" is on an empty grid.
 */
@Composable
private fun ActivityCard(history: List<WorkoutSession>, today: LocalDate, zone: ZoneId) {
    var mode by rememberSaveable { mutableStateOf(0) }
    val byDay = remember(history, zone) {
        history.filter { it.endedAtMillis != null }
            .groupBy { Instant.ofEpochMilli(it.startedAtMillis).atZone(zone).toLocalDate() }
            .mapValues { (_, list) -> list.sumOf { it.durationMinutes ?: 0 } to list.sumOf { it.completedSets.size } }
    }
    val peak = remember(byDay, mode) {
        byDay.values.maxOfOrNull { (minutes, sets) -> if (mode == 0) minutes else sets }?.coerceAtLeast(1) ?: 1
    }

    Panel {
        BoxWithConstraints {
            val gap = 3.dp
            // 21 columns, as in the reference. The reference draws a year at this density, so the
            // tile size is derived from the width rather than fixed: at 360dp a 11dp tile fits 21
            // columns, and on a wider screen the tiles grow to keep the same count instead of
            // adding columns the reference does not have.
            val columns = 21
            val tile = ((maxWidth - gap * (columns - 1)) / columns).coerceAtLeast(8.dp)
            // Anchor on this week and walk backwards, so the rightmost column is always "now".
            val firstMonday = BadgeRules.weekOf(today).minusWeeks((columns - 1).toLong())
            // Round, do not floor: 21 weeks is 4.8 months, and flooring it printed "近 4 个月"
            // above a month axis that spanned five.
            val spanMonths = Math.round((today.toEpochDay() - firstMonday.toEpochDay()) / 30.44).toInt()

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "活动（近 ${spanMonths.coerceAtLeast(1)} 个月）",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.Text,
                )
                SegmentedControl(listOf("时间", "总量"), mode, { mode = it }, Modifier.testTag("activity-tabs"))
                Column {
                    MonthRuler(firstMonday, columns, gap, tile)
                    Spacer(Modifier.height(GymSpacing.Sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (w in 0 until columns) {
                            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                for (d in 0..6) {
                                    val date = firstMonday.plusDays((w * 7 + d).toLong())
                                    if (date.isAfter(today)) Spacer(Modifier.size(tile))
                                    else {
                                        val entry = byDay[date]
                                        val value = when {
                                            entry == null -> 0
                                            mode == 0 -> entry.first
                                            else -> entry.second
                                        }
                                        HeatTile(value, peak, date == today, date, tile)
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (byDay.isEmpty()) "还没有训练记录，练完第一次就会有一格亮起来。"
                        else "共 ${byDay.size} 天有记录",
                        fontSize = 12.sp, color = ReadyGymColors.MutedText, modifier = Modifier.weight(1f),
                    )
                    Text("少", fontSize = 12.sp, color = ReadyGymColors.MutedText)
                    listOf(0.25f, 0.5f, 0.75f, 1f).forEach { f ->
                        Spacer(Modifier.width(gap))
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(HeatColor(f)))
                    }
                    Spacer(Modifier.width(gap))
                    Text("多", fontSize = 12.sp, color = ReadyGymColors.MutedText)
                }
            }
        }
    }
}

private fun HeatColor(fraction: Float) = ReadyGymColors.Accent.copy(alpha = 0.18f + 0.82f * fraction.coerceIn(0f, 1f))

/**
 * One day. Three readings must stay distinguishable at 11dp, so they differ in *value* as well as
 * in hue: an empty day is a grey tile, a trained day is a green fill whose depth is the volume, and
 * today is always a light tile with a dark ring — the ring alone says "now", whether or not the day
 * has data. The earlier version drew today as green-fill-plus-green-ring, which at this size read
 * as just another trained day.
 */
@Composable
private fun HeatTile(value: Int, peak: Int, isToday: Boolean, date: LocalDate, tile: androidx.compose.ui.unit.Dp) {
    val fraction = if (value <= 0) 0f else (value.toFloat() / peak).coerceIn(0.2f, 1f)
    val shape = RoundedCornerShape(3.dp)
    val background = when {
        isToday && fraction <= 0f -> ReadyGymColors.Surface
        fraction <= 0f -> ReadyGymColors.Sunken
        else -> HeatColor(fraction)
    }
    Box(
        Modifier
            .size(tile)
            .clip(shape)
            .background(background)
            .then(
                when {
                    isToday -> Modifier.border(1.5.dp, ReadyGymColors.Text, shape)
                    fraction <= 0f -> Modifier.border(1.dp, ReadyGymColors.Outline.copy(alpha = 0.35f), shape)
                    else -> Modifier
                }
            )
            .semantics {
                contentDescription = "${date.monthValue}月${date.dayOfMonth}日，" + when {
                    isToday -> "今天，" + if (value <= 0) "还没有训练记录" else "有训练记录"
                    value <= 0 -> "没有训练记录"
                    else -> "有训练记录"
                }
            },
    )
}

/**
 * Month labels lined up with the week columns.
 *
 * Drawn rather than laid out: a label ("10月") is wider than the 11dp column it belongs to, so a
 * normal Row would clip it to its own cell. Measuring and drawing at an exact x keeps the label
 * anchored to the first week of its month without disturbing the grid.
 */
@Composable
private fun MonthRuler(start: LocalDate, weeks: Int, gap: androidx.compose.ui.unit.Dp, tile: androidx.compose.ui.unit.Dp) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 11.sp, color = ReadyGymColors.FaintText)
    val step = tile + gap
    val width = step * weeks - gap
    val labels = remember(start, weeks) {
        buildList {
            var last = -99
            for (w in 0 until weeks) {
                val date = start.plusDays((w * 7).toLong())
                if (date.dayOfMonth <= 7 && w - last >= 3) { last = w; add(w to "${date.monthValue}月") }
            }
        }
    }
    Canvas(Modifier.width(width).height(15.dp)) {
        labels.forEach { (week, text) ->
            val layout = measurer.measure(AnnotatedString(text), style)
            val x = (step * week).toPx().coerceAtMost(size.width - layout.size.width)
            drawText(layout, topLeft = Offset(x, (size.height - layout.size.height) / 2f))
        }
    }
}

// ------------------------------------------------------------------ muscle balance

/**
 * 肌群平衡: effective sets per muscle in the chosen window, as horizontal bars.
 *
 * Uses [MuscleTaxonomy.loadOf], the same weighting the taxonomy documents (primary 1.0, secondary
 * 0.4, summed per completed set), so a push-up day is visible even when the plan called it an
 * accessory. Bars are relative to the most-worked muscle in the window; an untouched muscle is
 * listed rather than dropped from the chart.
 */
@Composable
private fun MuscleBalanceCard(
    history: List<WorkoutSession>,
    library: ExerciseLibrary,
    today: LocalDate,
    zone: ZoneId,
    plan: WorkoutPlan?,
) {
    var range by rememberSaveable { mutableStateOf(0) }
    var weekOffset by rememberSaveable { mutableStateOf(0) }
    var showMethod by rememberSaveable { mutableStateOf(false) }
    val weekStart = BadgeRules.weekOf(today).minusWeeks(weekOffset.toLong())
    val from = when (range) {
        1 -> today.minusDays(30)
        2 -> today.minusDays(90)
        3 -> LocalDate.MIN
        else -> weekStart
    }
    val to = when (range) {
        0 -> weekStart.plusDays(6)
        else -> today
    }
    // What the plan prescribes for a full week: the sum of every day's prescribed sets. Shown next
    // to the achieved total so "3" has something to be compared against instead of standing alone.
    // No plan means no comparison — the line is omitted rather than comparing against a guess.
    val planned = remember(plan) { plan?.days?.sumOf { d -> d.items.sumOf { it.sets } } ?: 0 }

    val load = remember(history, range, weekOffset, library) {
        val items = history.filter { it.endedAtMillis != null }.flatMap { s ->
            val date = Instant.ofEpochMilli(s.startedAtMillis).atZone(zone).toLocalDate()
            if (date < from || date > to) emptyList()
            else s.completedSets.groupBy { it.exerciseId }.mapNotNull { (id, sets) ->
                val ex = library[id] ?: return@mapNotNull null
                val weights = buildMap {
                    put(ex.primary, 1.0)
                    ex.secondary.forEach { m -> put(m, maxOf(get(m) ?: 0.0, MuscleTaxonomy.SECONDARY_WEIGHT)) }
                }
                weights to sets.size
            }
        }
        MuscleTaxonomy.loadOf(items)
    }
    val ranked = remember(load) { MuscleTaxonomy.rankOf(load) }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("这段时间练了哪里", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = ReadyGymColors.Text, modifier = Modifier.weight(1f))
            Box(Modifier.heightIn(min = 48.dp).clip(GymShapes.Sm)
                .clickable(role = Role.Button, onClick = { showMethod = !showMethod })
                .padding(horizontal = GymSpacing.Sm).testTag("muscle-method-toggle"),
                contentAlignment = Alignment.Center) {
                Text(if (showMethod) "收起说明" else "怎么看", fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, color = ReadyGymColors.AccentText)
            }
        }
        SegmentedControl(listOf("本周", "30天", "90天", "全部"), range, { range = it }, Modifier.testTag("muscle-range"))
        Spacer(Modifier.height(GymSpacing.Sm))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StepArrow(GymIcons.ChevronLeft, range == 0, { weekOffset += 1 }, "更早一周")
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when (range) {
                        0 -> weekRangeLabel(weekStart)
                        1 -> "近 30 天"
                        2 -> "近 90 天"
                        else -> "全部记录"
                    },
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ReadyGymColors.Text,
                    modifier = Modifier.testTag("muscle-week"),
                )
                if (ranked.worked.isNotEmpty()) Text(
                    "练到 ${ranked.worked.size} 个肌群",
                    fontSize = 11.sp, color = ReadyGymColors.MutedText,
                )
            }
            StepArrow(GymIcons.ChevronRight, range == 0 && weekOffset > 0, { weekOffset -= 1 }, "更近一周")
        }
        Spacer(Modifier.height(GymSpacing.Md))
        when {
            history.isEmpty() -> Text(
                "还没有训练记录。完成一次训练、勾上做过的组，这里就会显示练到了哪些肌群。",
                fontSize = 13.sp, lineHeight = 20.sp, color = ReadyGymColors.MutedText,
            )
            ranked.worked.isEmpty() -> Text(
                "这个时间范围里没有勾选完成的组。换一个范围，或者去「开始」练一次。",
                fontSize = 13.sp, lineHeight = 20.sp, color = ReadyGymColors.MutedText,
            )
            else -> MuscleBars(ranked, load)
        }
        if (showMethod) {
            Text(
                "只统计勾选完成的组。每组给主要肌群计 1 组、辅助肌群计 0.4 组，右侧数字是折算后的组数；条越长，表示这段时间练到它的组数越多。" +
                    "一组可能练到多个肌群，所以各肌群相加可能超过实际完成的组数。" +
                    if (planned > 0) "当前计划一轮共安排 $planned 组。" else "",
                fontSize = 12.sp, lineHeight = 18.sp, color = ReadyGymColors.MutedText,
                modifier = Modifier.padding(top = GymSpacing.Sm).testTag("muscle-method"),
            )
        }
    }
}

@Composable
private fun MuscleBars(ranked: MuscleTaxonomy.Ranked, load: Map<MuscleGroup, Double>) {
    val top = load.values.maxOrNull()?.coerceAtLeast(0.1) ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
        ranked.worked.take(8).forEach { muscle ->
            val value = load[muscle] ?: 0.0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(muscle.label, fontSize = 13.sp, color = ReadyGymColors.Text,
                    modifier = Modifier.width(64.dp), maxLines = 1)
                Box(Modifier.weight(1f).height(10.dp).clip(GymShapes.Xs).background(ReadyGymColors.Sunken)) {
                    Box(
                        Modifier.fillMaxWidth((value / top).toFloat().coerceIn(0.04f, 1f))
                            .height(10.dp).clip(GymShapes.Xs).background(ReadyGymColors.Accent),
                    )
                }
                Spacer(Modifier.width(GymSpacing.Sm))
                Text("%.0f".format(value), fontSize = 12.sp, color = ReadyGymColors.MutedText,
                    modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
            }
        }
        if (ranked.missed.isNotEmpty()) {
            Text(
                "没练到：" + ranked.missed.take(6).joinToString("、") { it.label } +
                    if (ranked.missed.size > 6) " 等 ${ranked.missed.size} 块" else "",
                fontSize = 12.sp, lineHeight = 18.sp, color = ReadyGymColors.MutedText,
                modifier = Modifier.padding(top = GymSpacing.Xs),
            )
        }
    }
}

@Composable
private fun StepArrow(icon: ImageVector, enabled: Boolean, onClick: () -> Unit, description: String) {
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // Both arrows use the same secondary tone; the disabled one is the *only* difference, so
        // "I cannot go further this way" reads as a state rather than as a different control.
        Icon(icon, null, Modifier.size(18.dp),
            tint = if (enabled) ReadyGymColors.MutedText else ReadyGymColors.FaintText.copy(alpha = 0.45f))
    }
}

/** "10月19日 起的一周", matching the reference's week label. */
private fun weekRangeLabel(monday: LocalDate): String =
    "${monday.monthValue}月${monday.dayOfMonth}日 起的一周"

// ------------------------------------------------------------------ shared pieces

/**
 * The segmented control both analysis cards use: a sunken track with one raised thumb.
 *
 * Selection changes weight, fill, and accessibility state as well as colour.
 */
@Composable
private fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(GymShapes.Md).background(ReadyGymColors.Sunken).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(GymShapes.Sm)
                    .background(if (isSelected) ReadyGymColors.Surface else ReadyGymColors.Sunken)
                    .clickable(role = Role.Tab, onClick = { onSelect(index) })
                    .semantics { contentDescription = label; this.selected = isSelected },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) ReadyGymColors.Text else ReadyGymColors.MutedText,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Consecutive weeks (ending this week, or last week when this one is not yet met) hitting the goal. */
internal fun streakOf(finished: List<WorkoutSession>, daysPerWeek: Int, today: LocalDate, zone: ZoneId): Int {
    if (finished.isEmpty()) return 0
    val perWeek = finished.groupingBy {
        BadgeRules.weekOf(Instant.ofEpochMilli(it.startedAtMillis).atZone(zone).toLocalDate())
    }.eachCount()
    val goal = daysPerWeek.coerceAtLeast(1)
    var week = BadgeRules.weekOf(today)
    if ((perWeek[week] ?: 0) < goal) week = week.minusWeeks(1)
    var count = 0
    while ((perWeek[week] ?: 0) >= goal) {
        count++
        week = week.minusWeeks(1)
    }
    return count
}

/**
 * This week's completion board, loaded only when asked.
 *
 * Opening it uploads this week's workout summaries, so it must not happen just because someone
 * looked at their profile: that would send training data to the server on a screen where the user
 * expects to be alone. The card states what the action does before offering it.
 */
@Composable
private fun WeeklyBoard(vm: GymViewModel) {
    var opened by rememberSaveable { mutableStateOf(false) }
    val board = vm.leaderboard
    Panel {
        Text("本周自律排行榜", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            if (opened) "完成率 = 本周完成次数 ÷ 自己计划的天数，每周一清零。练 2 天和练 5 天的人公平比较。"
            else "查看时会把这周的训练摘要（时长、组数）上传到 ReadyGym 服务器，只用于排名，不含动作细节。",
            fontSize = 12.sp, lineHeight = 18.sp, color = ReadyGymColors.MutedText,
        )
        when {
            !opened -> SecondaryButton("查看本周排名", { opened = true; vm.refreshLeaderboard() },
                Modifier.testTag("board-open"))
            vm.leaderboardError != null -> {
                Notice(vm.leaderboardError!!)
                SecondaryButton("重试", vm::refreshLeaderboard, Modifier.testTag("board-retry"))
            }
            board?.me != null -> Text(
                "我的排名：第 ${board.me.rank} / ${board.participants} 名 · ${board.me.done}/${board.me.planned} 次 · ${(board.me.rate * 100).toInt()}%",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ReadyGymColors.AccentText,
                modifier = Modifier.testTag("board-me"),
            )
            board != null -> Text("这周还没有训练，完成一次就能上榜。", fontSize = 14.sp, color = ReadyGymColors.AccentText)
            vm.leaderboardLoading -> Text("正在加载…", fontSize = 13.sp, color = ReadyGymColors.MutedText)
        }
    }
    if (opened) board?.entries?.take(5)?.forEach { RankRow(it) }
}@Composable
private fun RankRow(e: RankEntry) {
    Row(
        Modifier.fillMaxWidth().clip(GymShapes.Md)
            .background(if (e.me) ReadyGymColors.AccentSurface else ReadyGymColors.Surface)
            .padding(horizontal = GymSpacing.Lg, vertical = GymSpacing.Md).testTag("rank-${e.rank}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(when (e.rank) { 1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; else -> "${e.rank}" },
            fontSize = if (e.rank <= 3) 20.sp else 14.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(32.dp))
        Spacer(Modifier.width(GymSpacing.Sm))
        Column(Modifier.weight(1f)) {
            Text(e.nickname + if (e.me) "（我）" else "", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(5.dp).clip(GymShapes.Xs).background(ReadyGymColors.Sunken)) {
                Box(Modifier.fillMaxWidth(e.rate.toFloat().coerceIn(0f, 1f)).height(5.dp).background(ReadyGymColors.Accent))
            }
        }
        Spacer(Modifier.width(GymSpacing.Md))
        Text("${(e.rate * 100).toInt()}%", fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}



/** Badge progress as one line plus the next two conditions, instead of the full 11-icon wall. */
@Composable
private fun BadgeSummary(history: List<WorkoutSession>, daysPerWeek: Int) {
    val earned = remember(history, daysPerWeek) { BadgeRules.earned(history, daysPerWeek) }
    val total = Badge.entries.size
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("勋章", Modifier.padding(top = 0.dp).weight(1f))
            Text("$total 个里点亮 ${earned.size} 个", fontSize = 12.sp, color = ReadyGymColors.MutedText)
        }
        Row(Modifier.fillMaxWidth().height(8.dp).clip(GymShapes.Xs).background(ReadyGymColors.Sunken)) {
            Box(Modifier.fillMaxWidth(earned.size.toFloat() / total).height(8.dp)
                .clip(GymShapes.Xs).background(ReadyGymColors.Accent))
        }
        val next = com.readygym.core.workout.Badge.entries.filter { it !in earned }.take(2)
        if (next.isEmpty()) Text("全部点亮了。", fontSize = 13.sp, color = ReadyGymColors.AccentText)
        else next.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(ReadyGymColors.Sunken), contentAlignment = Alignment.Center) {
                    Text(b.icon, fontSize = 15.sp)
                }
                Spacer(Modifier.width(GymSpacing.Sm))
                Text(b.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(GymSpacing.Sm))
                Text(b.hint, fontSize = 12.sp, color = ReadyGymColors.MutedText, modifier = Modifier.weight(1f))
            }
        }
    }
}
