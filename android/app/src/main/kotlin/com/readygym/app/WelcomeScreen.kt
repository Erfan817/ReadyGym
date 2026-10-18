package com.readygym.app

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The entry screen: one promise, three steps, one disclosure, one action.
 *
 * Layout rationale — the old screen stacked a policy card, three paragraphs of caveats and two
 * buttons in one undifferentiated column, so nothing led the eye and the disclosure read as
 * fine print. This version keeps every sentence the old screen had (the consent copy is
 * unchanged, word for word) but gives the page a reading order: what this is → what it does →
 * what you are agreeing to → the action.
 *
 * The consent stays on this screen rather than behind a tap, because the start button must not
 * be reachable without the disclosure being visible above it.
 */
@Composable
fun WelcomeScreen(
    serviceConfigured: Boolean,
    onStart: () -> Unit,
    onConfigure: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    Surface(color = ReadyGymColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 26.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark()
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("READY GYM", fontWeight = FontWeight.ExtraBold, letterSpacing = 2.5.sp, fontSize = 15.sp)
                        Text("你的AI训练教练", fontSize = 12.sp, color = ReadyGymColors.Accent)
                    }
                }

                Spacer(Modifier.height(38.dp))
                Text("练得明白，\n才算练过。", fontSize = 40.sp, lineHeight = 48.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
                Spacer(Modifier.height(14.dp))
                Text("用自己的话聊聊就好。我会了解你的目标、训练条件和当前身体状态，再请你确认资料。",
                    color = ReadyGymColors.MutedText, fontSize = 16.sp, lineHeight = 26.sp)

                Spacer(Modifier.height(28.dp))
                FeatureRow("01", "按条件排计划", "器械、天数、每次时长，都由你的实际情况决定")
                FeatureRow("02", "每个动作看得到", "起始与发力要点，配演示图与注意事项")
                FeatureRow("03", "不舒服就先停", "身体信号优先于训练计划，不硬推")

                Spacer(Modifier.height(24.dp))

                // The consent block. Copy is verbatim from the previous entry screen.
                Panel {
                    Text("开始前，请你知情", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("同意后，你的回答与训练资料会发送至Ready Gym服务端和DeepSeek，用于AI问询。仅限18岁及以上；不要发送姓名、联系方式等无关资料。",
                        fontSize = 14.sp, lineHeight = 22.sp)
                    Hairline()
                    Text(if (serviceConfigured) "服务地址已配置，尚未连接；点击后才请求AI首问。" else "服务未配置，不能连接AI",
                        color = if (serviceConfigured) ReadyGymColors.MutedText else MaterialTheme.colorScheme.error,
                        fontSize = 13.sp, lineHeight = 20.sp)
                    onConfigure?.let { configure ->
                        TextButton(onClick = configure, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("配置Demo服务与访问码")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Agent只调用有界规则工具提出草稿；资料与计划分别确认，不自动采用。所有模板未经专业审核。",
                    fontSize = 13.sp, lineHeight = 21.sp, color = ReadyGymColors.MutedText)
                Spacer(Modifier.height(20.dp))

                // Actions live inside the scroll container, not pinned below it. Pinning them
                // made the screen unscrollable-to for accessibility tooling and left the buttons
                // unreachable at large font sizes on short viewports.
                Button(
                    onClick = onStart,
                    enabled = serviceConfigured && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag("welcome-start"),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ReadyGymColors.OnAction)
                        Spacer(Modifier.width(10.dp))
                        Text("正在连接…", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    } else {
                        Text("同意并开始", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        if (serviceConfigured) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("仅限18岁及以上 · 训练模板未经专业审核，不构成医疗建议",
                    fontSize = 12.sp, color = ReadyGymColors.MutedText, textAlign = TextAlign.Center,
                    lineHeight = 18.sp, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(ReadyGymColors.Action),
        contentAlignment = Alignment.Center,
    ) { Text("R", color = ReadyGymColors.OnAction, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp) }
}

@Composable
private fun FeatureRow(number: String, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
        Text(number, color = ReadyGymColors.Action, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            modifier = Modifier.width(30.dp).padding(top = 3.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(3.dp))
            Text(detail, color = ReadyGymColors.MutedText, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}
