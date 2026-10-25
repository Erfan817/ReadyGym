package com.readygym.app

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.Gender
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.UserProfile
import com.readygym.core.workout.WorkoutSession
import java.time.LocalDate

/**
 * 首页 answers the next useful action: meet the coach, make a plan, or start/continue training.
 * An unfinished session owns its title and counts even when the active plan has since changed.
 */
@Composable
fun CoachHomeScreen(
    vm: GymViewModel,
    nickname: String?,
    consented: Boolean,
    serviceConfigured: Boolean,
    onOpenCoach: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenEquipment: () -> Unit = {},
    /** What the user said they are in the current chat, so home matches the chat avatar. */
    coachGender: Gender? = null,
    onStartTraining: () -> Unit = {},
) {
    val profile by vm.profile.collectAsState()
    val plan by vm.activePlan.collectAsState()
    val history by vm.history.collectAsState()
    val open by vm.openSession.collectAsState()
    val p = profile.valueOrNull()
    val workout = plan.valueOrNull()
    // Same derivation as the chat screen: what was said in chat wins, else the saved profile,
    // else the default male face — so the two pages never show different coaches.
    val face = coachGender ?: p?.gender
    val primary = homePrimaryState(p, workout, open.valueOrNull(), vm.nextDay, LocalDate.now(), consented)
    val loading = profile is Loaded.Loading || plan is Loaded.Loading || open is Loaded.Loading

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = GymSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Lg),
    ) {
        Spacer(Modifier.height(GymSpacing.Lg))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Ready Gym", fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
                    color = ReadyGymColors.MutedText)
                Text(greeting(nickname), fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold,
                    color = ReadyGymColors.Text, modifier = Modifier.semantics { heading() })
            }
            CoachFace(face, 52)
        }

        if (loading) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            HomePrimaryCard(primary, face, onOpen = when (primary.destination) {
                HomeDestination.COACH -> onOpenCoach
                HomeDestination.PLAN -> onOpenPlan
                HomeDestination.TRAINING -> onStartTraining
            })
        }

        if (!loading && primary.destination != HomeDestination.COACH) {
            StateRow(
                label = "AI 教练",
                value = "聊聊训练，调整计划",
                detail = coachSubtitle(consented, serviceConfigured),
                icon = GymIcons.Person,
                onClick = onOpenCoach,
                testTag = "home-open-coach",
            )
        }

        StateRow(
            label = "器械指南",
            value = "不会用眼前这台器械？",
            detail = "拍张照找用法，也可以按名称查找",
            icon = GymIcons.Camera,
            onClick = onOpenEquipment,
            testTag = "home-equipment-row",
            raised = true,
        )

        if (p != null) {
            SectionHeader("训练概况")
            StateRow(
                label = "训练档案",
                value = "${p.goal.label} · 每周 ${p.daysPerWeek} 天 · 每次 ${p.minutesPerSession} 分钟",
                detail = "${p.age} 岁 · ${p.heightCm.toInt()} cm · ${p.weightKg.toInt()} kg · ${p.experience.label}",
                icon = GymIcons.Person,
                onClick = null,
            )
            StateRow(
                label = "当前计划",
                value = workout?.let { "每周 ${it.days.size} 练 · 共 ${it.days.sumOf { d -> d.items.size }} 个动作" }
                    ?: "还没有计划",
                detail = workout?.let { "查看每天的安排，也可以按肌肉添加动作" }
                    ?: "先生成草稿，确认后再开始练",
                icon = GymIcons.Calendar,
                onClick = onOpenPlan,
                testTag = "home-plan-row",
            )
            if (history.isNotEmpty()) {
                StateRow(
                    label = "最近一次",
                    value = history.first().dayTitle,
                    detail = listOfNotNull(
                        history.first().durationMinutes?.let { "练了 ${it.coerceAtLeast(1)} 分钟" },
                        "${history.first().completedSets.size} 组",
                        "累计 ${history.size} 次",
                    ).joinToString(" · "),
                    icon = GymIcons.Bars,
                    onClick = null,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(GymSpacing.Xs)) {
            Text("安全提醒", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.Text)
            Text(
                "AI 建议不能替代专业指导。训练中出现胸痛、眩晕或关节刺痛，先停下来，必要时就医。",
                fontSize = 13.sp, lineHeight = 20.sp, color = ReadyGymColors.MutedText,
            )
        }
        Spacer(Modifier.height(GymSpacing.Xxl))
    }
}

/**
 * A single primary card; the whole surface and its button lead to the same next step.
 */
