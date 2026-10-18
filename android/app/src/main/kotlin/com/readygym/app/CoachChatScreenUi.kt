package com.readygym.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.*

/**
 * The conversation screen, after consent.
 *
 * Layout changed; wording and test hooks did not. Every string a test asserts on and every
 * testTag is preserved verbatim, so this is a pure presentation change: the bubble chrome, the
 * sticky composer, and the confirm card were restyled, not rewritten.
 */
@Composable
fun CoachChatScreen(coach: CoachConversation, serviceConfigured: Boolean, onConfirmed: (CoachCandidate) -> Unit, onConfigure: (() -> Unit)? = null) {
    val scroll = rememberScrollState()
    LaunchedEffect(coach.messages.size, coach.reply?.confirmedCandidate) { scroll.animateScrollTo(scroll.maxValue) }
    Surface(color = ReadyGymColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            ChatHeader(serviceConfigured)
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // A restart drops the server session but keeps the typed input on screen. The
                // user is shown the same disclosure and must tap again; nothing is re-sent on
                // their behalf.
                if (coach.session == null) {
                    Spacer(Modifier.height(4.dp))
                    Text("你的AI训练教练", fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold)
                    Text("用自己的话聊聊就好。我会了解你的目标、训练条件和当前身体状态，再请你确认资料。",
                        color = ReadyGymColors.MutedText, lineHeight = 24.sp)
                    Panel {
                        Text("开始前，请你知情", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("同意后，你的回答与训练资料会发送至Ready Gym服务端和DeepSeek，用于AI问询。仅限18岁及以上；不要发送姓名、联系方式等无关资料。",
                            fontSize = 14.sp, lineHeight = 22.sp)
                        Hairline()
                        Text(if (serviceConfigured) "服务地址已配置，尚未连接；点击后才请求AI首问。" else "服务未配置，不能连接AI",
                            color = if (serviceConfigured) ReadyGymColors.MutedText else MaterialTheme.colorScheme.error,
                            fontSize = 13.sp, lineHeight = 20.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                coach.messages.forEachIndexed { index, bubble ->
                    val author = if (bubble.user) "你"
                        else if (bubble.source == "server_policy") "教练 · 服务端整理"
                        else "AI教练 · 服务端回复"
                    Box(Modifier.fillMaxWidth().testTag("coach-message-$index"),
                        contentAlignment = if (bubble.user) Alignment.CenterEnd else Alignment.CenterStart) {
                        MessageBubble(bubble.user, author, bubble.text)
                    }
                }
                if (coach.reply?.status == "blocked") Text(when (coach.reply?.safetyRoute) {
                    CoachSafetyRoute.STOP_AND_SEEK_CARE -> "服务端安全路线：停止训练并及时寻求医疗评估，不作诊断。"
                    CoachSafetyRoute.PROFESSIONAL_REVIEW -> "服务端安全路线：暂停普通训练并寻求专业评估，不作诊断。"
                    CoachSafetyRoute.UNSUPPORTED_EXPERIENCE -> "当前训练经验暂不支持规则模板；这不代表急性身体风险。"
                    null -> "服务端安全路线无法核实，不能采用普通规则计划。"
                }, color = MaterialTheme.colorScheme.error)
                coach.localSafetyMessage?.let {
                    Panel(container = ReadyGymColors.ErrorSurface) {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                    }
                }
                if (coach.correction) Text("直接告诉教练哪里不对，例如“我是在家训练”。修改后会重新整理摘要。", color = ReadyGymColors.MutedText)
                coach.reply?.confirmedCandidate?.takeUnless { coach.supportsFoundation && coach.reply?.confirmed == true }?.let { candidate ->
                    Card(colors = CardDefaults.cardColors(containerColor = ReadyGymColors.AccentSurface),
                        shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val p = candidate.profile; val pref = candidate.preferences
                            Text("请确认你的资料", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Hairline()
                            FactRow("年龄", "${p.age} 岁")
                            FactRow("身高体重", "${p.heightCm} cm · ${p.weightKg} kg")
                            FactRow("性别", p.gender.label)
                            FactRow("目标", p.goal.label)
                            FactRow("地点", p.location.label)
                            FactRow("器械", p.equipment.joinToString("、") { it.label })
                            FactRow("频率", "每周${pref.daysPerWeek}天 · 每次${pref.minutesPerSession}分钟")
                            FactRow("经验", if (pref.experience == TrainingExperience.BEGINNER) "初学" else "恢复训练")
                            Hairline()
                            Text(if (coach.localSafetyBlocked) "旧摘要已失效：较新的身体风险优先，不能以旧的无不适自述继续。" else "你明确自述：没有当前不适、没有医学训练限制。不是安全认证。", fontSize = 13.sp)
                            Text(if (coach.supportsFoundation) "确认资料后，Agent调用服务端规则工具提出计划；草稿仍需你单独确认，未经专业审核。" else "下一步是未经专业审核的规则计划底座，不是AI生成计划。", fontSize = 13.sp, color = ReadyGymColors.MutedText)
                            Button(onClick = { coach.confirm(onConfirmed) }, enabled = !coach.busy && !coach.localSafetyBlocked && coach.input.isBlank(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
                                Text(if (coach.supportsFoundation) "确认资料，请教练提出计划" else "确认资料，进入规则计划", fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = { coach.correct() }, enabled = !coach.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("这些信息不对") }
                        }
                    }
                }
                if (coach.supportsFoundation) FoundationCards(coach)
                Spacer(Modifier.height(4.dp))
                if (coach.session == null) {
                    Button(
                        onClick = { coach.start() },
                        enabled = serviceConfigured && !coach.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) { Text(if (coach.busy) "正在连接…" else "同意并开始", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                }
                if (coach.session != null) TextButton(onClick = { coach.clear() }, enabled = !coach.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("清空对话并删除服务端会话") }
                Text(if (coach.supportsFoundation) "Agent只调用有界规则工具提出草稿；资料与计划分别确认，不自动采用。所有模板未经专业审核。" else "不会用本地规则假装模型回答。AI个性化计划尚未接入，确认后可预览规则计划底座。", fontSize = 13.sp, lineHeight = 21.sp, color = ReadyGymColors.MutedText)
                Text("对话与输入旋转后保留，关闭或进程回收会清空；规则计划仅本次页面内存。服务端会话受过期限制，未实现账号或历史同步。", fontSize = 12.sp, lineHeight = 20.sp, color = ReadyGymColors.MutedText)
                Spacer(Modifier.height(8.dp))
            }
            Composer(coach)
        }
    }
}

@Composable
private fun ChatHeader(serviceConfigured: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.coach_avatar),
                contentDescription = null,
                modifier = Modifier.size(36.dp).clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("READY GYM", fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, fontSize = 15.sp)
                Text("AI建档 · 服务端会话", fontSize = 12.sp, color = ReadyGymColors.Accent)
            }
            Column(Modifier.clip(RoundedCornerShape(20.dp))
                .background(if (serviceConfigured) ReadyGymColors.AccentSurface else ReadyGymColors.ErrorSurface)
                .padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text(if (serviceConfigured) "已连接" else "未连接", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = if (serviceConfigured) ReadyGymColors.Accent else MaterialTheme.colorScheme.error)
            }
        }
        Hairline()
    }
}

@Composable
private fun Composer(coach: CoachConversation) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        coach.error?.let { error ->
            // Two different 401s used to share one message. A rejected API key and an expired
            // server session are not the same problem: the first needs a new key, the second
            // needs a new session. Telling a user to "重新开始会话" when their key is wrong sends
            // them round a loop that can never succeed.
            val keyRejected = error.code == "provider_auth_failed"
            Text(
                when {
                    keyRejected -> "API Key 被拒绝。请到 platform.deepseek.com 确认 Key 是否有效，然后重新填一个。"
                    error.httpStatus == 401 || error.code == "session_unauthorized" -> "会话授权已失效，请重新开始并再次明确同意；本次输入仍保留。"
                    else -> coachErrorText(error.code)
                },
                color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            if (error.retryable) TextButton(onClick = { coach.retry() }, enabled = !coach.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
            // Only a session problem offers a restart; a bad key has to be re-entered on the key
            // screen, and offering "重新开始会话" there would just fail again.
            if (!keyRejected && (error.httpStatus == 401 || error.code in setOf("session_expired", "session_not_found", "session_unauthorized", "invalid_session_token", "http_401", "http_404", "http_410"))) {
                TextButton(onClick = { coach.restart() }, enabled = !coach.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新开始会话") }
            }
        }
        if (coach.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = ReadyGymColors.Accent)
                Spacer(Modifier.width(8.dp))
                Text("正在等待服务端…", fontSize = 13.sp, color = ReadyGymColors.MutedText, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        OutlinedTextField(coach.input, { coach.edit(it) }, enabled = coach.session != null && !coach.busy, label = { Text("告诉教练（最多1000字）") },
            minLines = 1, maxLines = 3, isError = coach.input.length > 1000, shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().testTag("coach-input"))
        if (coach.input.length > 1000) Text("请缩短到1000字以内；输入仍保留。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        Button(onClick = { coach.send() }, enabled = coach.session != null && !coach.busy && !coach.planConfirmationUnknown && coach.input.isNotBlank() && coach.input.length <= 1000,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
            Text("发送", fontWeight = FontWeight.Bold)
        }
    }
}

private fun coachErrorText(code: String) = when (code) {
    "api_config_not_set" -> "还没有填 API Key。回到首页填一个就能开始。"
    "provider_not_configured" -> "服务端未配置DeepSeek，尚未连接AI。"
    "provider_auth_failed" -> "API Key 无效或已失效。请到 platform.deepseek.com 确认后重新填一个。"
    "provider_quota_exhausted" -> "这个 Key 的余额不足。充值后重试，或换一个 Key。"
    "provider_rate_limited" -> "请求太频繁，稍等一下再发。"
    "provider_model_not_found" -> "这个 Key 不能访问所请求的模型。"
    "provider_unavailable" -> "DeepSeek 服务暂时不可用，稍后重试。"
    "provider_invalid_response" -> "模型返回的格式不符合约定，已丢弃；你的输入还在。再发一次试试。"
    "insecure_endpoint" -> "服务地址不安全：只允许HTTPS或Debug本机测试。"
    "invalid_input" -> "请填写1–1000字，输入未丢失。"
    "invalid_response", "response_too_large", "unexpected_redirect" -> "服务端响应不符合契约，不能采用资料；输入已保留。"
    "session_expired", "session_not_found", "invalid_session_token", "http_401", "http_404", "http_410" -> "会话已失效，请重新开始；本次输入仍保留。"
    "network_error", "provider_timeout", "http_408", "http_504" -> "未能确认AI请求结果，输入已保留；可能已计费，重试会再次发起请求。仅在你点击重试后发送。"
    else -> "暂时无法取得AI回复，输入已保留；可重试。"
}
