package com.readygym.app

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Read-only tool results. Only an explicit button can call the write endpoint. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun FoundationCards(coach: CoachConversation, state: CoachUiState = CoachUiState.IDLE,
                             sharedScope: SharedTransitionScope? = null) {
    val reply = coach.reply ?: return
    var detail by remember { mutableStateOf<FoundationExercise?>(null) }
    reply.agentNotice?.let { Text(it, color = ReadyGymColors.MutedText, fontSize = 13.sp) }
    if (reply.toolTrace.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = ReadyGymColors.Surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("工具执行记录 · 服务端结果", fontWeight = FontWeight.Bold)
                reply.toolTrace.forEach { Text("${it.name} · ${if (it.status == "ok") "完成" else "拒绝"}", fontSize = 13.sp) }
            }
        }
    }
    // One element, two states: the plan card keeps its identity whether it is a draft or the
    // active version. `sharedBounds` (a Compose 1.7 *library* API, so not gated on the device's
    // Android version) needs an AnimatedVisibilityScope — that is the API's contract for "a node
    // that appears inside a visible container" — so each card is hosted in a permanently-visible
    // AnimatedVisibility with the same shared key. When the draft becomes v1, bounds and content
    // animate together instead of one card swapping for a look-alike.
    val active = reply.activePlan
    val draft = reply.planProposal
    // The celebration is keyed to the conversation's confirmed milestone, so it plays once when a
    // version is adopted — not again on every later recomposition of the same card.
    val milestone = coach.confirmedMilestone
    val planForShared = active ?: draft
    val sharedState = if (sharedScope != null && planForShared != null) {
        sharedScope.rememberSharedContentState("plan-${planForShared.id}")
    } else null
    if (sharedScope != null && sharedState != null) {
        if (active != null) {
            SharedPlanCard(sharedScope, sharedState) {
                FoundationPlanCard(active, "当前生效 v${active.revision}", { detail = it }, milestone)
            }
        }
        if (draft != null) {
            ProposingEnter(target = "${draft.id}-${draft.revision}") {
                SharedPlanCard(sharedScope, sharedState) {
                    FoundationPlanCard(draft, "待确认 v${draft.revision}", { detail = it }, milestone)
                }
            }
        }
    } else {
        if (active != null) FoundationPlanCard(active, "当前生效 v${active.revision}", { detail = it }, milestone)
        if (draft != null) ProposingEnter(target = "${draft.id}-${draft.revision}") {
            FoundationPlanCard(draft, "待确认 v${draft.revision}", { detail = it }, milestone)
        }
    }
    // The draft's own confirmation affordances sit below the shared card: the diff, the caveat,
    // and the single confirm action. They are not shared, because they stop existing on confirmation.
    if (draft != null) {
        if (draft.changes.isNotEmpty()) Card(colors = CardDefaults.cardColors(containerColor = ReadyGymColors.AccentSurface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("修订差异 · 原生效版未改变", fontWeight = FontWeight.Bold)
                draft.changes.forEach { Text("${fieldLabel(it.field)}：${it.before} → ${it.after}") }
            }
        }
        Text("草稿不会自动落库。明确确认后读回同一会话、资料与计划版本，并核对客户端支持字段；确认不是安全认证。", fontSize = 13.sp, color = ReadyGymColors.MutedText)
        ConfirmAttention(enabled = state == CoachUiState.AWAITING_CONFIRM) { modifier ->
            Button(onClick = { coach.confirmPlan() }, enabled = !coach.busy && !coach.localSafetyBlocked && coach.input.isBlank() && !coach.planConfirmationUnknown,
                modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("foundation-confirm-plan")) {
                Text(if (state == CoachUiState.RULE_FALLBACK) "确认采用规则草稿 v${draft.revision}" else "确认采用 v${draft.revision}")
            }
        }
    }
    if (coach.planConfirmationUnknown) Text("上次确认结果未知：请先读回状态，不能重复写入或提出新修改。", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
    if (reply.foundationEnabled && reply.confirmed) TextButton(onClick = { coach.readFoundation() },
        enabled = !coach.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("读回确认状态（不重复写入）") }
    detail?.let { item -> Dialog(onDismissRequest = { detail = null }) {
        Surface(shape = RoundedCornerShape(22.dp), color = ReadyGymColors.Surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("动作详情：${FoundationContract.exerciseNames[item.exerciseId]}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${item.sets}组 × ${exerciseDose(item)} · 组间休息${item.restSeconds}秒")
                    Text("未经专业审核；不推算负重。当前身体不适时停止训练。", fontSize = 13.sp)
                    ExerciseDemo(stableId = item.exerciseId)
                }
                Button(onClick = { detail = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("关闭详情") }
            }
        }
    } }
}

