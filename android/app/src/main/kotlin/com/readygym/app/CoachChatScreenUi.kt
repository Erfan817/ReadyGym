package com.readygym.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.*
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch

/** Composition-local holder for the shared push-to-talk engine (one recognizer per screen). */
internal val LocalVoiceInput = compositionLocalOf<VoiceInputController> {
    error("VoiceInputController not provided")
}

/**
 * The conversation screen, after consent.
 *
 * Layout changed; wording and test hooks did not. Every string a test asserts on and every
 * testTag is preserved verbatim, so this is a pure presentation change: the bubble chrome, the
 * sticky composer, and the confirm card were restyled, not rewritten.
 *
 * Motion rule for this file: every animation is chosen from [state] (the single
 * `StateFlow<CoachUiState>`); nothing here looks at `busy`/`error`/`reply` to decide *what* to
 * animate. Those values only decide *whether* an action is available, which the reducer is told
 * about.
 *
 * The draft → confirmed plan card is one continuous element: it is wrapped in a
 * `SharedTransitionLayout` here so the proposal card and the active card share bounds instead of
 * one fading out as the other fades in. That is a Compose 1.7 library API (this app is on
 * androidx.compose.animation 1.7.6 through BOM 2024.12.01), so it is not gated on the device's
 * Android version.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CoachChatScreen(coach: CoachConversation, serviceConfigured: Boolean, onConfirmed: (CoachCandidate) -> Unit,
                    onConfigure: (() -> Unit)? = null, savedGender: Gender? = null,
                    consented: Boolean = false, onConsent: () -> Unit = {}, onClose: (() -> Unit)? = null) {
    val scroll = rememberScrollState()
    val state by coach.uiState.collectAsState()
    val haptics = rememberHaptics()
    val motion = LocalMotionSettings.current
    val scope = rememberCoroutineScope()
    val followDistance = with(LocalDensity.current) { 96.dp.roundToPx() }
    var followLatest by remember { mutableStateOf(true) }
    var followingProgrammatically by remember { mutableStateOf(false) }
    var hasUnreadReply by remember { mutableStateOf(false) }
    LaunchedEffect(scroll) {
        snapshotFlow { Triple(scroll.isScrollInProgress, scroll.value, scroll.maxValue) }.collect { (moving, value, maximum) ->
            if (moving && !followingProgrammatically) {
                followLatest = maximum - value <= followDistance
                if (followLatest) hasUnreadReply = false
            }
        }
    }
    // Consent is asked once per install; after that an empty chat connects on its own. A failed
    // start is not retried here, so the error and its retry button stay in the user's hands.
    LaunchedEffect(consented, coach.session) {
        if (consented && serviceConfigured && coach.session == null && coach.error == null) coach.start()
    }
    // What the user just told the coach wins over the stored profile.
    val coachAvatar = coachAvatarFor(coach.statedGender ?: savedGender)
    // The confirm action needs the screen's own callback; `CoachConversation.confirm` takes it, so
    // it is captured once here rather than re-derived per recomposition.
    val onConfirm = rememberUpdatedState(onConfirmed)
    LaunchedEffect(coach.messages.size, coach.reply?.confirmedCandidate) {
        if (coach.messages.isEmpty()) followLatest = true
        if (followLatest) {
            followingProgrammatically = true
            try {
                // Wait for the new bubble/card to be measured before finding the bottom.
                withFrameNanos { }
                if (motion.reduceMotion) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue)
                followLatest = true
                hasUnreadReply = false
            } finally { followingProgrammatically = false }
        } else hasUnreadReply = true
    }
    // Haptics fire at the start of the transition, not at the end of its animation.
    LaunchedEffect(state) {
        when (state) {
            CoachUiState.SUCCESS -> haptics.confirm()
            CoachUiState.FAIL -> haptics.reject()
            CoachUiState.RISK_BLOCKED -> haptics.reject()
            else -> Unit
        }
    }
    Surface(color = ReadyGymColors.Background, modifier = Modifier.fillMaxSize()) {
        // Draft → confirmed plan is one element moving, not two cards swapping places.
        SharedTransitionLayout {
            // Bottom inset is ime ∪ navigationBars (whichever is taller), not safeDrawingPadding:
            // safeDrawing already contains the ime inset, so stacking it with imePadding() padded
            // the keyboard height twice and left a keyboard-tall gap above the keys.
            Column(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            ) {
                ChatHeader(serviceConfigured, coachAvatar, state, onClose)
                Column(Modifier.weight(1f).testTag("coach-messages").verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 8.dp),
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
                // THINKING / TOOL_RUNNING: the typing wave, and — while a *proposal* is being
                // prepared — a bone-fide skeleton with the card's own geometry, so nothing jumps
                // when the real card lands. The skeleton is tied to the working states, not to
                // "a summary exists": a card that is already on screen must not show a placeholder
                // (the review gate forbids the second motion source, and a stale skeleton is one).
                if (state == CoachUiState.THINKING || state == CoachUiState.TOOL_RUNNING) {
                    TypingWave(state)
                    if (coach.reply?.planProposal == null && coach.reply?.confirmedCandidate == null) {
                        SkeletonCard(Modifier.testTag("coach-skeleton"))
                    }
                }
                val blocked = coach.reply?.status == "blocked"
                if (blocked) {
                    RiskBlock(coach.reply?.safetyRoute)
                }
                coach.localSafetyMessage?.let {
                    RiskCardEntrance { modifier ->
                        Panel(modifier.testTag("coach-risk-card"), container = ReadyGymColors.ErrorSurface) {
                            Text(it, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                        }
                    }
                }
                if (coach.correction) Text("直接告诉教练哪里不对，例如“我是在家训练”。修改后会重新整理摘要。", color = ReadyGymColors.MutedText)
                // A confirmed card is history, not a pending ask: once the user has saved it the
                // card must not sit in the transcript and re-appear when they come back.
                val candidate = coach.reply?.confirmedCandidate?.takeUnless { coach.reply?.confirmed == true }
                if (candidate != null) {
                    ProposingEnter(target = candidate.identityKey()) { target ->
                        ProfileSummaryCard(coach, candidate, state, Modifier) { onConfirm.value(it) }
                    }
                }
                if (coach.supportsFoundation) FoundationCards(coach, state, this@SharedTransitionLayout)
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
                // Card entrance/dwell only needs trailing room when a card can be on screen; adding
                // it unconditionally pushed the consent button below the fold on small viewports.
                if (state == CoachUiState.PROPOSING || state == CoachUiState.AWAITING_CONFIRM) {
                    Spacer(Modifier.height(ComposerEstimate))
                } else {
                    Spacer(Modifier.height(8.dp))
                }
                }
                if (hasUnreadReply) TextButton(onClick = {
                    followLatest = true; hasUnreadReply = false
                    scope.launch {
                        followingProgrammatically = true
                        try {
                            if (motion.reduceMotion) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue)
                        } finally { followingProgrammatically = false }
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("coach-new-reply")) {
                    Text("有新回复，点此查看")
                }
                Composer(coach, state, onSend = {
                    followLatest = true
                    hasUnreadReply = false
                    scope.launch {
                        followingProgrammatically = true
                        try {
                            if (motion.reduceMotion) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue)
                        } finally { followingProgrammatically = false }
                    }
                })
            }
        }
    }
}

/**
 * How much vertical room the composer needs at the bottom of the scrolling column.
 *
 * The composer (error text + input + send) is ~140dp; the extra headroom is what lets the user
 * scroll a card's confirm button fully into view. Without this the column simply ends behind the
 * composer and the most important control on the screen is unreachable.
 */
