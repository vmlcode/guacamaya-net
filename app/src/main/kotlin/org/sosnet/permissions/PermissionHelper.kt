package org.sosnet.permissions

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import org.sosnet.ble.BleCapabilities

object PermissionHelper {

    fun requiredPermissions(sdk: Int = Build.VERSION.SDK_INT): List<String> = buildList {
        if (sdk >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (sdk >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    fun missingPermissions(context: Context): List<String> =
        requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun hasAllPermissions(context: Context): Boolean = missingPermissions(context).isEmpty()

    fun isBluetoothEnabled(context: Context): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bm?.adapter?.isEnabled == true
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun canOperateBle(context: Context): Boolean {
        if (!hasAllPermissions(context)) return false
        if (!isBluetoothEnabled(context)) return false
        val status = BleCapabilities.probe(context)
        return status == BleCapabilities.Status.Ready ||
            status == BleCapabilities.Status.NoCodedPhy
    }

    fun blockingReason(context: Context): BlockingReason? {
        if (!hasAllPermissions(context)) return BlockingReason.MissingPermissions
        if (!isBluetoothEnabled(context)) return BlockingReason.BluetoothOff
        return when (BleCapabilities.probe(context)) {
            BleCapabilities.Status.BtOff -> BlockingReason.BluetoothOff
            BleCapabilities.Status.NoExtendedAdv -> BlockingReason.NoExtendedAdv
            BleCapabilities.Status.NoMultipleAdv -> BlockingReason.NoMultipleAdv
            BleCapabilities.Status.NoBluetooth -> BlockingReason.BluetoothOff
            BleCapabilities.Status.Ready, BleCapabilities.Status.NoCodedPhy -> null
        }
    }

    enum class BlockingReason {
        MissingPermissions,
        BluetoothOff,
        NoExtendedAdv,
        NoMultipleAdv,
    }
}
