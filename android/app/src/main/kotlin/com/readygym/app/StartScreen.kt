package com.readygym.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.WorkoutPlan
import java.time.LocalDate
import java.time.ZoneId

/**
 * 开始: what to do right now.
 *
 * The screen answers one question — "which day am I on, and how do I begin it" — in that order:
 * a headline that says whether today is a training day or a rest day, the day the plan put next,
 * then the other days as a list of equal-weight rows. Nothing here explains the plan; the plan
 * screen is one tap away and the coach conversation is where the reasoning lives.
 *
 * Rows carry the whole tap target rather than a small button: the row is 60dp tall and the row
 * itself is clickable, with a pill on the right that is the visual affordance.
 */
@Composable
fun StartScreen(
    vm: GymViewModel,
    onCreateProfile: () -> Unit,
    onOpenPlan: () -> Unit,
    onStartDay: (Int) -> Unit,
) {
    val profile by vm.profile.collectAsState()
    val plan by vm.activePlan.collectAsState()
    val open by vm.openSession.collectAsState()
    val p = profile.valueOrNull()
    val workout = plan.valueOrNull()
    val inProgress = open.valueOrNull()

    val today = java.time.LocalDate.now(ZoneId.systemDefault())
    val plannedToday = p?.trainingDays?.contains(today.dayOfWeek.value) == true

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = GymSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
    ) {
        Spacer(Modifier.height(GymSpacing.Md))
        Text("开始训练", fontSize = 29.sp, lineHeight = 35.sp, fontWeight = FontWeight.ExtraBold,
            color = ReadyGymColors.Text)
        Text(
            when {
                inProgress != null -> "有一次训练还没结束，接着练就行。"
                workout == null -> "还没有计划。先让 AI 教练了解你，再排一份。"
                plannedToday -> "今天是训练日，按计划练第 ${vm.nextDay + 1} 天。"
                else -> "今天不是计划里的训练日。想练也可以，身体说了算。"
            },
            fontSize = 14.sp, lineHeight = 21.sp, color = ReadyGymColors.MutedText,
        )

        when {
            profile is Loaded.Loading || plan is Loaded.Loading ->
                Box(Modifier.fillMaxWidth().padding(top = 60.dp), Alignment.Center) { CircularProgressIndicator() }
            p == null -> StartEmptyState(
                title = "先认识一下你",
                body = "年龄、身高体重、目标和器械决定了练什么、练多重。聊两句就能建档。",
                action = "和 AI 教练聊着建档", onAction = onCreateProfile,
                testTag = "start-create-profile",
            )
            workout == null -> StartEmptyState(
                title = "还没有训练计划",
                body = "教练会根据你的档案挑选动作，排好一周的组数和次数，你确认后才会开始。",
                action = "去计划页排一份", onAction = onOpenPlan,
                testTag = "start-go-plan",
            )
            else -> {
                val next = vm.nextDay.coerceIn(workout.days.indices)
                val todayIndex = (inProgress?.dayIndex ?: next).coerceIn(workout.days.indices)
                // On a rest day the plan still has a next day, but presenting it as "today" would
                // contradict the line above. It is shown as "下一次" instead — an answer to "so
                // which one should I do?", which four equal rows do not give.
                SectionLabel(
                    when {
                        inProgress != null -> "进行中"
                        plannedToday -> "今天"
                        else -> "下一次"
                    }
                )
                StartDayRow(
                    day = workout.days[todayIndex],
                    selected = true,
                    actionLabel = if (inProgress != null) "继续" else "开始",
                    testTag = "start-day-primary",
                    onClick = { onStartDay(todayIndex) },
                )

                if (workout.days.size > 1) {
                    SectionLabel("其他训练日")
                    workout.days.forEachIndexed { i, day ->
                        if (i != todayIndex) {
                            StartDayRow(
                                day = day,
                                selected = false,
                                actionLabel = "开始",
                                testTag = "start-day-$i",
                                onClick = { onStartDay(i) },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(GymSpacing.Xxl))
    }
}

/** The same shape as a normal row, but it is the whole answer on an empty screen. */
@Composable
private fun StartEmptyState(title: String, body: String, action: String, onAction: () -> Unit, testTag: String) {
    Panel {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(body, fontSize = 14.sp, lineHeight = 21.sp, color = ReadyGymColors.MutedText)
        Spacer(Modifier.height(GymSpacing.Sm))
        PrimaryButton(action, onAction, Modifier.testTag(testTag))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.MutedText,
        modifier = Modifier.padding(top = GymSpacing.Sm))
}

/**
 * One training day: a single light row, not a card.
 *
 * Measured against the reference, the previous version ran 44dp tiles, 38dp pills and 16dp gaps
 * where the reference uses 29dp, 21dp and 8dp. At this row height the difference is the difference
 * between a list you scan and four cards you have to read one by one, so the numbers below are the
 * reference's, not a round number.
 */
@Composable
private fun StartDayRow(
    day: PlanDay,
    selected: Boolean,
    actionLabel: String,
    testTag: String,
    onClick: () -> Unit,
) {
    val icon = iconFor(day)
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(GymShapes.Md)
            .background(ReadyGymColors.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = GymSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(29.dp).clip(RoundedCornerShape(7.dp)).background(ReadyGymColors.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(17.dp), tint = ReadyGymColors.OnAction)
        }
        Spacer(Modifier.width(GymSpacing.Md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(day.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ReadyGymColors.Text,
                maxLines = 1)
            Text(
                listOfNotNull("${day.items.size} 个动作", day.items.sumOf { it.sets }.takeIf { it > 0 }?.let { "$it 组" })
                    .joinToString(" · "),
                fontSize = 12.sp, color = ReadyGymColors.MutedText, maxLines = 1,
            )
        }
        Spacer(Modifier.width(GymSpacing.Sm))
        DayPill(actionLabel, selected, Modifier.testTag(testTag))
    }
}

/** The right-hand affordance. Not separately clickable: the row owns the tap. */
@Composable
private fun DayPill(label: String, emphasised: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp))
            .background(if (emphasised) ReadyGymColors.Accent else ReadyGymColors.AccentWash)
            .padding(horizontal = GymSpacing.Sm, vertical = 5.dp),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = if (emphasised) ReadyGymColors.OnAction else ReadyGymColors.AccentText)
    }
}

/**
 * The day's focus decides the glyph. The plan's own `focus` text is free Chinese ("胸肩三头"),
 * so the mapping is by keyword and falls back to a dumbbell rather than inventing a category.
 */
private fun iconFor(day: PlanDay): ImageVector {
    val text = (day.title + day.focus).lowercase()
    return when {
        listOf("腿", "臀", "下肢", "深蹲", "leg", "glute").any { text.contains(it) } -> GymIcons.Legs
        listOf("拉", "背", "二头", "pull", "back").any { text.contains(it) } -> GymIcons.Cable
        listOf("推", "胸", "肩", "三头", "push", "chest").any { text.contains(it) } -> GymIcons.Dumbbell
        else -> GymIcons.Dumbbell
    }
}
