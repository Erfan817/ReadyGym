package com.readygym.app

import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.Instant
import java.util.TimeZone
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CurrentLocalDateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val originalZone = TimeZone.getDefault()

    @After fun restoreTimezone() { TimeZone.setDefault(originalZone) }

    @Test fun dateBroadcastRefreshesAnOpenScreenAtMidnight() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        var now = Instant.parse("2026-10-28T15:59:59Z").toEpochMilli()
        compose.setContent { Text(rememberCurrentLocalDate { now }.today.toString()) }
        compose.onNodeWithText("2026-10-28").assertExists()
        now += 2_000L
        compose.activity.sendBroadcast(Intent(Intent.ACTION_DATE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        compose.onNodeWithText("2026-10-29").assertExists()
    }

    @Test fun returningToTheForegroundRefreshesDateWithoutRelaunching() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        var now = Instant.parse("2026-10-28T15:59:59Z").toEpochMilli()
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Text(rememberCurrentLocalDate { now }.today.toString())
            }
        }
        compose.onNodeWithText("2026-10-28").assertExists()
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        now += 2_000L
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("2026-10-29").assertExists()
    }

    @Test fun timezoneBroadcastRefreshesTheLocalDateAndZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val now = Instant.parse("2026-10-28T18:00:00Z").toEpochMilli()
        compose.setContent { Text(rememberCurrentLocalDate { now }.let { "${it.today} ${it.zone.id}" }) }
        compose.onNodeWithText("2026-10-29 Asia/Shanghai").assertExists()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        compose.activity.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        compose.onNodeWithText("2026-10-28 UTC").assertExists()
    }
}
