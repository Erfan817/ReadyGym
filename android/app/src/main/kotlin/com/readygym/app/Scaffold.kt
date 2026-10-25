package com.readygym.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Five destinations: 首页 is the coach, 开始 is the one thing the app exists for, and the middle
 * button is the same action the home card offers, reachable from any tab.
 *
 * 我的 took the fifth slot that used to be 动作库: the exercise catalogue is a thing the coach
 * reads, not a place the user browses, while the profile is what both the plan and the stats are
 * computed from. Keeping that in the tab bar costs one slot and saves a trip through 训练.
 */
enum class AppTab(val label: String, val tag: String, val icon: ImageVector, val contentDescription: String) {
    HOME("首页", "tab-home", GymIcons.Home, "首页，AI 教练"),
    PLAN("计划", "tab-plan", GymIcons.Calendar, "计划"),
    START("开始", "tab-start", GymIcons.Play, "开始训练"),
    STATS("统计", "tab-stats", GymIcons.Bars, "统计"),
    ME("我的", "tab-me", GymIcons.Person, "我的，训练资料"),
}

/**
 * The shell. All five slots are destinations; [AppTab.START] just happens to be drawn as the raised
 * circle in the middle because it is the action the app exists for.
 */
@Composable
fun ReadyGymScaffold(
    tab: AppTab,
    onTabChange: (AppTab) -> Unit,
    home: @Composable () -> Unit,
    plan: @Composable () -> Unit,
    start: @Composable () -> Unit,
    stats: @Composable () -> Unit,
    me: @Composable () -> Unit,
) {
    val savedPages = rememberSaveableStateHolder()
    val motion = LocalMotionSettings.current
    Surface(color = ReadyGymColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).statusBarsPadding().consumeWindowInsets(WindowInsets.navigationBars)) {
                Crossfade(targetState = tab,
                    animationSpec = if (motion.reduceMotion) SunnyMotion.instant() else SunnyMotion.tweenShort(),
                    label = "tab-content") { destination ->
                    savedPages.SaveableStateProvider(destination.name) {
                        when (destination) {
                            AppTab.HOME -> home()
                            AppTab.PLAN -> plan()
                            AppTab.START -> start()
                            AppTab.STATS -> stats()
                            AppTab.ME -> me()
                        }
                    }
                }
            }
            BottomBar(tab, onTabChange)
        }
    }
}

/**
 * The bottom bar. A hairline separates it from the page; the middle circle overlaps the hairline
 * by 14dp so it reads as sitting *on* the bar rather than inside a slot cut out of it.
 */
@Composable
private fun BottomBar(tab: AppTab, onTabChange: (AppTab) -> Unit) {
    Column {
        // The hairline stops where the raised circle is: a 1dp rule drawn behind a 52dp disc shows
        // up as a line crossing the disc's lower half, which reads as a rendering artefact.
        Row(Modifier.fillMaxWidth().height(1.dp)) {
            Box(Modifier.weight(1f).fillMaxSize().background(ReadyGymColors.Outline.copy(alpha = 0.35f)))
            Box(Modifier.width(72.dp).fillMaxSize())
            Box(Modifier.weight(1f).fillMaxSize().background(ReadyGymColors.Outline.copy(alpha = 0.35f)))
        }
        Box(Modifier.fillMaxWidth().background(ReadyGymColors.Surface).navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabItem(AppTab.HOME, tab == AppTab.HOME, Modifier.weight(1f)) { onTabChange(AppTab.HOME) }
                TabItem(AppTab.PLAN, tab == AppTab.PLAN, Modifier.weight(1f)) { onTabChange(AppTab.PLAN) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    StartButton(tab == AppTab.START) { onTabChange(AppTab.START) }
                }
                TabItem(AppTab.STATS, tab == AppTab.STATS, Modifier.weight(1f)) { onTabChange(AppTab.STATS) }
                TabItem(AppTab.ME, tab == AppTab.ME, Modifier.weight(1f)) { onTabChange(AppTab.ME) }
            }
        }
    }
}

/**
 * The one saturated control on every screen. It lifts 14dp over the bar and presses down to 0.94
 * before settling back on the standard spring, so the tap is answered before the next screen loads.
 *
 * Selected and unselected are different fills, not just different tints: on this bar the circle has
 * no label underneath to read the current tab from, so it has to say it itself.
 */
@Composable
private fun StartButton(selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalMotionSettings.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !motion.reduceMotion) 0.94f else 1f,
        animationSpec = if (motion.reduceMotion) SunnyMotion.instant() else SunnyMotion.springSnappy(),
        label = "start-scale",
    )
    Box(
        Modifier
            .width(72.dp)
            .height(58.dp)
            .offset(y = (-14).dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = "开始训练"; this.selected = selected }
            .testTag("tab-start"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(if (selected) ReadyGymColors.Accent else ReadyGymColors.AccentWash),
            contentAlignment = Alignment.Center,
        ) {
            Icon(GymIcons.Play, null, Modifier.size(22.dp),
                tint = if (selected) ReadyGymColors.OnAccent else ReadyGymColors.AccentText)
        }
    }
}

@Composable
private fun TabItem(item: AppTab, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = if (selected) ReadyGymColors.Text else ReadyGymColors.FaintText
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 3.dp)
            .testTag(item.tag)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(item.icon, item.contentDescription, Modifier.size(22.dp), tint = tint)
        Text(
            item.label,
            fontSize = 11.sp,
            color = tint,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
