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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.ai.PlanProposal
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile

/**
 * 计划: profile → AI proposal → confirmed plan.
 *
 * This is the only screen that talks about the *whole week*; 开始 only ever shows the next day's
 * work. Keeping the proposal review here means the confirmation step can never be skipped by
 * tapping "start" on the home or start screen.
 */
@Composable
fun PlanScreen(
    vm: GymViewModel,
    onCreateProfile: () -> Unit,
    onOpenCoach: () -> Unit,
    onStartDay: (Int) -> Unit,
    onPickMuscle: () -> Unit = {},
    profileUpdated: Boolean = false,
    onProfilePlanConfirmed: () -> Unit = {},
) {
    val profile by vm.profile.collectAsState()
    val plan by vm.activePlan.collectAsState()
    val proposal = vm.proposal

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = GymSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
    ) {
        Spacer(Modifier.height(GymSpacing.Lg))
        if (profileUpdated && profile.valueOrNull() != null && proposal == null) {
            Panel(container = ReadyGymColors.AccentWash, modifier = Modifier.testTag("plan-profile-saved")) {
                Text("档案已保存", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (plan.valueOrNull() == null) "下一步，给自己排一份训练计划。" else
                    "按新资料排一份草稿，确认采用后才会替换当前计划。进行中的训练照常保留。",
                    fontSize = 14.sp, lineHeight = 21.sp, color = ReadyGymColors.MutedText)
                if (plan.valueOrNull() != null) PrimaryButton("按新档案生成计划草稿", vm::generatePlan,
                    Modifier.testTag("plan-generate-updated"), busy = vm.generating)
            }
        }
        when {
            profile is Loaded.Loading || plan is Loaded.Loading ->
                Box(Modifier.fillMaxWidth().padding(top = 120.dp), Alignment.Center) { CircularProgressIndicator() }
            profile.valueOrNull() == null -> {
                PageTitle("计划", "先让教练了解你，再排计划。", onPickMuscle)
                NoProfile(onCreateProfile, onOpenCoach)
            }
            proposal != null -> {
                PageTitle(if (plan.valueOrNull() == null) "计划草稿" else "新计划草稿", proposal.plan.summary.ifBlank { null }, onPickMuscle)
                ProposalView(vm, proposal, replacing = plan.valueOrNull(), onConfirmed = onProfilePlanConfirmed)
            }
            plan.valueOrNull() == null -> {
                PageTitle("计划", "档案已就绪。", onPickMuscle)
                NoPlan(vm, requireNotNull(profile.valueOrNull()), onOpenCoach)
            }
            else -> {
                val saved = requireNotNull(plan.valueOrNull())
                PageTitle("计划", "v${saved.version} · 每周 ${saved.daysPerWeek} 练", onPickMuscle)
                PlanView(vm, saved, onOpenCoach, onStartDay)
            }
        }
        Spacer(Modifier.height(GymSpacing.Xxl))
    }
}

