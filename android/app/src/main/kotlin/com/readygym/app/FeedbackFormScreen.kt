package com.readygym.app

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 意见反馈: free text plus optional contact, submitted anonymously to the ReadyGym server.
 *
 * The form shows exactly what will be attached (app version, phone model, OS) so nothing is sent
 * silently; the operator reads the list with the admin token on the server.
 */
@Composable
fun FeedbackFormScreen(onSubmit: suspend (content: String, contact: String, appVersion: String, deviceModel: String, osVersion: String) -> Unit,
                       onClose: () -> Unit) {
    var content by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var sending by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var done by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val appVersion = "v${BuildConfig.VERSION_NAME}"
    val deviceModel = Build.MODEL ?: "unknown"
    val osVersion = "Android ${Build.VERSION.SDK_INT}"

    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .verticalScroll(rememberScrollState()).padding(horizontal = GymSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
    ) {
        Spacer(Modifier.height(GymSpacing.Md))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("意见反馈", fontSize = 29.sp, lineHeight = 35.sp, fontWeight = FontWeight.ExtraBold,
                    color = ReadyGymColors.Text)
                Text("哪里不好用、想要什么功能，直接写", fontSize = 14.sp, color = ReadyGymColors.MutedText)
            }
            Box(
                Modifier.height(40.dp).clip(GymShapes.Sm).background(ReadyGymColors.Sunken)
                    .clickable(role = Role.Button, onClick = onClose).testTag("feedback-close"),
                contentAlignment = Alignment.Center,
            ) { Text("关闭", fontSize = 13.sp, color = ReadyGymColors.Text, modifier = Modifier.padding(horizontal = 14.dp)) }
        }

        Panel {
            OutlinedTextField(content, { content = it.take(1000) }, Modifier.fillMaxWidth().testTag("feedback-content"),
                label = { Text("想说的话（必填）") }, minLines = 4,
                supportingText = { Text("${content.length}/1000") })
            OutlinedTextField(contact, { contact = it.take(100) }, Modifier.fillMaxWidth().testTag("feedback-contact"),
                label = { Text("联系方式（选填，方便回你）") }, singleLine = true)
            Text("会自动附上：$appVersion · $deviceModel · $osVersion，帮你更快定位问题。", fontSize = 12.sp,
                lineHeight = 18.sp, color = ReadyGymColors.MutedText)
            error?.let { Notice(it) }
            if (done) {
                Text("已收到，谢谢你！每一条都会看。", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = ReadyGymColors.AccentText, modifier = Modifier.testTag("feedback-done"))
                SecondaryButton("好的", onClose, Modifier.testTag("feedback-ok"))
            } else {
                PrimaryButton(if (sending) "发送中…" else "提交反馈", {
                    if (content.isBlank()) { error = "先写两句再提交吧"; return@PrimaryButton }
                    sending = true; error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                onSubmit(content.trim(), contact.trim(), BuildConfig.VERSION_NAME, deviceModel, osVersion)
                            }
                            done = true
                        } catch (e: CoachException) {
                            error = e.detail ?: "提交失败（${e.code}），检查网络后再试"
                        } finally { sending = false }
                    }
                }, Modifier.testTag("feedback-submit"), enabled = !sending, busy = sending)
            }
        }
        Spacer(Modifier.height(GymSpacing.Xxl))
    }
}
