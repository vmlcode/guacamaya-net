package net.guacamaya

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import net.guacamaya.service.GuacamayaForegroundService

/**
 * Safety net: si el proceso murió (memory pressure, OEM kill) y el usuario abre la app, o si
 * START_STICKY rearrancó el servicio sin pasar por un Intent explícito, este onCreate garantiza
 * que la malla vueva al modo persistido. Idempotente — si el FGS ya está corriendo, Android
 * entrega el Intent al onStartCommand existente.
 */
class GuacaApplication : Application() {

    private val tag = "guacamaya.app"

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
        val want = prefs.getBoolean(KEY_WANT_OBSERVE, false)
        val mode = prefs.getString(KEY_LAST_MODE, MODE_OFF) ?: MODE_OFF
        if (!want || mode == MODE_OFF) {
            Log.i(tag, "onCreate — no active mesh (want=$want mode=$mode)")
            return
        }
        val action = when (mode) {
            MODE_SOS -> GuacamayaForegroundService.ACTION_START
            MODE_BOTH -> GuacamayaForegroundService.ACTION_HEARTBEAT_ON
            else -> GuacamayaForegroundService.ACTION_OBSERVE_ON
        }
        Log.i(tag, "onCreate — restoring mesh mode=$mode action=$action")
        val svc = Intent(this, GuacamayaForegroundService::class.java).apply {
            this.action = action
            setPackage(packageName)
        }
        ContextCompat.startForegroundService(this, svc)
    }

    private companion object {
        const val SERVICE_PREFS = "guacamaya_service"
        const val KEY_WANT_OBSERVE = "want_observe"
        const val KEY_LAST_MODE = "last_mode"
        const val MODE_OFF = "OFF"
        const val MODE_SOS = "SOS"
        const val MODE_BOTH = "BOTH"
    }
}
