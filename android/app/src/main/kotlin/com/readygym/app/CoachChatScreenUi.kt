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
fun CoachChatScreen(coach: CoachConversation, serviceConfigured: Boolean, onConfirmed: (CoachCandidate) -> Unit,
                    onConfigure: (() -> Unit)? = null, savedGender: Gender? = null,
                    consented: Boolean = false, onConsent: () -> Unit = {}) {
    val scroll = rememberScrollState()
    // Consent is asked once per install; after that an empty chat connects on its own. A failed
    // start is not retried here, so the error and its retry button stay in the user's hands.
    LaunchedEffect(consented, coach.session) {
        if (consented && serviceConfigured && coach.session == null && coach.error == null) coach.start()
    }
    // What the user just told the coach wins over the stored profile.
    val coachAvatar = coachAvatarFor(coach.statedGender ?: savedGender)
    LaunchedEffect(coach.messages.size, coach.reply?.confirmedCandidate) { scroll.animateScrollTo(scroll.maxValue) }
    Surface(color = ReadyGymColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            ChatHeader(serviceConfigured, coachAvatar)
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // A restart drops the server session but keeps the typed input on screen; nothing
                // the user typed is re-sent on their behalf.
                if (coach.session == null) {
                    Spacer(Modifier.height(4.dp))
                    Text("你的AI训练教练", fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold)
                    Text("用自己的话聊聊就好。我会了解你的目标、训练条件和当前身体状态，再请你确认资料。",
                        color = ReadyGymColors.MutedText, lineHeight = 24.sp)
                    if (consented) {
                        Text(if (!serviceConfigured) "暂时连不上服务器，不能连接 AI。" else if (coach.busy) "正在连接教练…" else "",
                            color = if (serviceConfigured) ReadyGymColors.MutedText else MaterialTheme.colorScheme.error,
                            fontSize = 13.sp, lineHeight = 20.sp)
                    } else Panel {
                        Text("开始前，请你知情", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("同意后，你的回答会经 ReadyGym 服务器发送给 AI（DeepSeek），用于建档问询。教练只聊健身和健康话题。不要发送姓名、联系方式等无关资料。",
                            fontSize = 14.sp, lineHeight = 22.sp)
                        Hairline()
                        Text(if (serviceConfigured) "点击后才会发出第一条请求。" else "暂时连不上服务器，不能连接 AI。",
                            color = if (serviceConfigured) ReadyGymColors.MutedText else MaterialTheme.colorScheme.error,
                            fontSize = 13.sp, lineHeight = 20.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                coach.messages.forEachIndexed { index, bubble ->
                    val author = if (bubble.user) "你"
                        else "AI 教练"
                    Row(Modifier.fillMaxWidth().testTag("coach-message-$index"), verticalAlignment = Alignment.Top,
                        horizontalArrangement = if (bubble.user) Arrangement.End else Arrangement.Start) {
                        if (!bubble.user) { ChatAvatar(coachAvatar); Spacer(Modifier.width(8.dp)) }
                        MessageBubble(bubble.user, author, bubble.text, Modifier.weight(1f, fill = false))
                        if (bubble.user) { Spacer(Modifier.width(8.dp)); ChatAvatar(R.drawable.user_avatar_default) }
                    }
                }
                if (coach.reply?.status == "blocked") Text(when (coach.reply?.safetyRoute) {
                    CoachSafetyRoute.STOP_AND_SEEK_CARE -> "请停止训练并及时寻求医疗评估。本应用不作诊断。"
                    CoachSafetyRoute.PROFESSIONAL_REVIEW -> "请暂停训练并寻求专业评估。本应用不作诊断。"
                    CoachSafetyRoute.UNSUPPORTED_EXPERIENCE -> "当前训练经验暂不支持；这不代表身体有风险。"
                    null -> "无法确认身体状况，暂不能生成普通计划。"
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
                            FactRow("经验", when (pref.experience) { TrainingExperience.BEGINNER -> "新手"; TrainingExperience.RETURNING -> "恢复训练"; TrainingExperience.ADVANCED -> "有经验" })
                            Hairline()
                            Text(if (coach.localSafetyBlocked) "旧摘要已失效：较新的身体风险优先，不能以旧的无不适自述继续。" else "你明确自述：没有当前不适、没有医学训练限制。不是安全认证。", fontSize = 13.sp)
                            Text("确认后保存为你的档案，然后到「训练」生成计划；计划也要你确认才会采用。", fontSize = 13.sp, color = ReadyGymColors.MutedText)
                            Button(onClick = { coach.confirm(onConfirmed) }, enabled = !coach.busy && !coach.localSafetyBlocked && coach.input.isBlank(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
                                Text("确认并保存档案", fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = { coach.correct() }, enabled = !coach.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("这些信息不对") }
                        }
                    }
                }
                if (coach.supportsFoundation) FoundationCards(coach)
                Spacer(Modifier.height(4.dp))
                if (coach.session == null && !consented) {
                    Button(
                        onClick = { onConsent(); coach.start() },
                        enabled = serviceConfigured && !coach.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) { Text(if (coach.busy) "正在连接…" else "同意并开始", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                }
                if (coach.session != null) TextButton(onClick = { coach.clear() }, enabled = !coach.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("清空对话") }
                                Text("对话只在本次打开期间保留；确认后的档案保存在本机。AI 建议不能替代专业指导。", fontSize = 12.sp, lineHeight = 20.sp, color = ReadyGymColors.MutedText)
                Spacer(Modifier.height(8.dp))
            }
            Composer(coach)
        }
    }
}

/** A female user gets the female coach, a male user the male one; until then the original coach. */
internal fun coachAvatarFor(gender: Gender?): Int = when (gender) {
    Gender.FEMALE -> R.drawable.coach_avatar_female
    Gender.MALE -> R.drawable.coach_avatar_male
    else -> R.drawable.coach_avatar
}

@Composable
private fun ChatAvatar(res: Int, size: androidx.compose.ui.unit.Dp = 36.dp) {
    Image(painterResource(res), contentDescription = null, modifier = Modifier.size(size).clip(CircleShape).testTag("avatar-$res"),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
}

@Composable
private fun ChatHeader(serviceConfigured: Boolean, coachAvatar: Int) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatAvatar(coachAvatar)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("READY GYM", fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, fontSize = 15.sp)
                Text("AI 建档 · 只聊健身与健康", fontSize = 12.sp, color = ReadyGymColors.Accent)
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
            // The server explains its own failures (daily limit, expired login, AI busy) in Chinese.
            val accountExpired = error.code == "unauthorized"
            Text(
                error.detail ?: when {
                    accountExpired -> "登录已失效，请重新打开 App。"
                    else -> coachErrorText(error.code)
                },
                color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            if (error.retryable) TextButton(onClick = { coach.retry() }, enabled = !coach.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
            // Only a session problem offers a restart; a bad key has to be re-entered on the key
            // screen, and offering "重新开始会话" there would just fail again.
            if (!accountExpired && (error.code in setOf("session_expired", "session_not_found", "session_unauthorized", "invalid_session_token", "http_401", "http_404", "http_410"))) {
                TextButton(onClick = { coach.restart() }, enabled = !coach.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新开始会话") }
            }
        }
        if (coach.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = ReadyGymColors.Accent)
                Spacer(Modifier.width(8.dp))
                Text("教练正在回复…", fontSize = 13.sp, color = ReadyGymColors.MutedText, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
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
    "daily_limit" -> "今天的 AI 次数用完了，明天再来吧。"
    "upstream_rate_limited" -> "AI 服务繁忙，稍等一下再发。"
    "ai_not_configured", "upstream_unavailable", "upstream_error", "upstream_unreachable" -> "AI 服务暂时不可用，稍后重试。"
    "provider_invalid_response" -> "模型返回的格式不符合约定，已丢弃；你的输入还在。再发一次试试。"
    "invalid_input" -> "请填写1–1000字，输入未丢失。"
    "invalid_response", "response_too_large", "unexpected_redirect" -> "AI 回复格式不对，已丢弃；输入已保留。"
    "session_expired", "session_not_found", "invalid_session_token", "http_404", "http_410" -> "会话已失效，请重新开始；本次输入仍保留。"
    "network_error", "http_408", "http_504" -> "连不上服务器，输入已保留；可以点重试。"
    else -> "暂时无法取得AI回复，输入已保留；可重试。"
}
