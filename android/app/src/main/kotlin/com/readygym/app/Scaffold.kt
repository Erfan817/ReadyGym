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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Three destinations: the conversation, training, and the profile.
 *
 * The middle action is a raised circle rather than a third equal tab, because starting training is
 * the thing the app exists for and it is reached from anywhere. It is still a full-height 48dp+
 * touch target and it is exposed to accessibility as a button, not as a tab.
 */
enum class AppTab { CHAT, TRAIN, PROFILE }

@Composable
fun ReadyGymScaffold(
    tab: AppTab,
    onTabChange: (AppTab) -> Unit,
    chat: @Composable () -> Unit,
    train: @Composable () -> Unit,
    profile: @Composable () -> Unit,
) {
    Surface(color = ReadyGymColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    AppTab.CHAT -> chat()
                    AppTab.TRAIN -> train()
                    AppTab.PROFILE -> profile()
                }
            }
            BottomBar(tab, onTabChange)
        }
    }
}

@Composable
private fun BottomBar(tab: AppTab, onTabChange: (AppTab) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(ReadyGymColors.Outline.copy(alpha = 0.6f)))
        Row(
            Modifier.fillMaxWidth().background(ReadyGymColors.Surface)
                .navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabItem("对话", Icons.Outlined.Email, tab == AppTab.CHAT,
                Modifier.weight(1f).testTag("tab-chat")) { onTabChange(AppTab.CHAT) }
            CenterAction(onClick = { onTabChange(AppTab.TRAIN) }, active = tab == AppTab.TRAIN,
                modifier = Modifier.weight(1f).testTag("tab-train"))
            TabItem("账户", Icons.Outlined.Person, tab == AppTab.PROFILE,
                Modifier.weight(1f).testTag("tab-profile")) { onTabChange(AppTab.PROFILE) }
        }
    }
}

@Composable
private fun TabItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (selected) ReadyGymColors.AccentText else ReadyGymColors.MutedText
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 8.dp)
            .semantics {
                contentDescription = if (selected) "$label，已选中" else label
                role = Role.Tab
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Text(label, fontSize = 11.sp, color = tint,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun CenterAction(
    onClick: () -> Unit,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp)
            .semantics { contentDescription = "开始训练" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape)
                .background(if (active) ReadyGymColors.AccentText else ReadyGymColors.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(28.dp), tint = ReadyGymColors.Surface)
        }
        Text("开始", fontSize = 11.sp,
            color = if (active) ReadyGymColors.AccentText else ReadyGymColors.MutedText,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
    }
}
