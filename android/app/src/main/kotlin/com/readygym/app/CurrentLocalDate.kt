package com.readygym.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal data class LocalTrainingDate(val today: LocalDate, val zone: ZoneId)

private fun localTrainingDate(now: Long): LocalTrainingDate {
    val zone = ZoneId.systemDefault()
    return LocalTrainingDate(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), zone)
}

/** Refresh on foregrounding, clock/timezone changes, and midnight while the app stays open. */
@Composable
internal fun rememberCurrentLocalDate(clock: () -> Long = System::currentTimeMillis): LocalTrainingDate {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentClock by rememberUpdatedState(clock)
    var current by remember { mutableStateOf(localTrainingDate(clock())) }

    DisposableEffect(context, lifecycle) {
        val refresh = { current = localTrainingDate(currentClock()) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh()
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val now = currentClock()
                current = localTrainingDate(now)
                val midnight = current.today.plusDays(1).atStartOfDay(current.zone).toInstant().toEpochMilli()
                delay((midnight - now).coerceIn(1L, 60_000L))
            }
        }
    }
    return current
}
