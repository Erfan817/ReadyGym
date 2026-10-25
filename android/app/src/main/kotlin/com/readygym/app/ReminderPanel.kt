package com.readygym.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.readygym.core.profile.weekdayLabel
import java.time.Instant
import java.time.ZoneId

/**
 * 训练提醒 settings, shown on 我的.
 *
 * Everything the user sets here is local: the rule goes into SharedPreferences, the next firing
 * into the AlarmManager, the nudge into a notification. Enabling it on Android 13+ asks for the
 * notification permission first; if that is refused the panel says so instead of failing silently.
 */
@Composable
internal fun ReminderPanel() {
    val context = LocalContext.current
    val store = remember(context) { ReminderStore(context) }
    var settings by remember(context) { mutableStateOf(store.load()) }
    var permissionGranted by remember(context) { mutableStateOf(notificationPermissionGranted(context)) }
    var showTimePicker by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }

    fun update(new: ReminderSettings) {
        settings = new
        store.save(new)
        ReminderScheduler.reschedule(context)
    }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(ReadyGymColors.AccentWash), contentAlignment = Alignment.Center) {
                Icon(GymIcons.Bell, null, Modifier.size(20.dp), tint = ReadyGymColors.AccentText)
            }
            Spacer(Modifier.width(GymSpacing.Md))
            SectionHeader("训练提醒", Modifier.padding(top = 0.dp).weight(1f))
        }
        Text("训练日到了就发通知提醒你。规则只存在这台手机上，随时可以关。", fontSize = 13.sp, lineHeight = 19.sp,
            color = ReadyGymColors.MutedText)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (settings.enabled) "已开启" else "已关闭", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(if (settings.enabled) "到点会在这里的通知栏出现" else "打开后到点提醒你训练", fontSize = 12.sp, color = ReadyGymColors.MutedText)
            }
            Switch(
                checked = settings.enabled,
                onCheckedChange = { on ->
                    if (on && Build.VERSION.SDK_INT >= 33 && !permissionGranted) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    update(settings.copy(enabled = on))
                },
                modifier = Modifier.testTag("reminder-toggle"),
            )
        }

        if (settings.enabled) {
            if (!permissionGranted) {
                Notice("通知权限还没开，提醒不会响。可以再试一次授权，或到系统设置里允许 Ready Gym 通知。", warning = true)
                SecondaryButton("再试一次授权", {
                    if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }, Modifier.testTag("reminder-permission-retry"))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("提醒时间", fontSize = 13.sp, color = ReadyGymColors.MutedText, modifier = Modifier.weight(1f))
                Text("%02d:%02d".format(settings.hour, settings.minute), fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("reminder-time"))
                Spacer(Modifier.width(GymSpacing.Sm))
                InlineAction("修改", { showTimePicker = true })
            }
            SectionHeader("哪几天提醒")
            ChoiceRow((1..7).toList(), label = { weekdayLabel(it) }, isSelected = { it in settings.days },
                onPick = { day ->
                    val days = if (day in settings.days) settings.days - day else settings.days + day
                    update(settings.copy(days = days))
                }, multi = true)

            val next = remember(settings) {
                ReminderPlanner.nextTriggerMillis(System.currentTimeMillis(), ZoneId.systemDefault(), settings)
            }
            if (next == null) {
                Notice("先勾选至少一个提醒日，闹钟才会排上。", warning = true)
            } else {
                val fire = Instant.ofEpochMilli(next).atZone(ZoneId.systemDefault())
                Text("下一次提醒：${weekdayLabel(fire.dayOfWeek.value)} %02d:%02d（%d/%d）"
                    .format(fire.hour, fire.minute, fire.monthValue, fire.dayOfMonth),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ReadyGymColors.AccentText,
                    modifier = Modifier.testTag("reminder-next"))
            }
        }
    }

    if (showTimePicker) {
        ReminderTimePicker(settings.hour, settings.minute,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                showTimePicker = false
                update(settings.copy(hour = hour, minute = minute))
            })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimePicker(hour: Int, minute: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(hour, minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }, modifier = Modifier.testTag("reminder-time-confirm")) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("几点提醒你？") },
        text = { TimePicker(state) },
    )
}

private fun notificationPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
