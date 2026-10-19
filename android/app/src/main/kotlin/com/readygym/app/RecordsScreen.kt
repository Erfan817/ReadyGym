package com.readygym.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.plan.formatKg
import com.readygym.core.profile.weekdayLabel
import com.readygym.core.workout.Badge
import com.readygym.core.workout.BadgeRules
import com.readygym.core.workout.SessionAward
import com.readygym.core.workout.WorkoutSession
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 记录: a month calendar of workouts, the selected day's sessions, and the badge wall. */
@Composable
fun RecordsScreen(vm: GymViewModel) {
    val history by vm.history.collectAsState()
    val profile by vm.profile.collectAsState()
    val p = profile.valueOrNull()
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    var monthIndex by rememberSaveable { mutableStateOf(YearMonth.from(today).let { it.year * 12 + it.monthValue - 1 }) }
    val month = YearMonth.of(monthIndex / 12, monthIndex % 12 + 1)
    var selectedEpochDay by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedEpochDay)

    val byDay = remember(history) { history.groupBy { Instant.ofEpochMilli(it.startedAtMillis).atZone(zone).toLocalDate() } }
    val awards = remember(history, p?.daysPerWeek) {
        BadgeRules.awards(history, p?.daysPerWeek ?: 3, zone).associateBy { it.sessionId }
    }
    val thisWeek = BadgeRules.weekOf(today)
    val weekCount = byDay.filterKeys { BadgeRules.weekOf(it) == thisWeek }.values.sumOf { it.size }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(GymSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
    ) {
        ScreenTitle("记录", if (history.isEmpty()) "完成训练后，日历上会盖一个章" else
            "共 ${history.size} 次训练 · 本周 $weekCount/${p?.daysPerWeek ?: "-"} 次")
        var showBoard by rememberSaveable { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
            Chip("日历", !showBoard, { showBoard = false }, modifier = Modifier.testTag("records-calendar"))
            Chip("自律排行榜", showBoard, { showBoard = true }, modifier = Modifier.testTag("records-board"))
        }
        if (showBoard) { LeaderboardView(vm); return@Column }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { monthIndex-- }, modifier = Modifier.testTag("cal-prev")) { Text("‹", fontSize = 20.sp) }
                Text("${month.year}年${month.monthValue}月", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f).testTag("cal-title"))
                TextButton(onClick = { monthIndex++ }, enabled = month < YearMonth.from(today),
                    modifier = Modifier.testTag("cal-next")) { Text("›", fontSize = 20.sp) }
            }
            MonthGrid(month, today, selected, byDay, awards, p?.trainingDays.orEmpty()) { selectedEpochDay = it.toEpochDay() }
            Legend(p?.trainingDays.orEmpty())
        }
        val sessions = byDay[selected].orEmpty()
        SectionHeader("${selected.monthValue}月${selected.dayOfMonth}日 ${weekdayLabel(selected.dayOfWeek.value)}")
        if (sessions.isEmpty()) Text(
            if (selected.dayOfWeek.value in p?.trainingDays.orEmpty() && !selected.isAfter(today)) "计划的训练日，这天没有训练记录。" else "这天没有训练。",
            color = ReadyGymColors.MutedText, fontSize = 14.sp)
        sessions.forEach { SessionCard(it, vm, awards[it.id], zone) }
        BadgeWall(BadgeRules.earned(history, p?.daysPerWeek ?: 3, zone))
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth, today: LocalDate, selected: LocalDate, byDay: Map<LocalDate, List<WorkoutSession>>,
    awards: Map<Long, SessionAward>, trainingDays: Set<Int>, onPick: (LocalDate) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        (1..7).forEach { Text("一二三四五六日"[it - 1].toString(), Modifier.weight(1f), textAlign = TextAlign.Center,
            fontSize = 12.sp, color = ReadyGymColors.MutedText) }
    }
    val lead = month.atDay(1).dayOfWeek.value - 1
    val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    cells.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            week.forEach { date ->
                Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                    if (date != null) DayCell(date, today, date == selected, byDay[date].orEmpty(), awards,
                        planned = date.dayOfWeek.value in trainingDays) { onPick(date) }
                }
            }
            repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, today: LocalDate, selected: Boolean, sessions: List<WorkoutSession>,
                    awards: Map<Long, SessionAward>, planned: Boolean, onClick: () -> Unit) {
    val trained = sessions.isNotEmpty()
    // The day's stamp: the first new badge earned that day, otherwise a plain check-in star.
    val stamp = sessions.firstNotNullOfOrNull { awards[it.id]?.unlocked?.firstOrNull() }?.icon ?: "⭐"
    val future = date.isAfter(today)
    Column(
        Modifier.fillMaxSize().clip(GymShapes.Sm)
            .background(when { selected -> ReadyGymColors.AccentWash; trained -> ReadyGymColors.AccentSurface; else -> ReadyGymColors.Surface })
            .then(if (planned && !trained) Modifier.border(1.dp, ReadyGymColors.Outline, GymShapes.Sm) else Modifier)
            .clickable(enabled = !future, onClick = onClick)
            .semantics { contentDescription = "${date.monthValue}月${date.dayOfMonth}日" + if (trained) "，已训练 ${sessions.size} 次" else "" }
            .testTag("cal-day-${date.dayOfMonth}"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text("${date.dayOfMonth}", fontSize = 13.sp,
            fontWeight = if (date == today) FontWeight.ExtraBold else FontWeight.Normal,
            color = when { future -> ReadyGymColors.FaintText; date == today -> ReadyGymColors.AccentText; else -> ReadyGymColors.Text })
        if (trained) Text(stamp, fontSize = 14.sp)
    }
}

@Composable
private fun Legend(trainingDays: Set<Int>) {
    Row(horizontalArrangement = Arrangement.spacedBy(GymSpacing.Lg)) {
        Text("⭐ 打卡  🏅 新勋章", fontSize = 12.sp, color = ReadyGymColors.MutedText)
        if (trainingDays.isNotEmpty()) Text("▢ 计划训练日", fontSize = 12.sp, color = ReadyGymColors.MutedText)
    }
}

@Composable
private fun SessionCard(s: WorkoutSession, vm: GymViewModel, award: SessionAward?, zone: ZoneId) {
    val start = Instant.ofEpochMilli(s.startedAtMillis).atZone(zone)
    Panel(Modifier.testTag("record-${s.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.dayTitle, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("%02d:%02d".format(start.hour, start.minute), fontSize = 13.sp, color = ReadyGymColors.MutedText)
        }
        Text(listOfNotNull(s.durationMinutes?.let { "${it.coerceAtLeast(1)} 分钟" }, "${s.completedSets.size} 组",
            s.volumeKg.takeIf { it > 0 }?.let { "${formatKg(it)} kg" }, "计划 v${s.planVersion}").joinToString(" · "),
            fontSize = 13.sp, color = ReadyGymColors.MutedText)
        award?.let { AwardRow(it) }
        s.completedSets.groupBy { it.exerciseId }.forEach { (id, sets) ->
            Row(Modifier.fillMaxWidth()) {
                Text(vm.library[id]?.name ?: id, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(sets.joinToString("  ") { shortSet(it) }, fontSize = 13.sp, color = ReadyGymColors.MutedText)
            }
        }
    }
}

/** The stamp every finished workout earns, followed by any badge it unlocked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AwardRow(award: SessionAward) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(GymSpacing.Sm), verticalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
        BadgeChip("⭐", "第 ${award.ordinal} 次打卡")
        award.unlocked.forEach { BadgeChip(it.icon, it.title) }
    }
}

@Composable
private fun BadgeChip(icon: String, text: String) {
    Row(Modifier.clip(GymShapes.Sm).background(ReadyGymColors.AccentWash).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 14.sp)
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.AccentText)
    }
}

@Composable
private fun BadgeWall(earned: Set<Badge>) {
    SectionHeader("我的勋章 ${earned.size}/${Badge.entries.size}")
    Panel {
        Badge.entries.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { b ->
                    val has = b in earned
                    Column(Modifier.weight(1f).padding(vertical = 6.dp).testTag("badge-${b.name}")
                        .semantics { contentDescription = "${b.title}，${b.hint}，" + if (has) "已获得" else "未获得" },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(48.dp).clip(CircleShape)
                            .background(if (has) ReadyGymColors.AccentWash else ReadyGymColors.Sunken), contentAlignment = Alignment.Center) {
                            Text(b.icon, fontSize = 22.sp, modifier = Modifier.alpha(if (has) 1f else 0.25f))
                        }
                        Text(b.title, fontSize = 11.sp, textAlign = TextAlign.Center,
                            color = if (has) ReadyGymColors.Text else ReadyGymColors.FaintText, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text("点亮条件：" + Badge.entries.filter { it !in earned }.take(2).joinToString("；") { "${it.title}——${it.hint}" }.ifBlank { "全部已点亮！" },
            fontSize = 12.sp, color = ReadyGymColors.MutedText, lineHeight = 18.sp)
    }
}

/** This week's ranking by completion rate; opening it also syncs this week's workouts. */
@Composable
private fun LeaderboardView(vm: GymViewModel) {
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshLeaderboard() }
    val board = vm.leaderboard
    Panel(container = ReadyGymColors.AccentWash) {
        Text("本周自律排行榜", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("完成率 = 本周完成次数 ÷ 自己计划的天数，每周一清零。练 2 天和练 5 天的人公平比较。",
            fontSize = 12.sp, lineHeight = 18.sp, color = ReadyGymColors.MutedText)
        board?.me?.let { me ->
            Text("我的排名：第 ${me.rank} / ${board.participants} 名 · ${me.done}/${me.planned} 次 · ${(me.rate * 100).toInt()}%",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ReadyGymColors.AccentText,
                modifier = Modifier.testTag("board-me"))
        } ?: board?.let { Text("这周还没有训练，完成一次就能上榜。", fontSize = 14.sp, color = ReadyGymColors.AccentText) }
    }
    vm.leaderboardError?.let {
        Notice(it)
        SecondaryButton("重试", vm::refreshLeaderboard)
    }
    if (board == null && vm.leaderboardLoading) Text("正在加载…", color = ReadyGymColors.MutedText)
    if (board != null && board.entries.isEmpty()) Text("本周还没有人上榜，做第一个吧！", color = ReadyGymColors.MutedText)
    board?.entries?.forEach { RankRow(it) }
}

@Composable
private fun RankRow(e: RankEntry) {
    Row(
        Modifier.fillMaxWidth().clip(GymShapes.Md)
            .background(if (e.me) ReadyGymColors.AccentSurface else ReadyGymColors.Surface)
            .padding(horizontal = GymSpacing.Lg, vertical = GymSpacing.Md).testTag("rank-${e.rank}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(when (e.rank) { 1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; else -> "${e.rank}" }, fontSize = if (e.rank <= 3) 22.sp else 15.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(36.dp))
        Spacer(Modifier.width(GymSpacing.Sm))
        Column(Modifier.weight(1f)) {
            Text(e.nickname + if (e.me) "（我）" else "", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(GymShapes.Xs).background(ReadyGymColors.Sunken)) {
                Box(Modifier.fillMaxWidth(e.rate.toFloat().coerceIn(0f, 1f)).height(6.dp).background(ReadyGymColors.Accent))
            }
        }
        Spacer(Modifier.width(GymSpacing.Md))
        Column(horizontalAlignment = Alignment.End) {
            Text("${(e.rate * 100).toInt()}%", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("${e.done}/${e.planned} 次", fontSize = 12.sp, color = ReadyGymColors.MutedText)
        }
    }
}
