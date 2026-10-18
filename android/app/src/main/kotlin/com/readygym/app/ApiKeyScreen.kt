package com.readygym.app

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * First run: paste a DeepSeek API key and start.
 *
 * Why this screen exists — the previous flow required the user to host a Python service and a Java
 * service before they could say a word. For a single person tracking their own training that is a
 * disproportionate amount of infrastructure, and it was the main reason the app was hard to try.
 * Bringing your own key removes the server entirely: the key stays in the Android Keystore and is
 * sent only to api.deepseek.com.
 *
 * The disclosure the old welcome screen carried is kept, but it is now accurate: nothing goes to a
 * "Ready Gym 服务端" because there is no such server in this mode.
 */
@Composable
fun ApiKeyScreen(
    onSave: (String) -> Unit,
    busy: Boolean = false,
    error: String? = null,
    hasStoredKey: Boolean = false,
    onForget: () -> Unit = {},
    /** Non-null when the user is *replacing* an existing key, offering a way back. */
    onCancel: (() -> Unit)? = null,
    /** Short echo of the currently stored key (e.g. "…abcd"), never the full key. */
    keyHint: String? = null,
) {
    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val trimmed = key.trim()
    val looksValid = trimmed.length >= 20 && trimmed.startsWith("sk-")

    Surface(color = ReadyGymColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.coach_avatar),
                        contentDescription = null,
                        modifier = Modifier.size(46.dp).clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("READY GYM", fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, fontSize = 15.sp)
                        Text("你的AI训练教练", fontSize = 12.sp, color = ReadyGymColors.AccentText)
                    }
                }

                Spacer(Modifier.height(36.dp))
                Text("填一个 API Key，\n就能开始。", fontSize = 34.sp, lineHeight = 44.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
                Spacer(Modifier.height(12.dp))
                Text("不需要自己搭服务器。Key 只存在这台手机里，只有发消息时才会送到 DeepSeek。",
                    color = ReadyGymColors.MutedText, fontSize = 15.sp, lineHeight = 24.sp)

                Spacer(Modifier.height(26.dp))
                CardBlock {
                    Text("去哪里拿", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("platform.deepseek.com → API keys → 创建。费用由你自己的账号承担，按量计费。",
                        fontSize = 13.sp, lineHeight = 21.sp, color = ReadyGymColors.MutedText)
                    Spacer(Modifier.height(8.dp))
                    Text("服务未配置，不能连接AI", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.take(200) },
                    label = { Text("DeepSeek API Key") },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    isError = error != null,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("api-key-input"),
                    trailingIcon = {
                        TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示", fontSize = 12.sp) }
                    },
                )
                if (!looksValid && trimmed.isNotEmpty()) {
                    Text("DeepSeek 的 Key 通常以 sk- 开头，请检查是否粘贴完整。",
                        fontSize = 12.sp, color = ReadyGymColors.Warning, lineHeight = 18.sp)
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 20.sp)
                }

                if (hasStoredKey) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = ReadyGymColors.AccentText)
                        Spacer(Modifier.width(6.dp))
                        Text("这台手机已保存过一个 Key", fontSize = 13.sp, color = ReadyGymColors.AccentText)
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = onForget) { Text("删除", fontSize = 13.sp) }
                    }
                }

                Spacer(Modifier.height(20.dp))
                CardBlock {
                    Text("关于你的数据", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("• 你输入的内容会发送给 DeepSeek，用于生成回复和整理档案\n" +
                        "• Key 用 Android Keystore 加密保存在本机，不上传，不写日志\n" +
                        "• 仅限 18 岁及以上\n" +
                        "• 不要发送姓名、联系方式等无关个人信息",
                        fontSize = 13.sp, lineHeight = 22.sp, color = ReadyGymColors.MutedText)
                    Spacer(Modifier.height(8.dp))
                    Text("训练模板由本地规则生成，未经专业审核，不构成医疗建议。",
                        fontSize = 12.sp, lineHeight = 19.sp, color = ReadyGymColors.FaintText)
                }
                Spacer(Modifier.height(18.dp))

                // Actions live inside the scroll container. Pinning them below it makes
                // `performScrollTo` fail for accessibility tooling and leaves the buttons
                // unreachable at large font sizes on short viewports.
                Button(
                    onClick = { onSave(trimmed) },
                    enabled = looksValid && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("api-key-save"),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                            color = ReadyGymColors.Surface)
                        Spacer(Modifier.width(10.dp))
                        Text("正在验证…", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    } else {
                        Text("保存并开始", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
                if (onCancel != null) {
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        onClick = onCancel,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("取消，保留当前 Key") }
                }
                if (keyHint != null) {
                    Spacer(Modifier.height(10.dp))
                    Text("当前保存的是：$keyHint", fontSize = 13.sp, color = ReadyGymColors.MutedText,
                        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun CardBlock(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(ReadyGymColors.Surface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}
