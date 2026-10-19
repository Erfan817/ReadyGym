package com.readygym.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Four destinations. 训练 is the home tab: the app exists to get the user through today's workout,
 * and back from any other tab returns there.
 */
enum class AppTab(val label: String, val tag: String) {
    COACH("教练", "tab-coach"), TRAIN("训练", "tab-train"), RECORDS("记录", "tab-records"), ME("我的", "tab-me")
}

@Composable
fun ReadyGymScaffold(
    tab: AppTab,
    onTabChange: (AppTab) -> Unit,
    coach: @Composable () -> Unit,
    train: @Composable () -> Unit,
    records: @Composable () -> Unit,
    me: @Composable () -> Unit,
) {
    Surface(color = ReadyGymColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).statusBarsPadding().consumeWindowInsets(WindowInsets.navigationBars)) {
                when (tab) {
                    AppTab.COACH -> coach()
                    AppTab.TRAIN -> train()
                    AppTab.RECORDS -> records()
                    AppTab.ME -> me()
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
                .navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTab.entries.forEach { t ->
                TabItem(t.label, t.icon, tab == t, Modifier.weight(1f).testTag(t.tag)) { onTabChange(t) }
            }
        }
    }
}

private val AppTab.icon: ImageVector get() = when (this) {
    AppTab.COACH -> Icons.Outlined.Email
    AppTab.TRAIN -> Icons.Filled.PlayArrow
    AppTab.RECORDS -> Icons.AutoMirrored.Filled.List
    AppTab.ME -> Icons.Outlined.Person
}

@Composable
private fun TabItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (selected) ReadyGymColors.Text else ReadyGymColors.FaintText
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(Modifier.size(width = 44.dp, height = 28.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) ReadyGymColors.AccentWash else ReadyGymColors.Surface), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (selected) ReadyGymColors.AccentText else tint)
        }
        Text(label, fontSize = 11.sp, color = tint,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}
