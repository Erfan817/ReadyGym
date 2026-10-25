package com.readygym.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.exercise.Exercise
import com.readygym.core.exercise.label
import com.readygym.core.plan.formatKg
import com.readygym.core.workout.SetLog
import com.readygym.core.workout.WorkoutSession
import kotlinx.coroutines.delay

/**
 * The workout in progress, one exercise at a time. Every edit is written to Room by the ViewModel,
 * so leaving this screen (or the app being killed) never loses a logged set.
 */
@Composable
fun WorkoutScreen(vm: GymViewModel, onClose: () -> Unit, onFinished: (WorkoutSession) -> Unit) {
    val session = vm.workout
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(ReadyGymColors.Background).safeDrawingPadding().imePadding()) {
        if (session == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        // Exercises in plan order; each keeps the indices of its sets in session.sets.
        val groups = remember(session.id, session.sets.size) {
            session.sets.indices.groupBy { session.sets[it].exerciseId }.toList()
        }
        var current by rememberSaveable(session.id) { mutableStateOf(firstUnfinished(session, groups)) }
        current = current.coerceIn(groups.indices)
        var ending by rememberSaveable { mutableStateOf(false) }
        val (exerciseId, indices) = groups[current]
        val exercise = vm.library[exerciseId]
        val motion = LocalMotionSettings.current
        val changed = remember { Animatable(1f) }
        val scroll = rememberScrollState()
        LaunchedEffect(exerciseId, motion.reduceMotion) {
            scroll.scrollTo(0)
            if (motion.reduceMotion) changed.snapTo(1f) else {
                changed.snapTo(0.75f)
                changed.animateTo(1f, SunnyMotion.tweenShort())
            }
        }
        var showDemo by rememberSaveable(session.id) { mutableStateOf(true) }
        val completed = indices.count { session.sets[it].done }

        TopBar(session, current, groups.size, onClose = onClose, onEnd = { ending = true })
        Column(Modifier.padding(horizontal = GymSpacing.Lg, vertical = GymSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(GymSpacing.Xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(exercise?.name ?: exerciseId, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text("$completed / ${indices.size} 组", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = ReadyGymColors.AccentText, modifier = Modifier.testTag("workout-exercise-progress"))
            }
            exercise?.let { e ->
                Text((listOf(e.primary) + e.secondary).distinct().take(3).joinToString(" · ") { it.label },
                    fontSize = 13.sp, color = ReadyGymColors.MutedText)
            }
            LinearProgressIndicator(progress = { completed.toFloat() / indices.size },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(GymShapes.Xs),
                color = ReadyGymColors.AccentText, trackColor = ReadyGymColors.Sunken)
        }
        Column(
            Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = GymSpacing.Lg)
                .graphicsLayer { alpha = changed.value },
            verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
        ) {
            TextButton(onClick = { showDemo = !showDemo }, modifier = Modifier.fillMaxWidth().testTag("workout-demo-toggle")) {
                Text(if (showDemo) "收起动作示范 ⌃" else "查看动作示范 ⌄", color = ReadyGymColors.AccentText)
            }
            if (showDemo) ExerciseImage(exercise, animate = true,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.4f).testTag("workout-demo"))
            SetTable(vm, session, exercise, indices)
            exercise?.let { Tips(it) }
            Spacer(Modifier.height(GymSpacing.Sm))
        }
        RestBanner(vm, session)
        Row(Modifier.padding(horizontal = GymSpacing.Lg, vertical = GymSpacing.Sm), horizontalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
            if (current > 0) SecondaryButton("上一个", { current-- }, Modifier.weight(1f))
            if (current < groups.lastIndex) PrimaryButton("下一个动作", { current++ }, Modifier.weight(2f).testTag("workout-next"))
            else PrimaryButton("完成训练", { ending = true }, Modifier.weight(2f).testTag("workout-finish"))
        }
        if (ending) EndDialog(session,
            onFinish = { ending = false; vm.finishWorkout(onFinished) },
            onDiscard = { ending = false; vm.discardWorkout(); onClose() },
            onDismiss = { ending = false })
    }
}

private fun firstUnfinished(s: WorkoutSession, groups: List<Pair<String, List<Int>>>): Int =
    groups.indexOfFirst { (_, idx) -> idx.any { !s.sets[it].done } }.takeIf { it >= 0 } ?: 0

@Composable
private fun TopBar(session: WorkoutSession, current: Int, total: Int, onClose: () -> Unit, onEnd: () -> Unit) {
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }
    Row(Modifier.fillMaxWidth().padding(horizontal = GymSpacing.Sm, vertical = GymSpacing.Xs), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClose) { Text("‹ 返回", color = ReadyGymColors.Text) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("动作 ${current + 1} / $total", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(clock((now - session.startedAtMillis) / 1000), fontSize = 12.sp, color = ReadyGymColors.MutedText)
        }
        TextButton(onClick = onEnd, modifier = Modifier.testTag("workout-end")) { Text("结束", color = ReadyGymColors.Error) }
    }
}

