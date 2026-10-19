package com.readygym.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.plan.ChangeKind
import com.readygym.core.plan.PlanChange
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.formatKg
import com.readygym.core.workout.BadgeRules
import com.readygym.core.workout.BodyArea
import com.readygym.core.workout.Difficulty
import com.readygym.core.workout.Effort
import com.readygym.core.workout.PostWorkoutFeedback
import com.readygym.core.workout.WorkoutSession

/**
 * After a workout: how did it feel → the coach's adjusted plan → the user accepts or keeps the old one.
 * Nothing changes in the plan until the user taps accept on the diff.
 */
@Composable
fun ReviewScreen(vm: GymViewModel, onDone: () -> Unit) {
    val session = vm.reviewing
    val adjustment = vm.adjustment
    // Leaving mid-review keeps the session in the history; only the plan change is dropped.
    BackHandler { vm.closeReview(); onDone() }
    Column(Modifier.fillMaxSize().background(ReadyGymColors.Background).safeDrawingPadding().imePadding()) {
        when {
            session == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            adjustment != null -> AdjustmentView(vm, adjustment, onDone)
            vm.adjusting -> Adjusting()
            else -> FeedbackForm(vm, session, onDone)
        }
    }
}

@Composable
private fun ColumnScope.FeedbackForm(vm: GymViewModel, session: WorkoutSession, onDone: () -> Unit) {
    var effort by rememberSaveable(session.id) { mutableStateOf<Effort?>(null) }
    var discomfort by rememberSaveable(session.id) { mutableStateOf<Set<BodyArea>>(emptySet()) }
    var noDiscomfort by rememberSaveable(session.id) { mutableStateOf(false) }
    var perExercise by rememberSaveable(session.id) { mutableStateOf<Map<String, Difficulty>>(emptyMap()) }
    var note by rememberSaveable(session.id) { mutableStateOf("") }
    val exercises = session.sets.map { it.exerciseId }.distinct()
    val ready = effort != null && (noDiscomfort || discomfort.isNotEmpty())
    fun feedback() = PostWorkoutFeedback(session.id, requireNotNull(effort), discomfort, perExercise, note.trim())

    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(GymSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
    ) {
        ScreenTitle("训练完成", listOfNotNull(session.dayTitle, session.durationMinutes?.let { "${it.coerceAtLeast(1)} 分钟" },
            "${session.completedSets.size}/${session.sets.size} 组", session.volumeKg.takeIf { it > 0 }?.let { "${formatKg(it)} kg" })
            .joinToString(" · "))
        val history by vm.history.collectAsState()
        val days = vm.profile.collectAsState().value.valueOrNull()?.daysPerWeek ?: 3
        BadgeRules.awards(history, days).firstOrNull { it.sessionId == session.id }?.let { award ->
            Panel(container = ReadyGymColors.AccentWash, modifier = Modifier.testTag("review-award")) {
                Text(if (award.unlocked.isEmpty()) "打卡成功！" else "获得新勋章！", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                AwardRow(award)
            }
        }
        Panel {
            SectionHeader("整体感觉怎么样？", Modifier.padding(top = 0.dp))
            ChoiceRow(Effort.entries, { it.label }, { it == effort }, { effort = it })
        }
        Panel {
            SectionHeader("有没有哪里不舒服？", Modifier.padding(top = 0.dp))
            Chip("没有", noDiscomfort, { noDiscomfort = true; discomfort = emptySet() }, modifier = Modifier.testTag("feedback-no-discomfort"))
            ChoiceRow(BodyArea.entries, { it.label }, { it in discomfort }, multi = true, onPick = {
                discomfort = if (it in discomfort) discomfort - it else discomfort + it
                noDiscomfort = false
            })
            if (discomfort.isNotEmpty()) Notice("如果是关节疼痛、刺痛或麻木，而不是肌肉酸胀，请先停止相关训练，必要时就医。教练会避开给这些部位加压的动作。", warning = true)
        }
        Panel {
            SectionHeader("每个动作的难度（选填）", Modifier.padding(top = 0.dp))
            exercises.forEach { id ->
                Text(vm.library[id]?.name ?: id, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                ChoiceRow(Difficulty.entries, { it.label }, { perExercise[id] == it }, {
                    perExercise = if (perExercise[id] == it) perExercise - id else perExercise + (id to it)
                })
            }
        }
        Panel {
            SectionHeader("想对教练说的（选填）", Modifier.padding(top = 0.dp))
            OutlinedTextField(note, { note = it.take(300) }, Modifier.fillMaxWidth().testTag("feedback-note"),
                placeholder = { Text("例如：深蹲最后一组膝盖有点晃", color = ReadyGymColors.FaintText) }, minLines = 2, shape = GymShapes.Md)
        }
    }
    Column(Modifier.padding(horizontal = GymSpacing.Xl, vertical = GymSpacing.Md), verticalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
        vm.adjustError?.let { Notice(it) }
        if (!ready) Text("选好整体感觉和是否不适后即可提交。", fontSize = 12.sp, color = ReadyGymColors.MutedText)
        PrimaryButton("让教练调整计划", { vm.requestAdjustment(feedback()) }, Modifier.testTag("feedback-adjust"), enabled = ready)
        SecondaryButton("只保存反馈，不改计划", { vm.saveFeedbackOnly(feedback(), onDone) }, Modifier.testTag("feedback-save"), enabled = ready)
    }
}

@Composable
private fun Adjusting() {
    Column(Modifier.fillMaxSize().padding(GymSpacing.Xxl), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = ReadyGymColors.AccentText)
        Spacer(Modifier.size(GymSpacing.Lg))
        Text("教练正在根据这次训练调整计划…", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("通常需要 20–60 秒", fontSize = 13.sp, color = ReadyGymColors.MutedText)
    }
}

@Composable
private fun ColumnScope.AdjustmentView(vm: GymViewModel, a: PlanAdjustment, onDone: () -> Unit) {
    val changed = a.changed
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(GymSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
    ) {
        ScreenTitle("计划调整", if (changed.isEmpty()) "教练认为当前计划不需要改动" else "${changed.size} 处改动 · 确认后成为 v${a.base.version + 1}")
        if (a.proposal.message.isNotBlank()) Panel(container = ReadyGymColors.AccentWash) {
            Text("教练说", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.AccentText)
            Text(a.proposal.message, lineHeight = 22.sp)
        }
        if (a.discomfort) Notice("你反馈了身体不适。如果疼痛持续或加重，请暂停训练并咨询医生。", warning = true)
        changed.groupBy { it.dayIndex }.forEach { (day, list) ->
            SectionHeader("第 ${day + 1} 天 · ${(a.proposal.plan.days.getOrNull(day) ?: a.base.days[day]).title}")
            list.forEach { ChangeCard(it, vm, a.proposal.reasons[it.exerciseId]) }
        }
        val kept = a.changes.size - changed.size
        if (kept > 0 && changed.isNotEmpty()) Text("其余 $kept 个动作保持不变。", fontSize = 13.sp, color = ReadyGymColors.MutedText)
    }
    Column(Modifier.padding(horizontal = GymSpacing.Xl, vertical = GymSpacing.Md), verticalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
        if (changed.isEmpty()) PrimaryButton("好的", { vm.closeReview(); onDone() }, Modifier.testTag("adjust-ok"))
        else {
            PrimaryButton("采用调整", { vm.acceptAdjustment(onDone) }, Modifier.testTag("adjust-accept"))
            SecondaryButton("保持原计划", { vm.closeReview(); onDone() }, Modifier.testTag("adjust-reject"))
        }
    }
}

@Composable
private fun ChangeCard(c: PlanChange, vm: GymViewModel, reason: String?) {
    val name = { item: PlanItem? -> item?.let { vm.library[it.exerciseId]?.name ?: it.exerciseId }.orEmpty() }
    Panel(Modifier.testTag("change-${c.dayIndex}-${c.exerciseId}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindBadge(c.kind)
            Spacer(Modifier.width(GymSpacing.Sm))
            Text(if (c.kind == ChangeKind.REPLACED) "${name(c.before)} → ${name(c.after)}" else name(c.after ?: c.before),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        c.before?.takeIf { c.kind != ChangeKind.ADDED }?.let {
            Text(describe(it), fontSize = 13.sp, color = ReadyGymColors.FaintText,
                textDecoration = if (c.kind == ChangeKind.REMOVED || c.kind == ChangeKind.REPLACED) TextDecoration.LineThrough else null)
        }
        c.after?.let { Text(describe(it), fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        reason?.let { Text(it, fontSize = 13.sp, color = ReadyGymColors.AccentText, lineHeight = 19.sp) }
    }
}

private fun describe(i: PlanItem) = listOfNotNull(i.target, i.loadText).joinToString(" · ")

@Composable
private fun KindBadge(kind: ChangeKind) {
    val (bg, fg) = when (kind) {
        ChangeKind.LOAD_UP, ChangeKind.VOLUME_UP, ChangeKind.ADDED -> ReadyGymColors.AccentWash to ReadyGymColors.AccentText
        ChangeKind.LOAD_DOWN, ChangeKind.VOLUME_DOWN, ChangeKind.REMOVED -> ReadyGymColors.WarningSurface to ReadyGymColors.Warning
        else -> ReadyGymColors.Sunken to ReadyGymColors.Text
    }
    Text(kind.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg,
        modifier = Modifier.clip(GymShapes.Xs).background(bg).padding(horizontal = 8.dp, vertical = 3.dp))
}
