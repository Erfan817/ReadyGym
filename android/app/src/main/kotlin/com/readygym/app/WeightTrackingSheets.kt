package com.readygym.app

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readygym.core.weight.WeightEntry
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** A target or dated measurement is edited locally; only the explicit save action commits it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeightValueSheet(
    title: String,
    initialKg: Double,
    isTarget: Boolean,
    initialDate: LocalDate = LocalDate.now(),
    hasExistingEntry: Boolean = false,
    saving: Boolean = false,
    error: String? = null,
    onDismiss: () -> Unit,
    onSave: (LocalDate, Double) -> Unit,
    onClearTarget: (() -> Unit)? = null,
) {
    val start = remember(initialKg) { initialKg.takeIf { it.isFinite() && it in 30.0..250.0 } ?: 70.0 }
    var input by rememberSaveable(isTarget, initialDate.toEpochDay(), initialKg) { mutableStateOf(weightInputNumber(start)) }
    var epochDay by rememberSaveable(isTarget, initialDate.toEpochDay()) { mutableStateOf(initialDate.toEpochDay()) }
    val date = LocalDate.ofEpochDay(epochDay)
    val today = LocalDate.now()
    val value = parseWeightInput(input)
    val validDate = isTarget || !date.isAfter(today)
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !saving })
    val maximumHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp
    BackHandler(saving) { }

    fun adjust(tenths: Int) {
        value?.let { input = weightInputNumber(((it * 10).roundToInt() + tenths).coerceIn(300, 2500) / 10.0) }
    }
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() }, sheetState = sheet,
        containerColor = ReadyGymColors.Background,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight).imePadding().testTag("weight-value-sheet")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = GymSpacing.Lg), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.testTag("weight-sheet-cancel")) {
                    Text("取消", color = ReadyGymColors.MutedText)
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = GymSpacing.Lg).testTag("weight-value-scroll"),
                verticalArrangement = Arrangement.spacedBy(GymSpacing.Md)) {
                Text(if (isTarget) "目标会以一条线显示在体重图表中，变化会根据是否朝目标靠近来显示。"
                    else "记录这次称重，慢慢看见自己的变化。记录保存在这台手机上。",
                    fontSize = 14.sp, lineHeight = 22.sp, color = ReadyGymColors.MutedText)
                if (!isTarget) TextButton(onClick = {
                    DatePickerDialog(context, { _, year, month, day ->
                        epochDay = LocalDate.of(year, month + 1, day).toEpochDay()
                    }, date.year, date.monthValue - 1, date.dayOfMonth).apply {
                        datePicker.maxDate = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.show()
                }, enabled = !saving && !hasExistingEntry, modifier = Modifier.fillMaxWidth().testTag("weight-date")) {
                    Text(weightSheetDate(date) + if (hasExistingEntry) "" else " · 更改日期",
                        color = if (hasExistingEntry) ReadyGymColors.MutedText else ReadyGymColors.AccentText, fontSize = 15.sp)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = GymSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
                    WeightRoundAction("−", "减少 0.1 千克", !saving && value != null && value > 30.0,
                        "weight-minus") { adjust(-1) }
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(input, { input = it.take(12) },
                            modifier = Modifier.weight(1f).heightIn(min = 72.dp)
                                .padding(vertical = 8.dp).testTag("weight-input")
                                .semantics { contentDescription = if (isTarget) "目标体重，单位千克" else "称重数值，单位千克" },
                            enabled = !saving, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            textStyle = TextStyle(fontSize = 44.sp, lineHeight = 54.sp, fontWeight = FontWeight.ExtraBold,
                                color = ReadyGymColors.Text, textAlign = TextAlign.Center))
                        Text("kg", fontSize = 20.sp, color = ReadyGymColors.MutedText)
                    }
                    WeightRoundAction("+", "增加 0.1 千克", !saving && value != null && value < 250.0,
                        "weight-plus") { adjust(1) }
                }
                Text(if (value == null) "请输入 30–250 kg，最多保留一位小数。" else "点数字可以直接输入体重",
                    fontSize = 12.sp, color = if (value == null) ReadyGymColors.Error else ReadyGymColors.MutedText,
                    modifier = Modifier.fillMaxWidth().testTag("weight-input-help"), textAlign = TextAlign.Center)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
                    listOf(-10 to "−1", -5 to "−0.5", 5 to "+0.5", 10 to "+1").forEach { (step, label) ->
                        Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(GymShapes.Md).background(ReadyGymColors.Surface)
                            .clickable(enabled = !saving && value != null, role = Role.Button) { adjust(step) }
                            .semantics { contentDescription = "${if (step > 0) "增加" else "减少"} ${kotlin.math.abs(step) / 10.0} 千克" }
                            .testTag("weight-step-$step"), contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 17.sp, color = ReadyGymColors.Text)
                        }
                    }
                }
                Slider(value = (value ?: start).toFloat(), onValueChange = { input = weightInputNumber((it * 10).roundToInt() / 10.0) },
                    valueRange = 30f..250f, enabled = !saving && value != null,
                    thumb = {
                        Box(Modifier.size(28.dp).shadow(4.dp, CircleShape)
                            .background(ReadyGymColors.Surface, CircleShape).testTag("weight-slider-thumb"))
                    },
                    track = { slider ->
                        val progress = ((slider.value - slider.valueRange.start) /
                            (slider.valueRange.endInclusive - slider.valueRange.start)).coerceIn(0f, 1f)
                        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                            val y = size.height / 2f
                            drawLine(ReadyGymColors.Sunken, Offset(0f, y), Offset(size.width, y),
                                strokeWidth = size.height, cap = StrokeCap.Round)
                            if (progress > 0f) drawLine(ReadyGymColors.Accent, Offset(0f, y), Offset(size.width * progress, y),
                                strokeWidth = size.height, cap = StrokeCap.Round)
                        }
                    }, modifier = Modifier.fillMaxWidth().testTag("weight-slider"))
                if (!isTarget) Text(if (hasExistingEntry && date == initialDate) "这一天已有记录，保存后会更新这条记录。"
                    else "同一天的称重会更新为这次的数值。",
                    fontSize = 12.sp, color = ReadyGymColors.MutedText)
                if (!validDate) Text("还不能记录未来的体重，请选择今天或之前的日期。",
                    fontSize = 13.sp, lineHeight = 19.sp, color = ReadyGymColors.Error)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = GymSpacing.Lg, vertical = GymSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
                error?.let { Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = ReadyGymColors.Error,
                    modifier = Modifier.testTag("weight-save-error").semantics { liveRegion = LiveRegionMode.Polite }) }
                Button(onClick = { value?.takeIf { validDate && !saving }?.let { onSave(date, it) } },
                    enabled = !saving && value != null && validDate,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("weight-save"), shape = GymShapes.Md,
                    colors = ButtonDefaults.buttonColors(containerColor = ReadyGymColors.Accent,
                        contentColor = ReadyGymColors.OnAccent, disabledContainerColor = ReadyGymColors.Sunken,
                        disabledContentColor = ReadyGymColors.MutedText)) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp),
                        strokeWidth = 2.dp, color = ReadyGymColors.MutedText)
                    Text(if (saving) "正在保存…" else if (isTarget) "保存目标" else "保存记录",
                        fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                if (isTarget && onClearTarget != null) TextButton(onClick = onClearTarget, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag("weight-clear-target")) {
                    Text("清除目标体重", color = ReadyGymColors.MutedText)
                }
            }
        }
    }
}

