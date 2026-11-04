package com.readygym.app

import android.content.Context

/** Only the reminder checkpoint lives here. Workout records and feedback remain in Room. */
class RecoveryCheckInStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("recovery_check_in", Context.MODE_PRIVATE)
    fun handledThroughId(): Long = prefs.getLong("handled_through_id", 0)
    fun markHandled(sessionId: Long) {
        prefs.edit().putLong("handled_through_id", maxOf(sessionId, handledThroughId())).apply()
    }
}
