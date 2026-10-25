package com.readygym.app

import android.content.Context

/**
 * Remembers that the user accepted the AI data disclosure, so it is shown once per install rather
 * than before every chat. Bump [VERSION] when the disclosure text changes materially to ask again.
 */
internal class AiConsentStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("readygym.consent", Context.MODE_PRIVATE)

    fun accepted(): Boolean = prefs.getInt(KEY, 0) >= VERSION

    fun accept() { prefs.edit().putInt(KEY, VERSION).apply() }

    private companion object {
        const val KEY = "ai_disclosure_version"
        const val VERSION = 1
    }
}
