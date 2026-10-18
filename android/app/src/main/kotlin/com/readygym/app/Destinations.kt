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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.*

/**
 * The middle destination: what today is, and the one action that matters.
 *
 * Two states only — no plan yet, or a confirmed plan with today's session. Anything that is not
 * yet true is stated as not yet true rather than shown as an empty card, so the screen never
 * implies training data that does not exist.
 */
@Composable
fun TrainScreen(
    plan: PlanDraft?,
    profile: TrainingProfile?,
    onOpenChat: () -> Unit,
    onOpenDay: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Text("开始训练", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (plan == null) "还没有已确认的训练计划。先和教练聊一聊，或直接用离线规则排一个。"
                else "以下是已确认的计划。点开某一天查看动作与组次。",
                color = ReadyGymColors.MutedText, fontSize = 14.sp, lineHeight = 22.sp,
            )

            if (plan == null) {
                CardPanel {
                    Text("从哪里开始", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(2.dp))
                    Text("对话会先了解你的基本情况，再问到目标、训练条件和身体状态，最后请你确认资料。",
                        fontSize = 14.sp, lineHeight = 22.sp, color = ReadyGymColors.MutedText)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenChat, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ReadyGymColors.Accent, contentColor = ReadyGymColors.OnAccent)) {
                        Text("去和教练聊聊", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                }
                CardPanel {
                    Text("接下来会发生什么", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(6.dp))
                    NumberedLine("1", "简单自我介绍", "年龄、身高体重、性别")
                    NumberedLine("2", "说明具体情况", "目标、练几次、有什么器械、身体有没有不适")
                    NumberedLine("3", "确认后出计划", "资料与计划分别确认，不自动采用")
                }
            } else {
                val (dayLabelText, session) = todayOf(plan)
                CardPanel(container = ReadyGymColors.AccentWash) {
                    Text("今天", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.AccentText)
                    Spacer(Modifier.height(4.dp))
                    Text(dayLabelText, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    if (session == null) {
                        Text("今天是休息日。不自动加练。", fontSize = 14.sp, color = ReadyGymColors.MutedText)
                    } else {
                        Text("${session.exercises.size} 个动作 · 估算 ${session.estimatedSeconds / 60} 分钟",
                            fontSize = 14.sp, color = ReadyGymColors.MutedText)
                    }
                }
                Text("本周安排", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                plan.week.forEachIndexed { index, day ->
                    DayRow(day) { onOpenDay(index) }
                }
                Spacer(Modifier.height(4.dp))
                Text("计划未经专业审核，不构成医疗建议。", fontSize = 12.sp, color = ReadyGymColors.FaintText)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun todayOf(plan: PlanDraft): Pair<String, TrainingSession?> {
    // java.time keeps this deterministic and testable; dayOfWeek is 1=Monday in the planner.
    val today = java.time.LocalDate.now().dayOfWeek.value
    val day = plan.week.firstOrNull { it.dayOfWeek == today } ?: plan.week.first()
    val names = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    return names[day.dayOfWeek - 1] to day.session
}

@Composable
private fun CardPanel(
    container: androidx.compose.ui.graphics.Color = ReadyGymColors.Surface,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(GymShapes.Xl).background(container).padding(GymSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
private fun NumberedLine(number: String, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(ReadyGymColors.Sunken),
            contentAlignment = Alignment.Center) {
            Text(number, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.MutedText)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(detail, fontSize = 13.sp, lineHeight = 20.sp, color = ReadyGymColors.MutedText)
        }
    }
}

@Composable
private fun DayRow(day: PlannedDay, onClick: () -> Unit) {
    val names = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val isTraining = day.session != null
    Row(
        Modifier.fillMaxWidth().clip(GymShapes.Lg).background(ReadyGymColors.Surface)
            .clickable(onClick = onClick).padding(horizontal = GymSpacing.Lg, vertical = GymSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(names[day.dayOfWeek - 1], fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            modifier = Modifier.width(56.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (isTraining) "${day.session!!.exercises.size} 个动作" else "休息",
                fontSize = 14.sp, color = if (isTraining) ReadyGymColors.Text else ReadyGymColors.MutedText,
            )
            if (isTraining) Text("估算 ${day.session!!.estimatedSeconds / 60} 分钟 · ${day.explanation.text}",
                fontSize = 12.sp, color = ReadyGymColors.FaintText, lineHeight = 17.sp)
        }
        if (isTraining) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(ReadyGymColors.Accent),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = ReadyGymColors.Surface)
            }
        }
    }
}

/**
 * The account destination. Shows only what the app actually knows: everything here is session
 * memory, and the screen says so rather than implying an account that can be signed back into.
 */
@Composable
fun ProfileScreen(
    profile: TrainingProfile?,
    plan: PlanDraft?,
    serviceConfigured: Boolean,
    onClear: () -> Unit,
    onChangeKey: () -> Unit = {},
    keyHint: String? = null,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("账户", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text("本次会话内有效，关闭 App 后不保留。", color = ReadyGymColors.MutedText, fontSize = 14.sp)

            CardPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(CircleShape).background(ReadyGymColors.AccentWash),
                        contentAlignment = Alignment.Center) {
                        Text("R", fontWeight = FontWeight.ExtraBold, color = ReadyGymColors.AccentText, fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(if (profile == null) "尚未建档" else "已建档", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(if (serviceConfigured) "AI 服务已配置" else "AI 服务未配置",
                            fontSize = 13.sp, color = ReadyGymColors.MutedText)
                    }
                }
            }

            // The one place a stored key can be replaced. Without this a user with a wrong or
            // expired key is stuck: the key screen is hidden once a key exists.
            if (serviceConfigured) {
                CardPanel {
                    ProfileRow("API Key", keyHint ?: "已保存")
                    TextButton(onClick = onChangeKey, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("更换 API Key")
                    }
                }
            }

            if (profile != null) {
                SectionTitle("训练资料")
                CardPanel {
                    ProfileRow("年龄", "${profile.age} 岁")
                    ProfileRow("身高体重", "${profile.heightCm} cm · ${profile.weightKg} kg")
                    ProfileRow("目标", profile.goal.label)
                    ProfileRow("地点", profile.location.label)
                    ProfileRow("器械", profile.equipment.joinToString("、") { it.label })
                }
            }

            SectionTitle("当前计划")
            CardPanel {
                if (plan == null) {
                    Text("没有已确认的计划。", fontSize = 14.sp, color = ReadyGymColors.MutedText)
                } else {
                    ProfileRow("训练天数", "每周 ${plan.preferences.daysPerWeek} 天")
                    ProfileRow("单次时长", "${plan.preferences.minutesPerSession} 分钟")
                    ProfileRow("版本", "v${plan.revision}")
                }
            }

            SectionTitle("数据与隐私")
            CardPanel {
                Text("对话与训练资料只存在于本次会话内存，旋转保留、进程结束即清空。服务端会话受过期限制，未实现账号、历史同步或云端备份。",
                    fontSize = 13.sp, lineHeight = 21.sp, color = ReadyGymColors.MutedText)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.MutedText,
        modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 14.sp, color = ReadyGymColors.MutedText, modifier = Modifier.width(76.dp))
        Text(value, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}
