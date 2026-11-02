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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.plan.formatKg
import com.readygym.core.profile.weekdayLabel
import com.readygym.core.workout.Badge
import com.readygym.core.workout.BadgeRules
import com.readygym.core.workout.WorkoutSession
import kotlinx.coroutines.launch

/**
 * 我的: who the app thinks you are, and everything computed from that.
 *
 * This replaced the 动作库 slot. The exercise catalogue is something the coach reads to write a
 * plan; the profile is the thing the plan, the stats and the coach's answers are all computed
 * from, so it earns the tab. The order follows what a user comes here to do: check the profile,
 * edit it, then look at what came of it (plan, badges, this week's board).
 */
@Composable
fun MeScreen(
    vm: GymViewModel,
    nickname: String?,
    onEditProfile: () -> Unit,
    onRename: () -> Unit,
    onOpenFeedback: () -> Unit = {},
    onOpenDonate: () -> Unit = {},
    onOpenGroup: () -> Unit = {},
) {
    val profile by vm.profile.collectAsState()
    val plan by vm.activePlan.collectAsState()
    val history by vm.history.collectAsState()
    val p = profile.valueOrNull()
    val workout = plan.valueOrNull()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = GymSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
    ) {
        Spacer(Modifier.height(GymSpacing.Md))
        Text("我的", fontSize = 29.sp, lineHeight = 35.sp, fontWeight = FontWeight.ExtraBold,
            color = ReadyGymColors.Text, modifier = Modifier.semantics { heading() })

        IdentityCard(
            nickname = nickname,
            goalLabel = p?.goal?.label,
            frequency = p?.let { "每周 ${it.daysPerWeek} 天 · 每次 ${it.minutesPerSession} 分钟" },
            sessions = history.count { it.endedAtMillis != null },
            planVersion = workout?.version,
            onRename = onRename,
        )

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("训练档案", Modifier.padding(top = 0.dp).weight(1f))
                if (p != null) SmallAction("编辑", onEditProfile, "me-edit-profile")
            }
            if (p == null) {
                Text("还没有档案。教练需要先了解你的目标、器械和时间。", fontSize = 14.sp, lineHeight = 21.sp,
                    color = ReadyGymColors.MutedText)
                PrimaryButton("填写档案", onEditProfile, Modifier.testTag("me-create-profile"))
            } else {
                FactRow("年龄", "${p.age} 岁")
                FactRow("身高 / 体重", "${formatKg(p.heightCm)} cm · ${formatKg(p.weightKg)} kg")
                FactRow("目标", p.goal.label)
                FactRow("经验", p.experience.label)
                FactRow("地点", p.location.label)
                FactRow("器械", p.equipment.joinToString("、") { it.label })
                FactRow("频率", "每周 ${p.daysPerWeek} 天 · 每次 ${p.minutesPerSession} 分钟")
                if (p.trainingDays.isNotEmpty()) FactRow("训练日", p.trainingDays.sorted().joinToString("、") { weekdayLabel(it) })
                if (p.limitations.isNotBlank()) FactRow("伤病 / 避开", p.limitations)
                Text("改了档案后，可以让教练按新条件重新排计划。", fontSize = 12.sp, lineHeight = 18.sp,
                    color = ReadyGymColors.MutedText)
            }
        }

        ReminderPanel()

        HelpPanel(onOpenFeedback, onOpenDonate, onOpenGroup)

        Panel {
            SectionHeader("账号", Modifier.padding(top = 0.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("昵称", fontSize = 13.sp, color = ReadyGymColors.MutedText, modifier = Modifier.width(72.dp))
                Text(nickname?.ifBlank { null } ?: "未设置", fontSize = 14.sp, modifier = Modifier.weight(1f))
                SmallAction("修改", onRename, "me-rename")
            }
        }

        Text(
            "档案、计划和训练记录保存在这台手机上；完成训练时只把时长、组数等摘要上传，用于排行榜。和教练聊天、生成或调整计划时，内容会经 ReadyGym 服务器发送给 AI。AI 建议不能替代医生或专业教练。",
            fontSize = 12.sp, lineHeight = 18.sp, color = ReadyGymColors.MutedText,
        )
        Spacer(Modifier.height(GymSpacing.Xxl))
    }
}

/**
 * The identity card: who you are at a glance, with the one action that changes the name.
 * The three numbers under it are the ones the rest of the app is keyed on, so they are here
 * rather than repeated on every screen.
 */
