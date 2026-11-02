package com.readygym.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reminder settings survive a store round trip; defaults are sensible for a first-time user. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun defaultsAreOffAtSevenPmOnMonWedFri() {
        val s = ReminderStore(context).load()
        assertFalse(s.enabled)
        assertEquals(19, s.hour)
        assertEquals(0, s.minute)
        assertEquals(setOf(1, 3, 5), s.days)
    }

    @Test fun roundTripsEnabledTimeAndDays() {
        val store = ReminderStore(context)
        val saved = ReminderSettings(enabled = true, hour = 7, minute = 30, days = setOf(2, 6, 7))
        store.save(saved)
        assertEquals(saved, store.load())
    }

    @Test fun daysKeepTheirOrderAcrossSaves() {
        val store = ReminderStore(context)
        store.save(ReminderSettings(enabled = true, days = setOf(7, 1, 4)))
        assertEquals(setOf(1, 4, 7), store.load().days)
    }
}