private val ComposerEstimate = 180.dp

/** The pending-work row: a typing wave plus a plain-language label, never the animation alone. */
@Composable
private fun TypingWave(state: CoachUiState) {
    AnimatedVisibility(
        visible = state.isWorking,
        enter = fadeIn(SunnyMotion.tweenShort()) + slideInVertically(SunnyMotion.tweenShort()) { it / 2 },
        exit = fadeOut(SunnyMotion.tweenShort()) + slideOutVertically(SunnyMotion.tweenShort()) { it / 2 },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("coach-working")) {
            TypingDots()
            Spacer(Modifier.width(8.dp))
            Text(
                if (state == CoachUiState.THINKING) "教练正在回复…" else "教练正在核对资料…",
                fontSize = 13.sp, color = ReadyGymColors.MutedText,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** RISK_BLOCKED: the serious card. A fade only — no bounce next to "请停止训练". */
@Composable
private fun RiskBlock(route: CoachSafetyRoute?) {
    RiskCardEntrance { modifier ->
        Panel(modifier.testTag("coach-blocked-card"), container = ReadyGymColors.ErrorSurface) {
            Text(when (route) {
                CoachSafetyRoute.STOP_AND_SEEK_CARE -> "请停止训练并及时寻求医疗评估。本应用不作诊断。"
                CoachSafetyRoute.PROFESSIONAL_REVIEW -> "请暂停训练并寻求专业评估。本应用不作诊断。"
                CoachSafetyRoute.UNSUPPORTED_EXPERIENCE -> "当前训练经验暂不支持；这不代表身体有风险。"
                null -> "无法确认身体状况，暂不能生成普通计划。"
            }, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
    }
}

/** Identity of a summary card, so `AnimatedContent` replays the entrance only for a new revision. */
private fun CoachCandidate.identityKey(): String =
    "${preferences.daysPerWeek}-${preferences.minutesPerSession}-${profile.age}-${profile.heightCm}-${profile.weightKg}-${profile.goal}"

@Composable
private fun ProfileSummaryCard(coach: CoachConversation, candidate: CoachCandidate, state: CoachUiState,
                               modifier: Modifier, onConfirmed: (CoachCandidate) -> Unit) {
    Card(modifier = modifier.fillMaxWidth().testTag("coach-profile-card"),
        colors = CardDefaults.cardColors(containerColor = ReadyGymColors.AccentSurface),
        shape = RoundedCornerShape(22.dp)) {
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
            Text("确认后前往「计划」，根据档案生成训练安排；计划也要你确认才会采用。", fontSize = 13.sp, color = ReadyGymColors.MutedText)
            ConfirmAttention(enabled = state == CoachUiState.AWAITING_CONFIRM) { actionModifier ->
                Button(onClick = { coach.confirm(onConfirmed) }, enabled = !coach.busy && !coach.localSafetyBlocked && coach.input.isBlank(),
                    modifier = actionModifier.fillMaxWidth().heightIn(min = 56.dp).testTag("coach-confirm-profile"), shape = RoundedCornerShape(16.dp)) {
                    Text("确认并保存档案", fontWeight = FontWeight.Bold)
                }
            }
            TextButton(onClick = { coach.correct() }, enabled = !coach.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("这些信息不对") }
        }
    }
}

/** A female user gets the female coach, a male user the male one; until then the original coach. */
/**
 * The coach's avatar. Male is the default (also for not-yet-stated/other) so the avatar never
 * shows a placeholder; it switches to the female portrait only when the user says she is female.
 */
internal fun coachAvatarFor(gender: Gender?): Int = when (gender) {
    Gender.FEMALE -> R.drawable.coach_avatar_female
    else -> R.drawable.coach_avatar_male
}

@Composable
private fun ChatAvatar(res: Int, size: androidx.compose.ui.unit.Dp = 36.dp) {
    Image(painterResource(res), contentDescription = null, modifier = Modifier.size(size).clip(CircleShape).testTag("avatar-$res"),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
}

/** Header badge: the connection state, plus the single-state machine's current phase for tests. */
@Composable
private fun ChatHeader(serviceConfigured: Boolean, coachAvatar: Int, state: CoachUiState, onClose: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // The conversation is a full-screen task opened from 首页; without a visible way back
            // the only exit would be the system back gesture.
            if (onClose != null) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose)
                        .testTag("coach-close").semantics { contentDescription = "返回首页" },
                    contentAlignment = Alignment.Center,
                ) { Text("‹", fontSize = 26.sp, color = ReadyGymColors.Text) }
                Spacer(Modifier.width(2.dp))
            }
            ChatAvatar(coachAvatar)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("READY GYM", fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, fontSize = 15.sp)
                Text("AI 建档 · 只聊健身与健康", fontSize = 12.sp, color = ReadyGymColors.Accent)
            }
            Column(Modifier.clip(RoundedCornerShape(20.dp))
                .background(if (serviceConfigured) ReadyGymColors.AccentSurface else ReadyGymColors.ErrorSurface)
                .padding(horizontal = 10.dp, vertical = 5.dp).testTag("coach-state-${state.wire}")) {
                Text(if (serviceConfigured) "已连接" else "未连接", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = if (serviceConfigured) ReadyGymColors.Accent else MaterialTheme.colorScheme.error)
            }
        }
        Hairline()
    }
}