@Composable
private fun NoProfile(onCreateProfile: () -> Unit, onOpenCoach: () -> Unit) {
    Panel(container = ReadyGymColors.AccentWash) {
        Text("还没有你的档案", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("年龄、身高体重、目标、器械和每周时间，决定计划的强度和动作。", fontSize = 14.sp,
            lineHeight = 21.sp, color = ReadyGymColors.MutedText)
        PrimaryButton("填写档案（1 分钟）", onCreateProfile, Modifier.testTag("train-create-profile"))
        SecondaryButton("和 AI 教练聊着建档", onOpenCoach)
    }
}

@Composable
private fun NoPlan(vm: GymViewModel, profile: UserProfile, onOpenCoach: () -> Unit) {
    Panel {
        Text("生成你的训练计划", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("教练会根据档案从 ${vm.library.exercises.size} 个带演示的动作里挑选，安排每天的组数、次数和重量。生成后先给你看，确认了才会保存。",
            fontSize = 14.sp, lineHeight = 21.sp, color = ReadyGymColors.MutedText)
        if (profile.experience != Experience.EXPERIENCED) {
            Text("已按「${profile.experience.label}」筛选动作：硬拉、杠铃深蹲这类高风险动作不会进你的计划，想练它们先找现场教练指导。",
                fontSize = 13.sp, lineHeight = 19.sp, color = ReadyGymColors.AccentText,
                modifier = Modifier.testTag("plan-safety-note"))
        }
        vm.planError?.let { Notice(it) }
        PrimaryButton(if (vm.generating) "教练正在排计划…" else "生成计划", vm::generatePlan,
            Modifier.testTag("train-generate"), busy = vm.generating)
        if (vm.generating) Text("通常需要 20–60 秒。", fontSize = 12.sp, color = ReadyGymColors.MutedText)
        SecondaryButton("先去与教练对话", onOpenCoach)
    }
}

@Composable
private fun ProposalView(vm: GymViewModel, proposal: PlanProposal, replacing: WorkoutPlan?, onConfirmed: () -> Unit) {
    if (proposal.message.isNotBlank()) {
        Panel(container = ReadyGymColors.AccentWash) {
            Text("教练说", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.AccentText)
            Text(proposal.message, lineHeight = 22.sp)
        }
    }
    proposal.plan.days.forEachIndexed { i, day -> DayCard(i, day, vm.library, expanded = true) }
    if (replacing != null) Text("确认后替换当前计划（v${replacing.version}），旧版本仍保留在记录里。",
        fontSize = 12.sp, color = ReadyGymColors.MutedText)
    PrimaryButton("采用这个计划", { vm.confirmProposal(); onConfirmed() }, Modifier.testTag("proposal-confirm"))
    SecondaryButton("不满意，重新生成", { vm.discardProposal(); vm.generatePlan() }, enabled = !vm.generating)
    TextButton(onClick = vm::discardProposal, modifier = Modifier.fillMaxWidth()) { Text("放弃", color = ReadyGymColors.MutedText) }
}

/**
 * The confirmed plan. The week is the subject here — every day is listed with its move count and
 * one tap to start it — while the hero block that used to say "继续训练" now belongs to 开始, so
 * this screen does not compete with it for the same action.
 */
@Composable
private fun PlanView(vm: GymViewModel, plan: WorkoutPlan, onOpenCoach: () -> Unit, onStartDay: (Int) -> Unit) {
    var confirmRegenerate by rememberSaveable { mutableStateOf(false) }
    val next = vm.nextDay.coerceIn(plan.days.indices)

    if (plan.summary.isNotBlank()) Text(plan.summary, fontSize = 14.sp, lineHeight = 21.sp, color = ReadyGymColors.MutedText)

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("本周安排", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("点标题展开动作；右侧直接开始这一天。", fontSize = 12.sp, color = ReadyGymColors.MutedText)
            }
            SmallTextAction("和教练说", onOpenCoach, "plan-open-coach")
        }
        plan.days.forEachIndexed { i, day ->
            PlanDayRow(day = day, index = i, isNext = i == next, library = vm.library, onStart = { onStartDay(i) })
        }
    }

    vm.planError?.let { Notice(it) }
    SecondaryButton(if (vm.generating) "教练正在排新计划…" else "根据档案重新生成计划",
        { confirmRegenerate = true }, enabled = !vm.generating)
    if (confirmRegenerate) AlertDialog(
        onDismissRequest = { confirmRegenerate = false },
        title = { Text("重新生成计划？") },
        text = { Text("会根据当前档案生成一份新草稿，你确认后才会替换。练后调整请在训练结束时进行。") },
        confirmButton = { TextButton(onClick = { confirmRegenerate = false; vm.generatePlan() }) { Text("生成") } },
        dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text("取消") } },
    )
}

/**
 * One training day, expandable in place: the header carries the number, the title and the start
 * action; the body is the prescribed moves with their GIF thumbnails.
 *
 * Only the day the plan will do next is tinted, and the open row is marked by its expanded body
 * rather than by a second green fill — the previous version tinted both, so "next" and "open" were
 * the same colour and meant two different things.
 */