@Composable
private fun RestBanner(vm: GymViewModel, session: WorkoutSession) {
    val endsAt = vm.restEndsAt
    val now by produceState(System.currentTimeMillis(), endsAt) {
        if (endsAt == null) return@produceState
        while (true) { value = System.currentTimeMillis(); delay(250) }
    }
    val remaining = endsAt?.let { ((it - now + 999) / 1000).coerceAtLeast(0) }
    val haptics = rememberHaptics()
    LaunchedEffect(endsAt, remaining == 0L) {
        if (endsAt != null && remaining == 0L) { vm.skipRest(); haptics.confirm() }
    }
    WorkoutRestDock(remainingSeconds = remaining.takeIf { endsAt != null },
        completedSets = session.completedSets.size, totalSets = session.sets.size,
        onAddRest = { haptics.tick(); vm.addRest(30) }, onSkipRest = { haptics.tick(); vm.skipRest() })
}

/** Always occupies the same space, so starting or skipping rest never moves the set table. */
@Composable
internal fun WorkoutRestDock(remainingSeconds: Long?, completedSets: Int, totalSets: Int,
                             onAddRest: () -> Unit, onSkipRest: () -> Unit) {
    val resting = remainingSeconds != null
    Row(
        Modifier.padding(horizontal = GymSpacing.Lg).fillMaxWidth().heightIn(min = 88.dp)
            .clip(GymShapes.Md).background(ReadyGymColors.Action)
            .padding(horizontal = GymSpacing.Lg, vertical = GymSpacing.Sm)
            .testTag("workout-rest-dock")
            .semantics { contentDescription = if (resting) "组间休息，剩余 $remainingSeconds 秒" else "已完成 $completedSets/$totalSets 组" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (resting) "组间休息" else if (completedSets == totalSets) "这次的组数已完成" else "准备好再做下一组",
                fontSize = 12.sp, color = ReadyGymColors.OnAction.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (resting) clock(requireNotNull(remainingSeconds)) else "$completedSets / $totalSets 组",
                fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.OnAction,
                maxLines = 1, modifier = Modifier.testTag("workout-rest-status"))
        }
        if (resting) {
            Pill("+30 秒", "workout-rest-add", onAddRest)
            Spacer(Modifier.width(GymSpacing.Sm))
            Pill("跳过", "workout-rest-skip", onSkipRest)
        }
    }
}

