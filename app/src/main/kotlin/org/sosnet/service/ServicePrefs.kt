package org.sosnet.service

import android.content.Context

/** Persists broadcast/observe toggles across process death and boot. */
object ServicePrefs {
    private const val PREFS = "sosnet.service.prefs"
    private const val KEY_BROADCASTING = "broadcasting"
    private const val KEY_OBSERVING = "observing"

    fun save(context: Context, broadcasting: Boolean, observing: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BROADCASTING, broadcasting)
            .putBoolean(KEY_OBSERVING, observing)
            .apply()
    }

    fun load(context: Context): Pair<Boolean, Boolean> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_BROADCASTING, false) to p.getBoolean(KEY_OBSERVING, false)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
