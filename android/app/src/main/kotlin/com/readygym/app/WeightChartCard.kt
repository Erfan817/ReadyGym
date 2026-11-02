package com.readygym.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.weight.WeightEntry
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Local measurements only: profile weight is a labelled starting value, never a chart sample. */
@Composable
internal fun WeightChartCard(
    entries: List<WeightEntry>,
    targetKg: Double?,
    profileWeightKg: Double?,
    onRecord: () -> Unit,
    onTarget: () -> Unit,
    onHistory: () -> Unit,
) {
    val ordered = remember(entries) { entries.sortedBy { it.date } }
    val recent = remember(ordered) { ordered.takeLast(30) }
    val latest = ordered.lastOrNull()
    val current = latest?.kilograms ?: profileWeightKg
    val change = if (ordered.size >= 2) weightChange(ordered[ordered.lastIndex - 1].kilograms,
        requireNotNull(latest).kilograms, targetKg) else null

    Panel(Modifier.testTag("weight-card")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("体重", fontSize = 15.sp, color = ReadyGymColors.MutedText, modifier = Modifier.weight(1f))
            WeightCardAction(targetKg?.let { "◎ ${chartWeight(it)}" } ?: "◎ 设目标", "weight-target",
                ReadyGymColors.Warning, onTarget)
            WeightCardAction("＋ 记录", "weight-record", ReadyGymColors.AccentText, onRecord)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text(current?.let(::chartWeight) ?: "—", fontSize = 40.sp, lineHeight = 45.sp,
                    fontWeight = FontWeight.ExtraBold, color = ReadyGymColors.Text,
                    modifier = Modifier.testTag("weight-latest"))
                Text(" kg", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    color = ReadyGymColors.MutedText, modifier = Modifier.padding(bottom = 5.dp))
            }
            Text(latest?.date?.let(::weightDateLabel) ?: if (current != null) "档案填写值" else "还没有记录",
                fontSize = 12.sp, color = ReadyGymColors.MutedText,
                modifier = Modifier.padding(bottom = 6.dp).testTag("weight-latest-date"))
        }
        Text(weightGoalDescription(latest?.kilograms, targetKg), fontSize = 13.sp, lineHeight = 20.sp,
            color = if (targetKg == null) ReadyGymColors.MutedText else ReadyGymColors.Warning,
            modifier = Modifier.testTag("weight-goal-summary"))
        if (change != null) Text(change.text, fontSize = 12.sp, lineHeight = 18.sp,
            color = when (change.tone) {
                WeightChangeTone.CLOSER -> ReadyGymColors.AccentText
                WeightChangeTone.FARTHER -> ReadyGymColors.Error
                WeightChangeTone.NEUTRAL -> ReadyGymColors.MutedText
            }, modifier = Modifier.testTag("weight-change"))

        if (recent.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(150.dp).clip(GymShapes.Md)
                .background(ReadyGymColors.Background).testTag("weight-chart-empty"),
                contentAlignment = Alignment.Center) {
                Column(Modifier.padding(GymSpacing.Lg), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
                    Text("从第一次称重开始", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = ReadyGymColors.Text)
                    Text("点「＋ 记录」记下体重，之后就能看到变化。",
                        fontSize = 13.sp, lineHeight = 20.sp, color = ReadyGymColors.MutedText)
                }
            }
        } else {
            WeightLineChart(recent, targetKg)
            Text(if (ordered.size > 30) "显示最近 30 次称重，点图查看某次读数" else
                "${recent.size} 次称重记录 · 点图查看读数", fontSize = 11.sp, color = ReadyGymColors.MutedText)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            WeightCardAction("全部称重记录  ›", "weight-history", ReadyGymColors.AccentText, onHistory)
        }
    }
}

