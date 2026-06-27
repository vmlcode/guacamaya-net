package org.sosnet.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build

/**
 * Probes chip capabilities for BLE 5 extended advertising used by SOSNet.
 */
object BleCapabilities {

    enum class Status {
        Ready,
        BtOff,
        NoBluetooth,
        NoExtendedAdv,
        NoMultipleAdv,
        NoCodedPhy,
    }

    data class Capabilities(
        val status: Status,
        val extendedAdv: Boolean,
        val multipleAdv: Boolean,
        val codedPhy: Boolean,
    )

    fun probe(context: Context): Status = probeCapabilities(context).status

    fun probeCapabilities(context: Context): Capabilities {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: return Capabilities(
            status = Status.NoBluetooth,
            extendedAdv = false,
            multipleAdv = false,
            codedPhy = false,
        )
        if (!adapter.isEnabled) {
            return Capabilities(Status.BtOff, false, false, false)
        }
        val extended = adapter.isLeExtendedAdvertisingSupported
        val multiple = adapter.isMultipleAdvertisementSupported
        val coded = if (Build.VERSION.SDK_INT >= 26) {
            adapter.isLeCodedPhySupported
        } else {
            false
        }
        val status = when {
            !multiple -> Status.NoMultipleAdv
            !extended -> Status.NoExtendedAdv
            !coded -> Status.NoCodedPhy
            else -> Status.Ready
        }
        return Capabilities(status, extended, multiple, coded)
    }
}
