package com.readygym.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.readygym.app.data.GymDatabase
import com.readygym.app.data.GymRepository
import com.readygym.core.workout.latestWorkoutOnDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Fires on the scheduled moment: posts the "time to train" notification (unless today's workout is
 * already logged — no nagging after the work is done) and re-arms the alarm for the next day.
 *
 * [goAsync] only extends the receiver's lifetime; it does NOT switch threads. All Room reads run
 * on [Dispatchers.IO], so a cold start, a WAL checkpoint or a migration cannot block the main
 * thread and cause an ANR.
 */
internal class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext
                val repo = GymRepository(GymDatabase.get(app))
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val history = repo.history.first()
                val plan = repo.activePlan.first()
                val trainedToday = latestWorkoutOnDate(history, today, zone) != null
                if (!trainedToday) postNotification(app, plan?.version)
                ReminderScheduler.reschedule(app)
            } finally {
                result.finish()
            }
        }
    }

    private fun postNotification(context: Context, planVersion: Int?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) return // no permission: stay silent, the alarm is still re-armed for next time
        ReminderScheduler.ensureChannel(context)
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (planVersion != null) "今天是你的训练日，计划 v$planVersion 已就绪，打开 App 开始今天的一练。"
        else "今天是你的训练日，打开 App 开始今天的一练。"
        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle("该练啦")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_FIRE = "com.readygym.app.reminder.FIRE"
        private const val NOTIFICATION_ID = 4001
    }
}

/** Alarms die on reboot and on app updates, so both events simply re-arm the next one. */
internal class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED -> {
                ReminderScheduler.ensureChannel(context)
                ReminderScheduler.reschedule(context)
            }
        }
    }
}