@Composable
private fun WeightCardAction(label: String, tag: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clip(GymShapes.Sm)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp).testTag(tag),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun WeightLineChart(entries: List<WeightEntry>, targetKg: Double?) {
    val geometry = remember(entries, targetKg) { weightChartGeometry(entries, targetKg) }
    var selectedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedIndex = entries.indexOfFirst { it.date.toEpochDay() == selectedDay }
        .takeIf { it >= 0 } ?: entries.lastIndex
    val selected = entries[selectedIndex]
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = ReadyGymColors.MutedText)
    val ticks = remember(geometry) { geometry.ticks() }
    val labels = ticks.map { measurer.measure(AnnotatedString(chartWeight(it)), style = labelStyle) }
    val density = LocalDensity.current
    val labelWidth = labels.maxOf { it.size.width }.toFloat() + with(density) { 8.dp.toPx() }
    val rightMargin = with(density) { 8.dp.toPx() }
    val topMargin = with(density) { 52.dp.toPx() + 20.sp.toPx() }
    val bottomMargin = with(density) { 28.dp.toPx() }
    val selectedDescription = "${weightDateLabel(selected.date)}，${chartWeight(selected.kilograms)}公斤"

    Canvas(Modifier.fillMaxWidth().height(238.dp).testTag("weight-chart")
        .pointerInput(entries, geometry, labelWidth, topMargin, bottomMargin) {
            detectTapGestures { tap ->
                val plotWidth = (size.width - labelWidth - rightMargin).coerceAtLeast(1f)
                val fraction = ((tap.x - labelWidth) / plotWidth).coerceIn(0f, 1f)
                val nearest = geometry.points.indices.minByOrNull { abs(geometry.points[it].x - fraction) }
                nearest?.let { selectedDay = entries[it].date.toEpochDay() }
            }
        }.semantics {
            contentDescription = "体重折线图，${entries.size}次称重。当前查看$selectedDescription"
            customActions = buildList {
                if (selectedIndex > 0) add(CustomAccessibilityAction("上一条称重记录") {
                    selectedDay = entries[selectedIndex - 1].date.toEpochDay(); true
                })
                if (selectedIndex < entries.lastIndex) add(CustomAccessibilityAction("下一条称重记录") {
                    selectedDay = entries[selectedIndex + 1].date.toEpochDay(); true
                })
            }
        }.focusable()) {
        val left = labelWidth
        val right = (size.width - rightMargin).coerceAtLeast(left + 1f)
        val top = topMargin
        val bottom = (size.height - bottomMargin).coerceAtLeast(top + 1f)
        val plotWidth = right - left
        val plotHeight = bottom - top
        fun y(value: Double) = bottom - geometry.yFraction(value) * plotHeight
        fun point(index: Int) = geometry.points[index].let { Offset(left + it.x * plotWidth, bottom - it.y * plotHeight) }
        val gridDash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))

        ticks.forEachIndexed { index, value ->
            val axisY = y(value)
            drawLine(ReadyGymColors.Outline.copy(alpha = 0.3f), Offset(left, axisY), Offset(right, axisY),
                strokeWidth = 1.dp.toPx(), pathEffect = gridDash)
            drawText(labels[index], topLeft = Offset(0f, axisY - labels[index].size.height / 2f))
        }
        if (entries.size > 1) {
            val area = Path().apply {
                moveTo(point(0).x, bottom)
                entries.indices.forEach { lineTo(point(it).x, point(it).y) }
                lineTo(point(entries.lastIndex).x, bottom)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(ReadyGymColors.Accent.copy(alpha = 0.24f),
                ReadyGymColors.Accent.copy(alpha = 0.01f)), startY = top, endY = bottom))
            val line = Path().apply {
                moveTo(point(0).x, point(0).y)
                entries.indices.drop(1).forEach { lineTo(point(it).x, point(it).y) }
            }
            drawPath(line, ReadyGymColors.Accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        targetKg?.let { target ->
            val goalY = y(target)
            drawLine(Color(0xFFE0B21B), Offset(left, goalY), Offset(right, goalY), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            val label = measurer.measure(AnnotatedString("目标 ${chartWeight(target)}"),
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.Warning))
            val labelLeft = (right - label.size.width - 2.dp.toPx()).coerceAtLeast(left)
            val labelTop = (goalY - label.size.height - 3.dp.toPx()).coerceAtLeast(top - label.size.height)
            drawRoundRect(ReadyGymColors.Surface.copy(alpha = 0.94f), Offset(labelLeft - 3.dp.toPx(), labelTop),
                Size(label.size.width + 6.dp.toPx(), label.size.height.toFloat()), CornerRadius(3.dp.toPx()))
            drawText(label, topLeft = Offset(labelLeft, labelTop))
        }
        val selectedPoint = point(selectedIndex)
        drawLine(ReadyGymColors.Outline.copy(alpha = 0.65f), Offset(selectedPoint.x, top - 4.dp.toPx()),
            Offset(selectedPoint.x, bottom), 1.dp.toPx(), pathEffect = gridDash)
        entries.indices.forEach { index ->
            drawCircle(ReadyGymColors.Surface, if (index == selectedIndex) 6.dp.toPx() else 3.5.dp.toPx(), point(index))
            drawCircle(ReadyGymColors.Accent, if (index == selectedIndex) 4.dp.toPx() else 2.dp.toPx(), point(index))
        }

        val tip = measurer.measure(AnnotatedString("${weightDateLabel(selected.date)} · ${chartWeight(selected.kilograms)} kg"),
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ReadyGymColors.Text),
            constraints = Constraints(maxWidth = (size.width - 24.dp.toPx()).toInt().coerceAtLeast(1)))
        val tipWidth = (tip.size.width + 16.dp.toPx()).coerceAtMost(size.width)
        val tipLeft = (selectedPoint.x - tipWidth / 2).coerceIn(0f, (size.width - tipWidth).coerceAtLeast(0f))
        val tipTop = 4.dp.toPx()
        drawRoundRect(ReadyGymColors.Sunken, Offset(tipLeft, tipTop), Size(tipWidth, tip.size.height + 12.dp.toPx()),
            CornerRadius(10.dp.toPx()))
        drawText(tip, topLeft = Offset(tipLeft + 8.dp.toPx(), tipTop + 6.dp.toPx()))

        val first = entries.first().date
        val last = entries.last().date
        val firstLabel = measurer.measure(AnnotatedString(chartAxisDate(first, first.year != last.year)), style = labelStyle)
        if (entries.size == 1) {
            drawText(firstLabel, topLeft = Offset(selectedPoint.x - firstLabel.size.width / 2f, bottom + 8.dp.toPx()))
        } else {
            drawText(firstLabel, topLeft = Offset(left, bottom + 8.dp.toPx()))
            val lastLabel = measurer.measure(AnnotatedString(chartAxisDate(last, first.year != last.year)), style = labelStyle)
            drawText(lastLabel, topLeft = Offset((right - lastLabel.size.width).coerceAtLeast(left), bottom + 8.dp.toPx()))
        }
    }
}