@Composable
private fun PlanDayRow(
    day: PlanDay,
    index: Int,
    isNext: Boolean,
    library: ExerciseLibrary,
    onStart: () -> Unit,
) {
    var open by rememberSaveable(day.title, index) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(GymShapes.Md)
            .background(if (isNext) ReadyGymColors.AccentWash else ReadyGymColors.Sunken.copy(alpha = 0.4f))
            .padding(GymSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Sm),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (isNext) ReadyGymColors.Accent else ReadyGymColors.Surface),
                contentAlignment = Alignment.Center,
            ) {
                Text("${index + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (isNext) ReadyGymColors.OnAccent else ReadyGymColors.MutedText)
            }
            Spacer(Modifier.width(GymSpacing.Sm))
            Column(
                Modifier.weight(1f).clickable(role = Role.Button, onClick = { open = !open }),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(day.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    listOfNotNull("${day.items.size} 个动作", "${day.items.sumOf { it.sets }} 组",
                        day.focus.ifBlank { null }).joinToString(" · "),
                    fontSize = 12.sp, color = ReadyGymColors.MutedText, maxLines = 1,
                )
            }
            // 40dp tall: the previous 68.5dp button was taller than the two lines beside it and
            // took the row's visual centre of gravity away from the day's name.
            Box(
                Modifier.heightIn(min = 48.dp).clip(GymShapes.Sm).background(ReadyGymColors.Surface)
                    .clickable(role = Role.Button, onClick = onStart).testTag("plan-start-$index")
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("开始", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.AccentText) }
        }
        if (open) day.items.forEach { PlanItemRow(it, library) }
    }
}

@Composable
private fun SmallTextAction(label: String, onClick: () -> Unit, testTag: String) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(GymShapes.Sm).background(ReadyGymColors.AccentWash)
            .clickable(role = Role.Button, onClick = onClick).testTag(testTag).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.AccentText) }
}

/** Large page title with an optional one-line subtitle, matching 统计 and 我的. */
@Composable
private fun PageTitle(title: String, subtitle: String?, onPickMuscle: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), fontSize = 29.sp, lineHeight = 35.sp,
                fontWeight = FontWeight.ExtraBold, color = ReadyGymColors.Text)
            SmallTextAction("按肌肉选动作", onPickMuscle, "plan-pick-muscle")
        }
        if (subtitle != null) Text(subtitle, fontSize = 14.sp, lineHeight = 21.sp, color = ReadyGymColors.MutedText)
    }
}

@Composable
internal fun DayCard(index: Int, day: PlanDay, library: ExerciseLibrary, expanded: Boolean, isNext: Boolean = false) {
    var open by rememberSaveable(day.title, index) { mutableStateOf(expanded) }
    Panel(Modifier.clickable { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("第 ${index + 1} 天", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (isNext) ReadyGymColors.AccentText else ReadyGymColors.MutedText)
            Spacer(Modifier.width(GymSpacing.Sm))
            Text(day.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (open) "收起" else "${day.items.size} 个动作", fontSize = 12.sp, color = ReadyGymColors.MutedText)
        }
        if (day.focus.isNotBlank()) Text(day.focus, fontSize = 13.sp, color = ReadyGymColors.MutedText)
        if (open) day.items.forEach { PlanItemRow(it, library) }
    }
}

@Composable
private fun PlanItemRow(item: PlanItem, library: ExerciseLibrary) {
    val ex = library[item.exerciseId]
    // The image is decoded by Coil on a background thread when it first composes; `animate=false`
    // keeps a 3–8 item list from decoding eight animated GIFs at once.
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        ExerciseImage(ex, animate = false, modifier = Modifier.size(52.dp))
        Spacer(Modifier.width(GymSpacing.Md))
        Column(Modifier.weight(1f)) {
            Text(ex?.name ?: item.exerciseId, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull(item.target, item.loadText, "休息 ${item.restSeconds}s").joinToString(" · "),
                fontSize = 13.sp, color = ReadyGymColors.MutedText)
            item.note?.let { Text(it, fontSize = 12.sp, color = ReadyGymColors.AccentText) }
        }
    }
}
