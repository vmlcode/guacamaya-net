package net.guacamaya.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import net.guacamaya.service.GuacamayaForegroundService

/**
 * Rearranca la malla tras reboot. Direct-boot aware: `LOCKED_BOOT_COMPLETED` llega antes del
 * primer unlock — el observer puede empezar a escuchar sin Keystore (no firma frames propios,
 * pero relay + incoming SOS ya funcionan). El broadcast de identidad se inicializa lazy cuando
 * el usuario desbloquea y el FGS re-carga `Identity`.
 *
 * Sólo actúa si el usuario dejó la malla activa (`last_mode != OFF` en prefs del servicio).
 */
class BootReceiver : BroadcastReceiver() {

    private val tag = "guacamaya.boot"

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> Unit
            else -> return
        }
        val prefs = context.getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_LAST_MODE, MODE_OFF) ?: MODE_OFF
        if (mode == MODE_OFF) {
            Log.i(tag, "boot received — last mode OFF, no auto-start")
            return
        }
        val action = when (mode) {
            MODE_SOS -> GuacamayaForegroundService.ACTION_START
            MODE_BOTH -> GuacamayaForegroundService.ACTION_HEARTBEAT_ON
            else -> GuacamayaForegroundService.ACTION_OBSERVE_ON
        }
        Log.i(tag, "boot received — restarting FGS mode=$mode action=$action")
        val svc = Intent(context, GuacamayaForegroundService::class.java).apply {
            this.action = action
            setPackage(context.packageName)
        }
        ContextCompat.startForegroundService(context, svc)
    }

    private companion object {
        const val SERVICE_PREFS = "guacamaya_service"
        const val KEY_LAST_MODE = "last_mode"
        const val MODE_OFF = "OFF"
        const val MODE_SOS = "SOS"
        const val MODE_BOTH = "BOTH"
    }
}
