package net.guacamaya.time

import android.content.Context
import android.location.Location

/**
 * Small, persisted GPS clock correction. It never changes wall-clock settings;
 * callers use [now] for protocol timestamps and record whether time is trusted.
 */
object MeshClock {
    private const val PREFS = "guacamaya_mesh_clock"
    private const val OFFSET_MS = "offset_ms"
    private const val TRUSTED_AT_MS = "trusted_at_ms"
    private const val MAX_GPS_AGE_MS = 24 * 60 * 60_000L

    fun updateFromGps(context: Context, location: Location) {
        if (location.time <= 0L) return
        val offset = location.time - System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(OFFSET_MS, offset).putLong(TRUSTED_AT_MS, System.currentTimeMillis()).apply()
    }

    fun now(context: Context): Long {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return System.currentTimeMillis() + p.getLong(OFFSET_MS, 0L)
    }

    fun isTrusted(context: Context): Boolean {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(TRUSTED_AT_MS, 0L)
        return at > 0 && System.currentTimeMillis() - at <= MAX_GPS_AGE_MS
    }
}
