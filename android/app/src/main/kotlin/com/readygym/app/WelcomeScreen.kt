package com.readygym.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * First launch: pick a nickname and an anonymous account is created on the server. No phone number,
 * no password; the nickname is only what other people see on the leaderboard.
 */
@Composable
fun WelcomeScreen(
    onRegister: (String) -> Unit,
    busy: Boolean,
    error: String?,
    initialNickname: String = "",
    title: String = "欢迎来到\nReady Gym",
    confirmLabel: String = "开始",
    onCancel: (() -> Unit)? = null,
) {
    var nickname by rememberSaveable { mutableStateOf(initialNickname) }
    val trimmed = nickname.trim()
    val valid = trimmed.isNotEmpty() && trimmed.codePointCount(0, trimmed.length) <= 12

    Surface(color = ReadyGymColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = GymSpacing.Xxl),
                verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
            ) {
                Spacer(Modifier.height(36.dp))
                Image(painterResource(R.drawable.user_avatar_default), contentDescription = null,
                    modifier = Modifier.size(96.dp).clip(CircleShape).align(Alignment.CenterHorizontally),
                    contentScale = ContentScale.Crop)
                Spacer(Modifier.height(8.dp))
                Text(title, fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.ExtraBold)
                Text("懂你的私人 AI 健身教练：了解你的情况后制定计划，练完根据你的反馈调整。",
                    color = ReadyGymColors.MutedText, fontSize = 15.sp, lineHeight = 24.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it.take(24) },
                    label = { Text("给自己起个昵称") },
                    placeholder = { Text("最多 12 个字", color = ReadyGymColors.FaintText) },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    shape = GymShapes.Md,
                    modifier = Modifier.fillMaxWidth().testTag("welcome-nickname"),
                )
                Text("昵称会显示在「自律排行榜」上。不需要手机号或密码；训练计划和记录保存在这台手机上。",
                    fontSize = 12.sp, lineHeight = 18.sp, color = ReadyGymColors.MutedText)
                error?.let { Notice(it) }
            }
            Column(Modifier.padding(horizontal = GymSpacing.Xxl, vertical = GymSpacing.Lg), verticalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
                PrimaryButton(if (busy) "正在连接…" else confirmLabel, { onRegister(trimmed) },
                    Modifier.testTag("welcome-start"), enabled = valid && !busy, busy = busy)
                if (onCancel != null) TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("取消", color = ReadyGymColors.MutedText)
                }
            }
        }
    }
}
