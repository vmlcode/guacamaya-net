package net.guacamaya.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import net.guacamaya.ble.BleMeshRuntime
import net.guacamaya.service.GuacamayaForegroundService

/**
 * adb entry point that bypasses MainActivity/singleTop/MIUI task stacks:
 *   adb shell am broadcast -a net.guacamaya.action.OBSERVE_ON -n net.guacamaya/.adb.AdbCommandReceiver
 */
class AdbCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!action.startsWith("net.guacamaya.action.")) return
        Log.i(TAG, "onReceive action=$action")
        val svc = Intent(context, GuacamayaForegroundService::class.java).apply { this.action = action }
        ContextCompat.startForegroundService(context, svc)
        when (action) {
            GuacamayaForegroundService.ACTION_OBSERVE_ON,
            GuacamayaForegroundService.ACTION_START,
            -> {
                BleMeshRuntime.ensureObserving(context)
                GuacamayaForegroundService.kickObserve(context)
            }
            GuacamayaForegroundService.ACTION_OBSERVE_OFF -> {
                BleMeshRuntime.stopObserving()
            }
        }
    }

    companion object {
        private const val TAG = "guacamaya.adb"
    }
}
