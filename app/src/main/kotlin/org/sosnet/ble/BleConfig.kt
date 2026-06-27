package org.sosnet.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import java.util.UUID

/**
 * BLE constants + parameters. See docs/payload-binary-layout.md.
 */
object BleConfig {

    val SERVICE_UUID: UUID = UUID.fromString("8d3d0001-2a1b-4c8e-9c0f-1234567890ab")
    val SERVICE_PARCEL_UUID: ParcelUuid = ParcelUuid(SERVICE_UUID)

    const val SERVICE_DATA_SIZE = 22 + 32 + 64

    fun advertisingParameters(codedPhySupported: Boolean): AdvertisingSetParameters {
        val secondaryPhy = if (codedPhySupported) {
            BluetoothDevice.PHY_LE_CODED
        } else {
            BluetoothDevice.PHY_LE_1M
        }
        return AdvertisingSetParameters.Builder()
            .setConnectable(false)
            .setScannable(false)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
            .setSecondaryPhy(secondaryPhy)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()
    }

    fun scanSettings(foreground: Boolean): ScanSettings {
        val mode = if (foreground) {
            ScanSettings.SCAN_MODE_LOW_LATENCY
        } else {
            ScanSettings.SCAN_MODE_BALANCED
        }
        return ScanSettings.Builder()
            .setScanMode(mode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()
    }
}
