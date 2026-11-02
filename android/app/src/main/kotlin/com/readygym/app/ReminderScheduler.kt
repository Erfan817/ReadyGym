package com.readygym.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Owns the single inexact alarm that backs the workout reminder.
 *
 * Only the *next* firing is ever scheduled; when it goes off, [ReminderReceiver] posts the
 * notification and calls back here for the one after that. `setAndAllowWhileIdle` needs no
 * exact-alarm permission, and a reminder that lands at 19:03 instead of 19:00 is fine.
 */
internal object ReminderScheduler {
    const val CHANNEL_ID = "workout_reminders"
    private const val REQUEST_CODE = 4001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "训练提醒",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "在训练日的固定时间提醒你去训练"
            })
    }

    /** Re-reads the settings and moves the alarm to the next firing; safe to call any time. */
    fun reschedule(context: Context) {
        val settings = ReminderStore(context).load()
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        alarmManager.cancel(pending)
        val next = ReminderPlanner.nextTriggerMillis(System.currentTimeMillis(), java.time.ZoneId.systemDefault(), settings)
            ?: return
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
    }

    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