internal data class WeightChartPoint(val x: Float, val y: Float)

internal data class WeightChartGeometry(
    val points: List<WeightChartPoint>, val lowerKg: Double, val upperKg: Double, val tickKg: Double,
) {
    fun yFraction(kilograms: Double): Float = ((kilograms - lowerKg) / (upperKg - lowerKg)).toFloat().coerceIn(0f, 1f)
    fun ticks(): List<Double> = (0..((upperKg - lowerKg) / tickKg).toInt()).map { lowerKg + it * tickKg }
}

/** Date distance is retained: ten unrecorded days do not become a fake daily series. */
internal fun weightChartGeometry(entries: List<WeightEntry>, targetKg: Double?): WeightChartGeometry {
    require(entries.isNotEmpty())
    val values = entries.map { it.kilograms } + listOfNotNull(targetKg)
    require(values.all { it.isFinite() })
    val minimum = values.min()
    val maximum = values.max()
    val span = (maximum - minimum).coerceAtLeast(1.0)
    val rawStep = span / 3.0
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val fraction = rawStep / magnitude
    val step = when { fraction <= 1 -> 1.0; fraction <= 2 -> 2.0; fraction <= 5 -> 5.0; else -> 10.0 } * magnitude
    val padding = span * 0.12
    val lower = floor((minimum - padding) / step) * step
    val upper = ceil((maximum + padding) / step) * step
    val firstDay = entries.first().date.toEpochDay()
    val lastDay = entries.last().date.toEpochDay()
    val daySpan = (lastDay - firstDay).coerceAtLeast(1)
    val points = entries.map {
        WeightChartPoint(if (entries.size == 1 || firstDay == lastDay) 0.5f else
            ((it.date.toEpochDay() - firstDay).toDouble() / daySpan).toFloat().coerceIn(0f, 1f),
            ((it.kilograms - lower) / (upper - lower)).toFloat().coerceIn(0f, 1f))
    }
    return WeightChartGeometry(points, lower, upper, step)
}

internal enum class WeightChangeTone { CLOSER, FARTHER, NEUTRAL }
internal data class WeightChange(val text: String, val tone: WeightChangeTone)

internal fun weightChange(previousKg: Double, currentKg: Double, targetKg: Double?): WeightChange {
    if (targetKg == null) {
        val delta = currentKg - previousKg
        return WeightChange(if (abs(delta) < 0.05) "与上次持平" else
            "较上次 ${if (delta > 0) "+" else "−"}${chartWeight(abs(delta))} kg", WeightChangeTone.NEUTRAL)
    }
    val distanceChange = abs(currentKg - targetKg) - abs(previousKg - targetKg)
    return when {
        distanceChange < -0.05 -> WeightChange("比上次更接近目标 ${chartWeight(abs(distanceChange))} kg", WeightChangeTone.CLOSER)
        distanceChange > 0.05 -> WeightChange("比上次离目标远 ${chartWeight(distanceChange)} kg", WeightChangeTone.FARTHER)
        else -> WeightChange("与上次离目标的距离相同", WeightChangeTone.NEUTRAL)
    }
}

internal fun weightGoalDescription(currentKg: Double?, targetKg: Double?): String = when {
    targetKg == null -> "设置目标后，图中会显示目标线。"
    currentKg == null -> "◎ 目标 ${chartWeight(targetKg)} kg · 先记录一次体重"
    abs(currentKg - targetKg) < 0.05 -> "◎ 已到设定目标 ${chartWeight(targetKg)} kg"
    currentKg > targetKg -> "◎ 目标 ${chartWeight(targetKg)} kg · 还需减重 ${chartWeight(currentKg - targetKg)} kg"
    else -> "◎ 目标 ${chartWeight(targetKg)} kg · 还需增重 ${chartWeight(targetKg - currentKg)} kg"
}

private fun chartWeight(value: Double): String = if (abs(value - value.toInt()) < 0.00001) value.toInt().toString()
    else String.format(Locale.ROOT, "%.1f", value)

private fun weightDateLabel(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日周${"一二三四五六日"[date.dayOfWeek.value - 1]}"
private fun chartAxisDate(date: LocalDate, withYear: Boolean): String =
    if (withYear) "${date.year}/${date.monthValue}/${date.dayOfMonth}" else "${date.monthValue}/${date.dayOfMonth}"