@Composable
private fun Composer(coach: CoachConversation, state: CoachUiState, onSend: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        coach.error?.let { error ->
            // The server explains its own failures (daily limit, expired login, AI busy) in Chinese.
            val accountExpired = error.code == "unauthorized"
            val message = error.detail ?: when {
                accountExpired -> "登录已失效，请重新打开 App。"
                else -> coachErrorText(error.code)
            }
            // FAIL: a single 4dp shake, once per distinct failure. The text is already the
            // accessible channel; the shake only says "this is where it broke".
            ShakeOnce(trigger = error) { modifier ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                    modifier = modifier.semantics { liveRegion = LiveRegionMode.Assertive }.testTag("coach-error"),
                )
            }
            if (error.retryable) TextButton(onClick = { coach.retry() }, enabled = !coach.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("coach-retry")) { Text("重试") }
            // Only a session problem offers a restart; a bad key has to be re-entered on the key
            // screen, and offering "重新开始会话" there would just fail again.
            if (!accountExpired && (error.code in setOf("session_expired", "session_not_found", "session_unauthorized", "invalid_session_token", "http_401", "http_404", "http_410"))) {
                TextButton(onClick = { coach.restart() }, enabled = !coach.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新开始会话") }
            }
        }
        // WeChat-style composer: keyboard input OR hold-to-talk voice. The mic toggles modes;
        // holding the button streams partial recognition into the field, release commits it.
        // RECORD_AUDIO is a runtime permission: the first mic tap asks, and voice mode only
        // engages once granted (a denial leaves the keyboard in place with a hint).
        val voice = LocalVoiceInput.current
        val context = LocalContext.current
        var voiceMode by remember { mutableStateOf(false) }
        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) voiceMode = true
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                if (voiceMode) {
                    HoldToTalkButton(
                        voice = voice,
                        onCommit = {
                            // onFinal only fires with non-blank text on success; drop back to the
                            // text field so the user can review the transcript before sending.
                            coach.edit(if (coach.input.isBlank()) it else "$coach.input$it")
                            voiceMode = false
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("coach-hold-talk"))
                } else {
                    OutlinedTextField(coach.input, { coach.edit(it) }, enabled = coach.session != null && !coach.busy,
                        label = { Text("与教练交流") },
                        minLines = 1, maxLines = 3, isError = coach.input.length > 1000, shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().testTag("coach-input"))
                }
            }
            // Mic toggles voice mode; when a message is ready, the same slot becomes 发送.
            val canSend = coach.session != null && !coach.busy && !coach.planConfirmationUnknown &&
                coach.input.isNotBlank() && coach.input.length <= 1000
            if (canSend && !voiceMode) {
                Button(onClick = { onSend(); coach.send() }, enabled = !coach.busy,
                    modifier = Modifier.heightIn(min = 56.dp).testTag("coach-send"), shape = RoundedCornerShape(16.dp)) {
                    Text("发送", fontWeight = FontWeight.Bold)
                }
            } else {
                FilledIconButton(onClick = {
                    if (voiceMode) {
                        voiceMode = false
                        voice.cancel()
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                        voiceMode = true
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }, modifier = Modifier.size(56.dp).testTag("coach-voice-toggle")) {
                    Icon(if (voiceMode) GymIcons.Keyboard else GymIcons.Mic,
                        contentDescription = if (voiceMode) "切换到键盘" else "切换到语音")
                }
            }
        }
        if (voiceMode) {
            val voiceState by voice.state.collectAsState()
            (voiceState as? VoiceInputController.State.Error)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
        if (coach.input.length > 1000) Text("请缩短到1000字以内；输入仍保留。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
}

/** 微信"按住说话": press-and-hold listens, release commits the recognized text. */
@Composable
private fun HoldToTalkButton(voice: VoiceInputController, onCommit: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by voice.state.collectAsState()
    val listening = state is VoiceInputController.State.Listening
    var holding by remember { mutableStateOf(false) }
    val label = when {
        state is VoiceInputController.State.Error -> (state as VoiceInputController.State.Error).message
        listening && (state as VoiceInputController.State.Listening).partial.isNotBlank() ->
            (state as VoiceInputController.State.Listening).partial
        listening -> "正在听…松开结束"
        else -> "按住 说话"
    }
    Box(modifier
        .clip(RoundedCornerShape(18.dp))
        .background(if (holding) ReadyGymColors.AccentWash else ReadyGymColors.Sunken)
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    holding = true
                    voice.onFinal = onCommit
                    voice.start()
                    try { awaitRelease() } finally {
                        holding = false
                        voice.stop()
                    }
                })
        }
        .testTag("coach-hold-talk"),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = if (holding) ReadyGymColors.AccentText else ReadyGymColors.Text,
            maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp))
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