@Composable
private fun Pill(text: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clip(GymShapes.Sm).background(ReadyGymColors.OnAction.copy(alpha = 0.15f))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp).testTag(tag),
        contentAlignment = Alignment.Center) {
        Text(text, color = ReadyGymColors.OnAction, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SetTable(vm: GymViewModel, session: WorkoutSession, exercise: Exercise?, indices: List<Int>) {
    val timed = session.sets[indices.first()].seconds != null
    val loadable = exercise?.loadable ?: (session.sets[indices.first()].loadKg != null)
    val last = vm.lastSets[session.sets[indices.first()].exerciseId].orEmpty()
    Panel {
        Row(Modifier.fillMaxWidth()) {
            HeaderCell("组", Modifier.width(32.dp))
            HeaderCell("上次", Modifier.weight(1.2f))
            if (loadable) HeaderCell("kg", Modifier.weight(1f))
            HeaderCell(if (timed) "秒" else "次数", Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }
        indices.forEach { i ->
            val set = session.sets[i]
            WorkoutSetRow(session.id, i, set, last.getOrNull(set.setIndex), timed, loadable,
                active = i == indices.firstOrNull { !session.sets[it].done }) { vm.updateSet(i, it) }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text, modifier, fontSize = 12.sp, color = ReadyGymColors.MutedText, textAlign = TextAlign.Center)
}

@Composable
internal fun WorkoutSetRow(sessionId: Long, index: Int, set: SetLog, previous: SetLog?, timed: Boolean, loadable: Boolean,
                           active: Boolean, onChange: (SetLog) -> Unit) {
    val done = set.done
    val haptics = rememberHaptics()
    val motion = LocalMotionSettings.current
    val target = when { done -> ReadyGymColors.AccentWash; active -> ReadyGymColors.Background; else -> ReadyGymColors.Surface }
    val animatedColor by animateColorAsState(target,
        if (motion.reduceMotion) SunnyMotion.instant() else SunnyMotion.tweenShort(), label = "set-completion")
    Row(
        Modifier.fillMaxWidth().clip(GymShapes.Sm).background(if (motion.reduceMotion) target else animatedColor)
            .border(1.dp, if (active) ReadyGymColors.AccentText else androidx.compose.ui.graphics.Color.Transparent, GymShapes.Sm)
            .padding(vertical = 4.dp).testTag("set-row-$index"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${set.setIndex + 1}", textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                color = if (done || active) ReadyGymColors.AccentText else ReadyGymColors.Text)
            if (active) Text("当前", fontSize = 9.sp, color = ReadyGymColors.AccentText)
        }
        Text(previous?.let(::shortSet) ?: "—", Modifier.weight(1.2f), textAlign = TextAlign.Center,
            fontSize = 14.sp, color = ReadyGymColors.MutedText)
        if (loadable) NumberCell("第${set.setIndex + 1}组重量", "$sessionId-$index-kg", set.loadKg?.let(::formatKg).orEmpty(),
            decimal = true, Modifier.weight(1f)) { text -> onChange(set.copy(loadKg = text.toDoubleOrNull()?.takeIf { it in 0.0..300.0 })) }
        if (timed) NumberCell("第${set.setIndex + 1}组秒数", "$sessionId-$index-s", set.seconds?.toString().orEmpty(),
            decimal = false, Modifier.weight(1f)) { text -> onChange(set.copy(seconds = text.toIntOrNull()?.coerceIn(0, 600))) }
        else NumberCell("第${set.setIndex + 1}组次数", "$sessionId-$index-r", set.reps?.toString().orEmpty(),
            decimal = false, Modifier.weight(1f)) { text -> onChange(set.copy(reps = text.toIntOrNull()?.coerceIn(0, 100))) }
        Box(Modifier.size(48.dp).clip(GymShapes.Xs)
            .toggleable(value = done, role = Role.Checkbox, onValueChange = {
                if (it) haptics.confirm() else haptics.tick()
                onChange(set.copy(done = it))
            })
            .semantics { contentDescription = "第${set.setIndex + 1}组完成"; stateDescription = if (done) "已完成，点击可撤销" else "未完成" }
            .testTag("set-done-$index"), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(32.dp).clip(GymShapes.Xs).background(if (done) ReadyGymColors.AccentWash else ReadyGymColors.Sunken),
                contentAlignment = Alignment.Center,
            ) { Text("✓", color = if (done) ReadyGymColors.AccentText else ReadyGymColors.FaintText, fontWeight = FontWeight.ExtraBold) }
        }
    }
}

/**
 * A compact numeric cell. It keeps its own text so "12." can be typed on the way to "12.5";
 * [key] ties that text to one set, so moving between exercises does not leak values.
 */
@Composable
private fun NumberCell(label: String, key: String, value: String, decimal: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    var text by rememberSaveable(key) { mutableStateOf(value) }
    BasicTextField(
        text,
        { v -> val clean = v.filter { it.isDigit() || (decimal && it == '.') }.take(5); text = clean; onChange(clean) },
        modifier.padding(horizontal = 4.dp).clip(GymShapes.Xs).background(ReadyGymColors.Background)
            .heightIn(min = 40.dp).padding(vertical = 10.dp).semantics { contentDescription = label },
        singleLine = true,
        textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = ReadyGymColors.Text),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}

@Composable
private fun Tips(exercise: Exercise) {
    Panel {
        SectionHeader("动作要点", Modifier.padding(top = 0.dp))
        exercise.tips.forEach { Text("· $it", fontSize = 14.sp, lineHeight = 21.sp) }
        if (exercise.mistakes.isNotEmpty()) {
            Text("常见错误", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.Warning, modifier = Modifier.padding(top = 4.dp))
            exercise.mistakes.forEach { Text("· $it", fontSize = 13.sp, lineHeight = 20.sp, color = ReadyGymColors.MutedText) }
        }
    }
}

@Composable
private fun EndDialog(session: WorkoutSession, onFinish: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    val done = session.completedSets.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (done == session.sets.size) "完成训练" else "提前结束？") },
        text = {
            Text(if (done == 0) "还没有打勾的组。可以放弃本次训练，不会留下记录。"
            else "已完成 $done/${session.sets.size} 组。结束后记录这次训练，并请你反馈感受。")
        },
        confirmButton = {
            if (done > 0) TextButton(onClick = onFinish, modifier = Modifier.testTag("end-finish")) { Text("完成并记录") }
            else TextButton(onClick = onDismiss) { Text("继续训练") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard, modifier = Modifier.testTag("end-discard")) { Text("放弃本次", color = ReadyGymColors.Error) }
                if (done > 0) TextButton(onClick = onDismiss) { Text("继续") }
            }
        },
    )
}

internal fun shortSet(s: SetLog): String =
    (s.loadKg?.let { "${formatKg(it)}×" } ?: "") + (s.reps?.toString() ?: s.seconds?.let { "${it}秒" } ?: "—")

private fun clock(totalSeconds: Long): String = "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