/**
 * Hosts one side of the shared plan card.
 *
 * `AnimatedVisibility` is always visible here: it exists only to supply the `AnimatedVisibilityScope`
 * that `sharedBounds` requires, and to keep the two hosts on one shared key so Compose can move the
 * node between them when the draft becomes the active plan.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedPlanCard(
    scope: SharedTransitionScope,
    state: SharedTransitionScope.SharedContentState,
    content: @Composable () -> Unit,
) {
    androidx.compose.animation.AnimatedVisibility(visible = true) {
        with(scope) {
            androidx.compose.foundation.layout.Box(Modifier.sharedBounds(state, animatedVisibilityScope = this@AnimatedVisibility)) {
                content()
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FoundationPlanCard(plan: FoundationPlan, label: String, onExercise: (FoundationExercise) -> Unit,
                               milestone: Any? = null, modifier: Modifier = Modifier) {
    var day by remember(plan.id) { mutableIntStateOf(plan.trainingDays.first()) }
    // SUCCESS: exactly one scale pulse when this revision became the active one. The trigger is the
    // conversation's confirmed milestone (the plan object read back from the server), so a later
    // recomposition of the same card does not replay it.
    CelebrationScale(trigger = if (plan.status == "confirmed") milestone ?: plan.revision else null) { celebration ->
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = if (plan.status == "draft") ReadyGymColors.AccentSurface else ReadyGymColors.Surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp).testTag("foundation-${plan.status}-v${plan.revision}").then(celebration),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(plan.title, fontWeight = FontWeight.SemiBold)
            Text("每周${plan.daysPerWeek}天 · 每次${plan.minutesPerSession}分钟\n训练日：${plan.trainingDays.joinToString("、") { weekdays[it - 1] }}")
            Text(if (plan.source == "rule_fallback") "来源：规则回退 rule_fallback · 未经专业审核" else "来源：Agent调用规则工具 agent_rule_tool · 未经专业审核", fontSize = 13.sp, color = ReadyGymColors.Accent)
            Text("不是医疗处方或安全认证；不推算负重。", fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                plan.week.forEach { d -> TextButton(onClick = { day = d.dayOfWeek }, contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("${d.dayOfWeek}${if (d.training) "练" else "休"}", fontWeight = if (day == d.dayOfWeek) FontWeight.Bold else FontWeight.Normal)
                } }
            }
            Text("${weekdays[day - 1]} / ${if (plan.week[day - 1].training) "训练" else "休息与恢复"}", fontWeight = FontWeight.Bold)
            plan.week[day - 1].exercises.forEach { x ->
                // Press feedback is a graphicsLayer scale, so tapping in a six-item GIF list never
                // triggers a relayout of the row it sits in.
                PressScale(Modifier.fillMaxWidth()) {
                    OutlinedCard(Modifier.fillMaxWidth().testTag("foundation-exercise-${x.slotId}").clickable(onClickLabel = "查看准确动作详情") { onExercise(x) }) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(FoundationContract.exerciseNames.getValue(x.exerciseId), fontWeight = FontWeight.Bold)
                            Text("${x.sets}组 × ${exerciseDose(x)} · 休息${x.restSeconds}秒", fontSize = 13.sp)
                            Text("查看动作详情与准确GIF →", color = ReadyGymColors.Action, fontSize = 13.sp)
                        }
                    }
                }
            }
            plan.notices.forEach { Text(it, fontSize = 12.sp, color = ReadyGymColors.MutedText) }
        }
    }
    }
}
private fun exerciseDose(x: FoundationExercise) = x.seconds?.let { "${it}秒" } ?: "${x.repsMin}–${x.repsMax}次"
private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private fun fieldLabel(field: String) = when (field) {
    "days_per_week" -> "每周天数"; "minutes_per_session" -> "每次分钟"; "equipment" -> "可用器械"
    "unavailable_days" -> "没空的日子"; "training_days" -> "训练日"; else -> field
}
