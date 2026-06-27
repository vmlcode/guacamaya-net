package org.sosnet.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * BLE GAP Broadcaster. Radiates a single 118-byte service-data frame via BLE 5
 * Extended Advertising on channels 37/38/39. No pairing, no connectable bit, no
 * scan-response. See docs/protocol-flows.md Flow 1.
 *
 * Payload rotation is the caller's responsibility — call [swap] with a fresh
 * (payload, signature) pair to change what's on the air. The rotation cadence
 * itself is owned by `mesh.FloodRouter` (origin) or `service.SosForegroundService`.
 */
class Broadcaster private constructor(
    private val advertiser: BluetoothLeAdvertiser,
) {
    private val tag = "sosnet.ble.Broadcaster"

    private val activeSet = AtomicReference<AdvertisingSet?>(null)
    @Volatile private var currentServiceData: ByteArray = ByteArray(BleConfig.SERVICE_DATA_SIZE)
    @Volatile private var activeCallback: AdvertisingSetCallback? = null

    /** True if advertising is currently on. */
    val isAdvertising: Boolean get() = activeSet.get() != null

    /**
     * Start advertising with the given hop TTL + 22 B payload + 32 B pubkey + 64 B
     * signature, concatenated into a single service-data blob keyed by the SOSNet
     * Service UUID. [ttl] is the unsigned hop budget (0..15) written at offset 0.
     */
    fun start(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray, ttl: Int = BleConfig.ORIGIN_HOP_TTL) {
        val combined = frame(payload22, pub32, sig64, ttl)
        currentServiceData = combined

        val data = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .addServiceUuid(BleConfig.SERVICE_PARCEL_UUID)
            .addServiceData(BleConfig.SERVICE_PARCEL_UUID, combined)
            .build()

        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS) {
                    activeSet.set(set)
                    Log.i(tag, "advertising started, txPower=$txPower dBm, frame=${combined.size} B")
                } else {
                    Log.e(tag, "advertising start failed status=$status")
                }
            }

            override fun onAdvertisingEnabled(set: AdvertisingSet?, enable: Boolean, status: Int) {
                Log.d(tag, "advertising enabled=$enable status=$status")
            }

            override fun onAdvertisingDataSet(set: AdvertisingSet?, status: Int) {
                Log.d(tag, "advertising data set status=$status")
            }

            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                activeSet.set(null)
                activeCallback = null
                Log.i(tag, "advertising stopped")
            }
        }
        activeCallback = callback

        advertiser.startAdvertisingSet(BleConfig.parameters, data, null, null, null, callback)
    }

    /**
     * Swap the active frame without re-triggering startAdvertisingSet.
     * Uses AdvertisingSet#setAdvertisingData to update in place — faster than
     * stop+start and avoids the controller re-arming delay.
     */
    fun swap(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray, ttl: Int = BleConfig.ORIGIN_HOP_TTL) {
        val set = activeSet.get() ?: run {
            Log.w(tag, "swap called while not advertising; calling start() instead")
            start(payload22, pub32, sig64, ttl)
            return
        }
        val combined = frame(payload22, pub32, sig64, ttl)
        currentServiceData = combined

        val data = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .addServiceUuid(BleConfig.SERVICE_PARCEL_UUID)
            .addServiceData(BleConfig.SERVICE_PARCEL_UUID, combined)
            .build()
        set.setAdvertisingData(data)
    }

    /** Assemble the 119-byte service-data blob: [ttl][payload][pubkey][sig]. */
    private fun frame(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray, ttl: Int): ByteArray {
        require(payload22.size == 22) { "payload must be 22 bytes, got ${payload22.size}" }
        require(pub32.size == 32) { "pubkey must be 32 bytes, got ${pub32.size}" }
        require(sig64.size == 64) { "signature must be 64 bytes, got ${sig64.size}" }
        require(ttl in 0..255) { "ttl out of byte range: $ttl" }
        return ByteArray(BleConfig.SERVICE_DATA_SIZE).also {
            it[BleConfig.TTL_OFFSET] = ttl.toByte()
            System.arraycopy(payload22, 0, it, BleConfig.PAYLOAD_OFFSET, 22)
            System.arraycopy(pub32, 0, it, BleConfig.PUBKEY_OFFSET, 32)
            System.arraycopy(sig64, 0, it, BleConfig.SIG_OFFSET, 64)
        }
    }

    fun stop() {
        val cb = activeCallback
        if (cb != null) {
            advertiser.stopAdvertisingSet(cb)
        }
        activeSet.set(null)
        activeCallback = null
    }

    companion object {
        fun create(context: Context): Broadcaster? {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter ?: run {
                Log.w("sosnet.ble.Broadcaster", "no BluetoothManager")
                return null
            }
            if (!adapter.isMultipleAdvertisementSupported) {
                Log.w("sosnet.ble.Broadcaster", "chip does not advertise; BLE extended ADV required")
                return null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !adapter.isLeExtendedAdvertisingSupported
            ) {
                Log.w("sosnet.ble.Broadcaster", "extended advertising not supported on this chip")
                return null
            }
            val advertiser = adapter.bluetoothLeAdvertiser ?: run {
                Log.w("sosnet.ble.Broadcaster", "null BluetoothLeAdvertiser")
                return null
            }
            return Broadcaster(advertiser)
        }
    }
}