@Composable
private fun WeightRoundAction(label: String, description: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).background(ReadyGymColors.Surface)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }.testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 30.sp, fontWeight = FontWeight.Medium,
            color = if (enabled) ReadyGymColors.Text else ReadyGymColors.FaintText)
    }
}

/** Newest first; editing and deleting always identify the date rather than a list position. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeightHistorySheet(
    entries: List<WeightEntry>,
    onDismiss: () -> Unit,
    onEdit: (WeightEntry) -> Unit,
    onDelete: (WeightEntry) -> Unit,
    deletingDate: LocalDate? = null,
    error: String? = null,
) {
    var pendingDeleteDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { deletingDate == null })
    val maximumHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    BackHandler(deletingDate != null) { }
    ModalBottomSheet(onDismissRequest = { if (deletingDate == null) onDismiss() }, sheetState = sheet,
        containerColor = ReadyGymColors.Background) {
        Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight).padding(horizontal = GymSpacing.Lg)
            .padding(bottom = GymSpacing.Lg).testTag("weight-history-sheet"),
            verticalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("全部称重记录", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onDismiss, enabled = deletingDate == null, modifier = Modifier.testTag("weight-history-close")) {
                    Text("关闭", color = ReadyGymColors.MutedText)
                }
            }
            Text("点一条记录可以修改体重。", fontSize = 13.sp, color = ReadyGymColors.MutedText)
            error?.let { Text(it, fontSize = 13.sp, color = ReadyGymColors.Error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("weight-history-error")) }
            if (entries.isEmpty()) Text("还没有称重记录。先记录一次，看看之后的变化。",
                fontSize = 14.sp, lineHeight = 22.sp, color = ReadyGymColors.MutedText,
                modifier = Modifier.padding(vertical = GymSpacing.Lg))
            else LazyColumn(Modifier.weight(1f, fill = false).testTag("weight-history-list"),
                verticalArrangement = Arrangement.spacedBy(GymSpacing.Sm)) {
                items(entries.sortedByDescending { it.date }, key = { it.date.toEpochDay() }) { entry ->
                    Row(Modifier.fillMaxWidth().clip(GymShapes.Md).background(ReadyGymColors.Surface)
                        .padding(start = GymSpacing.Md), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).heightIn(min = 64.dp)
                            .clickable(enabled = deletingDate == null, role = Role.Button, onClickLabel = "编辑称重记录") { onEdit(entry) }
                            .testTag("weight-history-entry-${entry.date}").padding(vertical = GymSpacing.Sm),
                            verticalArrangement = Arrangement.Center) {
                            Text("${weightInputNumber(entry.kilograms)} kg", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(weightSheetDate(entry.date), fontSize = 12.sp, color = ReadyGymColors.MutedText)
                        }
                        TextButton(onClick = { pendingDeleteDay = entry.date.toEpochDay() }, enabled = deletingDate == null,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("weight-history-delete-${entry.date}")) {
                            Text(if (deletingDate == entry.date) "删除中…" else "删除", color = ReadyGymColors.Error)
                        }
                    }
                }
            }
        }
    }
    entries.firstOrNull { it.date.toEpochDay() == pendingDeleteDay }?.let { entry ->
        AlertDialog(onDismissRequest = { pendingDeleteDay = null },
            title = { Text("删除这次称重？") },
            text = { Text("${weightSheetDate(entry.date)}的 ${weightInputNumber(entry.kilograms)} kg 记录会被删除。") },
            confirmButton = { TextButton(onClick = { pendingDeleteDay = null; onDelete(entry) },
                modifier = Modifier.testTag("weight-delete-confirm")) { Text("删除", color = ReadyGymColors.Error) } },
            dismissButton = { TextButton(onClick = { pendingDeleteDay = null }, modifier = Modifier.testTag("weight-delete-cancel")) { Text("取消") } })
    }
}

internal fun parseWeightInput(input: String): Double? =
    input.takeIf { Regex("\\d{1,3}(\\.\\d)?").matches(it) }?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 30.0..250.0 }

private fun weightInputNumber(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

private fun weightSheetDate(date: LocalDate): String =
    "${date.year}年${date.monthValue}月${date.dayOfMonth}日 周${"一二三四五六日"[date.dayOfWeek.value - 1]}"
