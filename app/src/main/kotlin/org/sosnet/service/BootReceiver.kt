package org.sosnet.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.sosnet.permissions.PermissionHelper

/**
 * Restores mesh listening after device reboot if the user had it enabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val (broadcasting, observing) = ServicePrefs.load(context)
        if (!broadcasting && !observing) return
        if (!PermissionHelper.hasAllPermissions(context)) return
        if (!PermissionHelper.isBluetoothEnabled(context)) return

        val serviceIntent = Intent(context, SosForegroundService::class.java).apply {
            action = SosForegroundService.ACTION_RESTORE
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