@Composable
private fun HomePrimaryCard(
    state: HomePrimaryState,
    face: Gender?,
    onOpen: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(GymShapes.Xl)
            .background(ReadyGymColors.AccentWash)
            .clickable(role = Role.Button, onClick = onOpen)
            .testTag(when (state.destination) {
                HomeDestination.COACH -> "home-open-coach"
                HomeDestination.PLAN -> "home-generate-plan"
                HomeDestination.TRAINING -> "home-start-training"
            })
            .padding(GymSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.label, modifier = Modifier.weight(1f), fontSize = 13.sp,
                fontWeight = FontWeight.Bold, color = ReadyGymColors.AccentText)
            if (state.destination == HomeDestination.COACH) CoachFace(face, 40)
            else Icon(GymIcons.Dumbbell, null, Modifier.size(24.dp), tint = ReadyGymColors.AccentText)
        }
        Text(state.title, fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold,
            color = ReadyGymColors.Text)
        Text(state.detail, fontSize = 15.sp, lineHeight = 23.sp, color = ReadyGymColors.Text)
        Text(state.hint, fontSize = 13.sp, lineHeight = 20.sp, color = ReadyGymColors.MutedText)
        Box(
            Modifier.fillMaxWidth().height(54.dp).clip(GymShapes.Md).background(ReadyGymColors.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(state.action, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.OnAccent)
        }
    }
}

/** Name over a one-line fact: the shape used for 档案 / 计划 / 最近一次. */
@Composable
private fun StateRow(
    label: String,
    value: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (() -> Unit)?,
    testTag: String? = null,
    raised: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(GymShapes.Md)
            .then(if (raised) Modifier.background(ReadyGymColors.Surface) else Modifier)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(GymSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(GymShapes.Sm).background(ReadyGymColors.AccentWash),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = ReadyGymColors.AccentText)
        }
        Spacer(Modifier.width(GymSpacing.Md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 12.sp, color = ReadyGymColors.MutedText)
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ReadyGymColors.Text)
            Text(detail, fontSize = 12.sp, lineHeight = 17.sp, color = ReadyGymColors.MutedText)
        }
        if (onClick != null) {
            Icon(GymIcons.ChevronRight, null, Modifier.size(18.dp), tint = ReadyGymColors.FaintText)
        }
    }
}

@Composable
private fun CoachFace(gender: Gender?, size: Int) {
    Image(
        painterResource(coachAvatarFor(gender)),
        contentDescription = null,
        modifier = Modifier.size(size.dp).clip(CircleShape),
        contentScale = ContentScale.Crop,
    )
}

/** Time-of-day greeting; the nickname is optional because the account may not be set yet. */
private fun greeting(nickname: String?): String {
    val hour = java.time.LocalTime.now().hour
    val part = when {
        hour < 5 -> "夜深了"
        hour < 11 -> "早上好"
        hour < 14 -> "中午好"
        hour < 18 -> "下午好"
        else -> "晚上好"
    }
    return if (nickname.isNullOrBlank()) part else "$part，$nickname"
}

private fun coachSubtitle(consented: Boolean, serviceConfigured: Boolean): String = when {
    !serviceConfigured -> "暂时连不上，稍后再试"
    !consented -> "首次使用需要你知情同意"
    else -> "想调整天数、强度，直接告诉教练"
}

internal enum class HomeDestination { COACH, PLAN, TRAINING }

internal data class HomePrimaryState(
    val label: String,
    val title: String,
    val detail: String,
    val hint: String,
    val action: String,
    val destination: HomeDestination,
)

/** Kept independent of Compose so plan replacement and rest-day wording can be checked directly. */
internal fun homePrimaryState(
    profile: UserProfile?,
    plan: WorkoutPlan?,
    openSession: WorkoutSession?,
    nextDay: Int,
    today: LocalDate,
    consented: Boolean,
): HomePrimaryState {
    if (openSession != null) return HomePrimaryState(
        label = "训练进行中",
        title = openSession.dayTitle,
        detail = "${openSession.sets.map { it.exerciseId }.distinct().size} 个动作 · 已完成 ${openSession.completedSets.size}/${openSession.sets.size} 组",
        hint = "按上次的安排接着练，已经完成的组都还在。",
        action = "继续训练",
        destination = HomeDestination.TRAINING,
    )
    if (profile == null) return HomePrimaryState(
        label = "从这里开始",
        title = "先让教练认识你",
        detail = "聊聊你的目标、训练时间和手边的器械。",
        hint = "整理好档案，再一起安排适合你的训练。",
        action = if (consented) "继续建档" else "和教练聊聊",
        destination = HomeDestination.COACH,
    )
    val day = plan?.days?.takeIf { it.isNotEmpty() }?.let { it[nextDay.coerceIn(it.indices)] }
    if (day == null) return HomePrimaryState(
        label = "档案已就绪",
        title = "排好计划，再开始练",
        detail = "目标${profile.goal.label} · 每周 ${profile.daysPerWeek} 天 · 每次 ${profile.minutesPerSession} 分钟",
        hint = "先生成一份草稿，你确认后才会采用。",
        action = "去生成计划",
        destination = HomeDestination.PLAN,
    )
    val plannedToday = today.dayOfWeek.value in profile.trainingDays
    return HomePrimaryState(
        label = if (plannedToday) "今天的训练" else "下一次训练",
        title = day.title,
        detail = "${day.items.size} 个动作 · ${day.items.sumOf { it.sets }} 组",
        hint = when {
            plannedToday -> "今天是训练日，按自己的状态开始。"
            profile.trainingDays.isEmpty() -> "训练时间由你安排，准备好了就开始。"
            else -> "今天可以休息；这是下一次的安排。"
        },
        action = "开始训练",
        destination = HomeDestination.TRAINING,
    )
}
