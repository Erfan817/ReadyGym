package com.readygym.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 打赏作者: the developer's WeChat reward QR, plus a one-line thank-you. */
@Composable
fun DonateScreen(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(ReadyGymColors.Background).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = GymSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
    ) {
        Spacer(Modifier.height(GymSpacing.Md))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("打赏作者", fontSize = 29.sp, lineHeight = 35.sp, fontWeight = FontWeight.ExtraBold,
                    color = ReadyGymColors.Text)
                Text("App 完全免费，你的支持就是更新动力", fontSize = 14.sp, color = ReadyGymColors.MutedText)
            }
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(ReadyGymColors.Sunken)
                    .clickable(role = Role.Button, onClick = onClose).testTag("donate-close")
                    .semantics { contentDescription = "关闭打赏页" },
                contentAlignment = Alignment.Center,
            ) { Icon(GymIcons.Close, null, Modifier.size(18.dp), tint = ReadyGymColors.Text) }
        }

        Panel {
            Text("微信扫码打赏", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Image(
                painterResource(R.drawable.donate_qr),
                contentDescription = "微信赞赏码，截图后用微信扫一扫识别",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).testTag("donate-qr"),
            )
            Text("截图后打开微信扫一扫，从相册选择图片。", fontSize = 13.sp, lineHeight = 19.sp,
                color = ReadyGymColors.MutedText)
            Text("金额随意，一分也是心意。打赏不影响任何功能。", fontSize = 13.sp, lineHeight = 19.sp,
                color = ReadyGymColors.MutedText)
        }
        Spacer(Modifier.height(GymSpacing.Xxl))
    }
}
