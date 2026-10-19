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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.ai.PlanProposal
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.WorkoutPlan

/**
 * The 训练 tab: profile → AI proposal → confirmed plan → today's workout.
 * Each state has exactly one primary action, so the user always knows the next step.
 */
@Composable
fun TrainHome(vm: GymViewModel, onCreateProfile: () -> Unit, onOpenCoach: () -> Unit, onOpenWorkout: () -> Unit) {
    val profile by vm.profile.collectAsState()
    val plan by vm.activePlan.collectAsState()
    val proposal = vm.proposal

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(GymSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
    ) {
        when {
            profile is Loaded.Loading || plan is Loaded.Loading ->
                Box(Modifier.fillMaxWidth().padding(top = 120.dp), Alignment.Center) { CircularProgressIndicator() }
            profile.valueOrNull() == null -> NoProfile(onCreateProfile, onOpenCoach)
            proposal != null -> ProposalView(vm, proposal, replacing = plan.valueOrNull())
            plan.valueOrNull() == null -> NoPlan(vm)
            else -> PlanView(vm, requireNotNull(plan.valueOrNull()), onOpenWorkout)
        }
    }
}

@Composable
private fun NoProfile(onCreateProfile: () -> Unit, onOpenCoach: () -> Unit) {
    ScreenTitle("训练", "先让教练了解你，再生成专属计划。")
    Panel(container = ReadyGymColors.AccentWash) {
        Text("还没有你的档案", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("年龄、身高体重、目标、器械和每周时间，决定了计划的强度和动作。", color = ReadyGymColors.MutedText, lineHeight = 20.sp)
        PrimaryButton("填写档案（1 分钟）", onCreateProfile, Modifier.testTag("train-create-profile"))
        SecondaryButton("和 AI 教练聊着建档", onOpenCoach)
    }
}

@Composable
private fun NoPlan(vm: GymViewModel) {
    ScreenTitle("训练", "档案已就绪。")
    Panel {
        Text("生成你的训练计划", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("AI 教练会根据档案从 ${vm.library.exercises.size} 个带演示的动作里挑选，安排每天的组数、次数和重量。生成后先给你看，确认了才会保存。",
            color = ReadyGymColors.MutedText, lineHeight = 20.sp)
        vm.planError?.let { Notice(it) }
        PrimaryButton(if (vm.generating) "教练正在排计划…" else "生成计划", vm::generatePlan,
            Modifier.testTag("train-generate"), busy = vm.generating)
        if (vm.generating) Text("通常需要 20–60 秒。", fontSize = 12.sp, color = ReadyGymColors.MutedText)
    }
}

@Composable
private fun ProposalView(vm: GymViewModel, proposal: PlanProposal, replacing: WorkoutPlan?) {
    ScreenTitle(if (replacing == null) "计划草稿" else "新计划草稿", proposal.plan.summary.ifBlank { null })
    if (proposal.message.isNotBlank()) {
        Panel(container = ReadyGymColors.AccentWash) {
            Text("教练说", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.AccentText)
            Text(proposal.message, lineHeight = 22.sp)
        }
    }
    proposal.plan.days.forEachIndexed { i, day -> DayCard(i, day, vm.library, expanded = true) }
    if (replacing != null) Text("确认后替换当前计划（v${replacing.version}），旧版本仍保留在记录里。",
        fontSize = 12.sp, color = ReadyGymColors.MutedText)
    PrimaryButton("采用这个计划", vm::confirmProposal, Modifier.testTag("proposal-confirm"))
    SecondaryButton("不满意，重新生成", { vm.discardProposal(); vm.generatePlan() }, enabled = !vm.generating)
    TextButton(onClick = vm::discardProposal, modifier = Modifier.fillMaxWidth()) { Text("放弃", color = ReadyGymColors.MutedText) }
}

@Composable
private fun PlanView(vm: GymViewModel, plan: WorkoutPlan, onOpenWorkout: () -> Unit) {
    val open by vm.openSession.collectAsState()
    var confirmRegenerate by rememberSaveable { mutableStateOf(false) }
    val next = vm.nextDay.coerceIn(plan.days.indices)
    val today = plan.days[next]

    ScreenTitle("训练", "计划 v${plan.version} · 每周 ${plan.daysPerWeek} 练 · ${plan.summary}")

    val inProgress = open.valueOrNull()
    Panel(container = ReadyGymColors.Action) {
        Text(if (inProgress != null) "进行中" else "下一次 · 第 ${next + 1} 天", fontSize = 12.sp,
            fontWeight = FontWeight.Bold, color = ReadyGymColors.Accent)
        Text(inProgress?.dayTitle ?: today.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.OnAction)
        if (inProgress == null) Text(
            listOfNotNull(today.focus.ifBlank { null }, "${today.items.size} 个动作", "${today.items.sumOf { it.sets }} 组").joinToString(" · "),
            color = ReadyGymColors.OnAction.copy(alpha = 0.7f), fontSize = 14.sp)
        else Text("已完成 ${inProgress.completedSets.size}/${inProgress.sets.size} 组",
            color = ReadyGymColors.OnAction.copy(alpha = 0.7f), fontSize = 14.sp)
        Spacer(Modifier.size(GymSpacing.Xs))
        Box(
            Modifier.fillMaxWidth().clip(GymShapes.Md).background(ReadyGymColors.Accent)
                .clickable { if (inProgress == null) vm.startWorkout(next); onOpenWorkout() }
                .padding(vertical = 14.dp).testTag("train-start"),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (inProgress != null) "继续训练" else "开始训练", color = ReadyGymColors.OnAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }

    SectionHeader("全部训练日")
    plan.days.forEachIndexed { i, day -> DayCard(i, day, vm.library, expanded = false, isNext = i == next) }

    vm.planError?.let { Notice(it) }
    TextButton(onClick = { confirmRegenerate = true }, enabled = !vm.generating, modifier = Modifier.fillMaxWidth()) {
        Text(if (vm.generating) "教练正在排新计划…" else "根据档案重新生成计划", color = ReadyGymColors.MutedText)
    }
    if (confirmRegenerate) AlertDialog(
        onDismissRequest = { confirmRegenerate = false },
        title = { Text("重新生成计划？") },
        text = { Text("会根据当前档案生成一份新草稿，你确认后才会替换。练后调整请在训练结束时进行。") },
        confirmButton = { TextButton(onClick = { confirmRegenerate = false; vm.generatePlan() }) { Text("生成") } },
        dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text("取消") } },
    )
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
