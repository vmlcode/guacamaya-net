package org.sosnet.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSetParameters
import android.os.ParcelUuid
import java.util.UUID

/**
 * BLE constants + parameters. See docs/payload-binary-layout.md.
 *
 * The 22 B payload + 32 B pubkey + 64 B signature ride inside BLE service data
 * keyed by the 128-bit SOSNet Service UUID. Observer filters on this UUID in
 * software (extended service-data is often omitted when using hardware filters).
 *
 * The UUID is a placeholder — change before production. Same value must be used
 * by Observer when matching ScanRecord.serviceUuids.
 */
object BleConfig {

    /** 128-bit Service UUID. */
    val SERVICE_UUID: UUID = UUID.fromString("8d3d0001-2a1b-4c8e-9c0f-1234567890ab")

    val SERVICE_PARCEL_UUID: ParcelUuid = ParcelUuid(SERVICE_UUID)

    /** Total service-data length: 22 B payload + 32 B pubkey + 64 B Ed25519 signature = 118 B. */
    const val SERVICE_DATA_SIZE = 22 + 32 + 64

    /**
     * AdvertisingSetParameters tuned for extended ADV (BLE 5).
     *
     * - Connectable false: we never pair.
     * - Primary PHY 1M (discovery) + secondary PHY CODED (data). The asymmetry
     *   flips the controller into extended-advertising mode (lifting the 31 B
     *   legacy cap) and CODED gives the broadest receiver compatibility —
     *   some controllers enable 1M+Coded by default and omit 2M. 1M/1M was
     *   rejected with status=4 on the broadcaster side; 1M/2M was broadcast
     *   clean but invisible to those receivers.
     * - Interval LOW (≈ 1 s broadcast) — fast enough for demo, gentle on battery.
     * - TX power HIGH for max range in the demo.
     */
    val parameters: AdvertisingSetParameters = AdvertisingSetParameters.Builder()
        .setConnectable(false)
        .setScannable(false)
        .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
        .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
        .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
        .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
        .build()
}