@Composable
private fun IdentityCard(
    nickname: String?,
    goalLabel: String?,
    frequency: String?,
    sessions: Int,
    planVersion: Int?,
    onRename: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(GymShapes.Xl).background(ReadyGymColors.Surface).padding(GymSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(ReadyGymColors.AccentWash),
                contentAlignment = Alignment.Center,
            ) { Icon(GymIcons.Person, null, Modifier.size(26.dp), tint = ReadyGymColors.AccentText) }
            Spacer(Modifier.width(GymSpacing.Md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(nickname?.ifBlank { null } ?: "还没起名字", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(goalLabel?.let { "目标：$it" } ?: "还没有训练档案", fontSize = 13.sp,
                    color = if (goalLabel == null) ReadyGymColors.MutedText else ReadyGymColors.AccentText)
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(ReadyGymColors.Sunken)
                    .clickable(role = Role.Button, onClick = onRename).testTag("me-rename-icon")
                    .semantics { contentDescription = "修改昵称" },
                contentAlignment = Alignment.Center,
            ) { Icon(GymIcons.Pencil, null, Modifier.size(18.dp), tint = ReadyGymColors.Text) }
        }
        Row(Modifier.fillMaxWidth()) {
            IdentityStat("完成训练", "$sessions", Modifier.weight(1f))
            IdentityStat("频率", frequency?.substringAfter("每周 ")?.substringBefore(" 天")?.let { "$it 天/周" } ?: "—", Modifier.weight(1f))
            IdentityStat("当前计划", planVersion?.let { "v$it" } ?: "—", Modifier.weight(1f))
        }
    }
}

@Composable
private fun IdentityStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.Text, maxLines = 1)
        Text(label, fontSize = 12.sp, color = ReadyGymColors.MutedText, maxLines = 1)
    }
}

/** A text-sized action in a header row, sized to keep a 48dp touch target. */
@Composable
private fun SmallAction(label: String, onClick: () -> Unit, testTag: String) {
    Box(
        Modifier.height(40.dp).clip(GymShapes.Sm).background(ReadyGymColors.AccentWash)
            .clickable(role = Role.Button, onClick = onClick).testTag(testTag)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.AccentText) }
}

/**
 * 检查更新 + 意见反馈: the two operator-facing channels. Update checks go to PGYER (the
 * distribution host, so its version list is always current); the silent daily check just feeds
 * the "有新版本" hint, the explicit tap re-queries and shows the release notes.
 */
@Composable
private fun HelpPanel(onOpenFeedback: () -> Unit, onOpenDonate: () -> Unit, onOpenGroup: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val store = androidx.compose.runtime.remember(context) { PgyerUpdateStore(context) }
    var newer by androidx.compose.runtime.remember(context) { mutableStateOf(store.cachedNewer()) }
    var checking by androidx.compose.runtime.remember { mutableStateOf(false) }
    var message by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }

    fun goCheck() {
        if (checking) return
        checking = true; message = null
        scope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PgyerUpdate.check() }
            val today = java.time.LocalDate.now().toString()
            store.record(result, today)
            newer = store.cachedNewer()
            message = when (result) {
                is PgyerUpdate.CheckResult.UpToDate -> "已是最新版本 v${BuildConfig.VERSION_NAME}"
                is PgyerUpdate.CheckResult.Failed -> result.why
                is PgyerUpdate.CheckResult.Newer -> null // the newer card below says it all
            }
            checking = false
        }
    }

    Panel {
        SectionHeader("帮助与更新", Modifier.padding(top = 0.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("检查更新", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("当前版本 v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = ReadyGymColors.MutedText)
            }
            if (newer != null) Text("有新版本", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = ReadyGymColors.AccentText, modifier = Modifier.padding(end = GymSpacing.Sm))
            InlineAction(if (checking) "检查中…" else "检查", { goCheck() }, Modifier.testTag("update-check"))
        }
        message?.let { Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = ReadyGymColors.MutedText) }
        newer?.let { info ->
            Panel(container = ReadyGymColors.AccentWash) {
                Text("新版本 ${info.versionName}", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = ReadyGymColors.AccentText)
                if (info.notes.isNotBlank()) Text(info.notes, fontSize = 13.sp, lineHeight = 19.sp)
                PrimaryButton("去下载", {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(info.downloadUrl)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }, Modifier.testTag("update-download"))
            }
        }
        Hairline()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("意见反馈", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("不好用的地方、想要的功能，告诉我", fontSize = 12.sp, color = ReadyGymColors.MutedText)
            }
            InlineAction("去反馈", onOpenFeedback, Modifier.testTag("open-feedback"))
        }
        Hairline()
        Row(Modifier.fillMaxWidth().clip(GymShapes.Sm)
            .clickable(role = Role.Button, onClick = onOpenGroup).testTag("open-group")
            .padding(vertical = GymSpacing.Sm), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("软件交流群", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("扫码加入 QQ 群，一起交流训练和使用心得", fontSize = 12.sp,
                    color = ReadyGymColors.MutedText)
            }
            Text("查看", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = ReadyGymColors.AccentText, modifier = Modifier.padding(horizontal = 12.dp))
        }
        Hairline()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("打赏作者", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("App 免费开放，请作者喝杯饮料", fontSize = 12.sp, color = ReadyGymColors.MutedText)
            }
            InlineAction("去打赏", onOpenDonate, Modifier.testTag("open-donate"))
        }
    }
}
