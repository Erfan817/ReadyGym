package com.readygym.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.plan.formatKg

/** 我的: the account nickname and the profile the coach plans from. */
@Composable
fun MeScreen(vm: GymViewModel, nickname: String?, onEditProfile: () -> Unit, onRename: () -> Unit) {
    val profile by vm.profile.collectAsState()
    val plan by vm.activePlan.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(GymSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
    ) {
        ScreenTitle("我的")
        val p = profile.valueOrNull()
        Panel {
            SectionHeader("训练档案", Modifier.padding(top = 0.dp))
            if (p == null) {
                Text("还没有档案。", color = ReadyGymColors.MutedText)
                PrimaryButton("填写档案", onEditProfile, Modifier.testTag("me-create-profile"))
            } else {
                FactRow("年龄", "${p.age} 岁")
                FactRow("身高 / 体重", "${formatKg(p.heightCm)} cm · ${formatKg(p.weightKg)} kg")
                FactRow("目标", p.goal.label)
                FactRow("经验", p.experience.label)
                FactRow("地点", p.location.label)
                FactRow("器械", p.equipment.joinToString("、") { it.label })
                FactRow("频率", "每周 ${p.daysPerWeek} 天 · 每次 ${p.minutesPerSession} 分钟")
                if (p.trainingDays.isNotEmpty()) FactRow("训练日", p.trainingDays.sorted().joinToString("、") { com.readygym.core.profile.weekdayLabel(it) })
                if (p.limitations.isNotBlank()) FactRow("伤病 / 避开", p.limitations)
                SecondaryButton("编辑档案", onEditProfile, Modifier.testTag("me-edit-profile"))
                Text("改了档案后，可以在「训练」里让教练重新生成计划。", fontSize = 12.sp, color = ReadyGymColors.MutedText)
            }
        }
        plan.valueOrNull()?.let { FactPanel("当前计划", "v${it.version} · 每周 ${it.daysPerWeek} 练 · ${it.days.size} 个训练日") }
        Panel {
            SectionHeader("账号", Modifier.padding(top = 0.dp))
            FactRow("昵称", nickname?.ifBlank { null } ?: "未设置")
            SecondaryButton("修改昵称", onRename, Modifier.testTag("me-rename"))
        }
        Text("档案、计划和训练记录保存在这台手机上；完成训练时只把时长、组数等摘要上传，用于排行榜。和教练聊天、生成或调整计划时，内容会经 ReadyGym 服务器发送给 AI。AI 建议不能替代医生或专业教练。",
            fontSize = 12.sp, lineHeight = 18.sp, color = ReadyGymColors.MutedText)
    }
}

@Composable
private fun FactPanel(title: String, value: String) {
    Panel {
        Text(title, fontSize = 13.sp, color = ReadyGymColors.MutedText)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
