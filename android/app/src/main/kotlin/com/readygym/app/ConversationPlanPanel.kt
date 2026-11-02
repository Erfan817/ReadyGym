package com.readygym.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.ai.ConversationPlanDraft
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.exercise.label
import com.readygym.core.plan.ChangeKind
import com.readygym.core.plan.PlanDiff
import com.readygym.core.plan.PlanItem

/** A choice is only a preview. Persist only after a separate, explicit confirmation. */
@Composable
fun ConversationPlanPanel(coach: CoachConversation, gym: GymViewModel) {
    val draft = coach.reply?.conversationPlan ?: return
    if (coach.localSafetyBlocked || coach.reply?.status == "blocked") return
    val active by gym.activePlan.collectAsState()
    val profile by gym.profile.collectAsState()
    val stale = active.valueOrNull() != draft.base || profile.valueOrNull() != draft.profile
    ConversationPlanChoices(
        draft = draft,
        library = gym.library,
        enabled = !coach.busy && coach.input.isBlank() && !stale,
        busy = coach.busy,
        stale = stale,
        onConfirm = { index ->
            coach.confirmConversationPlan(index) { reviewed, selected ->
                gym.confirmChatPlan(reviewed.base, reviewed.profile, selected.plan)
            }
        },
        onDismiss = coach::dismissConversationPlan,
    )
}

@Composable
internal fun ConversationPlanChoices(
    draft: ConversationPlanDraft,
    library: ExerciseLibrary,
    enabled: Boolean,
    busy: Boolean = false,
    stale: Boolean = false,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(draft) { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth().testTag("coach-plan-options"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("选一套适合你的调整", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("确认后立即更新计划和当前训练中待做的动作，已完成的记录保留。", color = ReadyGymColors.MutedText, fontSize = 14.sp)
        if (stale) Notice("档案或计划刚刚更新了。请告诉教练重新给一组选项，再来确认。")
        draft.options.forEachIndexed { index, option ->
            val chosen = selected == index
            Panel(
                container = if (chosen) ReadyGymColors.AccentWash else ReadyGymColors.Surface,
                modifier = Modifier.testTag("coach-plan-option-$index"),
            ) {
                Text("${index + 1}. ${option.title}", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (option.detail.isNotBlank()) Text(option.detail, fontSize = 14.sp, color = ReadyGymColors.MutedText)
                val changes = PlanDiff.between(draft.base, option.plan).filter { it.kind != ChangeKind.KEPT }
                changes.forEach { change ->
                    fun describe(item: PlanItem?): String = item?.let {
                        "${library[it.exerciseId]?.name ?: it.exerciseId}（${it.target}${it.loadText?.let { load -> " · $load" }.orEmpty()}）"
                    } ?: "无"
                    Text("第 ${change.dayIndex + 1} 天 · ${change.kind.label}：${describe(change.before)} → ${describe(change.after)}",
                        fontSize = 14.sp, lineHeight = 21.sp)
                }
                val newActions = changes.filter { it.kind == ChangeKind.REPLACED || it.kind == ChangeKind.ADDED }
                    .groupBy { it.exerciseId }
                if (newActions.isNotEmpty()) {
                    Text("新动作演示" + if (newActions.size > 1) " · 左右滑动查看" else "",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    @Composable fun demo(id: String) {
                        val exercise = library[id] ?: return
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.testTag("coach-plan-demo-$index-$id")) {
                            Text(exercise.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            ExerciseGifPreview(exercise, Modifier.fillMaxWidth().height(180.dp))
                            val days = newActions.getValue(id).map { it.dayIndex + 1 }.distinct().joinToString("、")
                            Text("第 $days 天 · ${exercise.equipment.label}", fontSize = 12.sp,
                                color = ReadyGymColors.MutedText)
                        }
                    }
                    if (newActions.size == 1) demo(newActions.keys.first())
                    else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("coach-plan-demos-$index")) {
                        items(newActions.keys.toList(), key = { it }) { id ->
                            Column(Modifier.width(240.dp)) { demo(id) }
                        }
                    }
                }
                // Order/title changes may leave all matched items unchanged; the complete preview
                // below still makes that difference reviewable.
                if (!chosen) SecondaryButton("查看这套调整", { selected = index },
                    Modifier.testTag("coach-plan-select-$index"), enabled = enabled)
                else {
                    Text("采用后的整周安排", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    option.plan.days.forEachIndexed { dayIndex, day ->
                        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("第 ${dayIndex + 1} 天 · ${day.title}", fontWeight = FontWeight.Bold)
                            day.items.forEach { item ->
                                Text("${library[item.exerciseId]?.name ?: item.exerciseId} · ${item.target}" +
                                    (item.loadText?.let { " · $it" } ?: ""), fontSize = 14.sp)
                            }
                        }
                    }
                    Text("新动作先选适合自己的轻重量；训练时可以再记录。", fontSize = 13.sp, color = ReadyGymColors.MutedText)
                    PrimaryButton("确认采用这套调整", { onConfirm(index) },
                        Modifier.testTag("coach-plan-confirm"), enabled = enabled, busy = busy)
                }
            }
        }
        TextButton(onClick = onDismiss, enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("coach-plan-dismiss")) {
            Text("先不改，继续和教练聊")
        }
    }
}
