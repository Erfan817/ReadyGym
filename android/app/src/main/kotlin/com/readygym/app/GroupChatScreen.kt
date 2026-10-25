package com.readygym.app

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The supplied QQ group invitation stays bundled and readable without a network connection. */
@Composable
internal fun GroupChatScreen(onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by rememberSaveable { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = ReadyGymColors.Background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = GymSpacing.Lg, vertical = GymSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(ReadyGymColors.Surface)
                    .clickable(role = Role.Button, onClickLabel = "返回我的", onClick = onClose)
                    .testTag("group-close"), contentAlignment = Alignment.Center) {
                    Icon(GymIcons.ChevronLeft, "返回我的", Modifier.size(20.dp), tint = ReadyGymColors.Text)
                }
                Spacer(Modifier.width(GymSpacing.Md))
                Text("软件交流群", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.semantics { heading() })
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = GymSpacing.Lg), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(GymSpacing.Md)) {
                    Text("一起聊训练，也聊聊 ReadyGym", fontSize = 14.sp,
                        color = ReadyGymColors.MutedText, modifier = Modifier.padding(top = GymSpacing.Sm))
                    Image(painterResource(R.drawable.community_group_qr),
                        contentDescription = "ReadyGym 交流群 QQ 二维码，群号 158476703",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().clip(GymShapes.Lg).testTag("group-qr"))
                    Panel {
                        Text("QQ群：158476703", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("截图后打开 QQ 扫一扫，从相册选择图片；也可以复制群号，在 QQ 中搜索加入。",
                            fontSize = 14.sp, lineHeight = 22.sp, color = ReadyGymColors.MutedText)
                        PrimaryButton(if (copied) "已复制群号" else "复制群号", {
                            clipboard.setText(AnnotatedString("158476703"))
                            copied = true
                        }, Modifier.testTag("group-copy-number").semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Spacer(Modifier.height(GymSpacing.Lg))
                }
            }
        }
    }
}
