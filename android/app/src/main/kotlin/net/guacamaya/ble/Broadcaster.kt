package net.guacamaya.ble

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
 * itself is owned by `mesh.FloodRouter` (origin) or `service.GuacamayaForegroundService`.
 */
class Broadcaster private constructor(
    private val advertiser: BluetoothLeAdvertiser,
) {
    private val tag = "guacamaya.ble.Broadcaster"

    private val activeSet = AtomicReference<AdvertisingSet?>(null)
    @Volatile private var currentServiceData: ByteArray = ByteArray(BleConfig.SERVICE_DATA_SIZE)
    @Volatile private var activeCallback: AdvertisingSetCallback? = null
    @Volatile private var pendingPayload: Quad? = null
    @Volatile private var activeParams: android.bluetooth.le.AdvertisingSetParameters = BleConfig.parametersCompat
    @Volatile private var activeLabel: String = "1M/1M"

    private data class Quad(val p22: ByteArray, val pub: ByteArray, val sig: ByteArray, val ttl: Int)

    /** True if advertising is currently on. */
    val isAdvertising: Boolean get() = activeSet.get() != null

    /**
     * Start advertising with the given hop TTL + 22 B payload + 32 B pubkey + 64 B
     * signature, concatenated into a single service-data blob keyed by the Guacamaya
     * Service UUID. [ttl] is the unsigned hop budget (0..15) written at offset 0.
     */
    fun start(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray, ttl: Int = BleConfig.ORIGIN_HOP_TTL) {
        pendingPayload = Quad(payload22, pub32, sig64, ttl)
        beginAdvertising(BleConfig.parametersCompat, "1M/1M")
    }

    private fun beginAdvertising(params: android.bluetooth.le.AdvertisingSetParameters, label: String) {
        val pending = pendingPayload ?: return
        activeParams = params
        activeLabel = label
        val combined = frame(pending.p22, pending.pub, pending.sig, pending.ttl)
        currentServiceData = combined

        val data = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .addServiceUuid(BleConfig.SERVICE_PARCEL_UUID)
            .addServiceData(BleConfig.SERVICE_PARCEL_UUID, combined)
            .build()

        activeCallback?.let { advertiser.stopAdvertisingSet(it) }
        activeSet.set(null)

        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS) {
                    activeSet.set(set)
                    Log.i(tag, "advertising started ($label), txPower=$txPower dBm, frame=${combined.size} B")
                } else if (params == BleConfig.parametersCompat) {
                    Log.w(tag, "compat ADV failed status=$status — retry 1M/CODED")
                    beginAdvertising(BleConfig.parameters, "1M/CODED")
                } else {
                    Log.e(tag, "advertising start failed status=$status ($label)")
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
        advertiser.startAdvertisingSet(params, data, null, null, null, callback)
    }

    /**
     * Swap the active frame without re-triggering startAdvertisingSet.
     * Uses AdvertisingSet#setAdvertisingData to update in place — faster than
     * stop+start and avoids the controller re-arming delay.
     */
    fun swap(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray, ttl: Int = BleConfig.ORIGIN_HOP_TTL) {
        pendingPayload = Quad(payload22, pub32, sig64, ttl)
        val set = activeSet.get()
        if (set == null) {
            Log.w(tag, "swap while not advertising — restarting")
            beginAdvertising(activeParams, activeLabel)
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
        try {
            set.setAdvertisingData(data)
        } catch (e: RuntimeException) {
            Log.w(tag, "swap failed (${e.javaClass.simpleName}) — restart ADV")
            activeSet.set(null)
            beginAdvertising(activeParams, "$activeLabel-restart")
        }
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
                Log.w("guacamaya.ble.Broadcaster", "no BluetoothManager")
                return null
            }
            if (!adapter.isMultipleAdvertisementSupported) {
                Log.w("guacamaya.ble.Broadcaster", "chip does not advertise; BLE extended ADV required")
                return null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !adapter.isLeExtendedAdvertisingSupported
            ) {
                Log.w("guacamaya.ble.Broadcaster", "extended advertising not supported on this chip")
                return null
            }
            val advertiser = adapter.bluetoothLeAdvertiser ?: run {
                Log.w("guacamaya.ble.Broadcaster", "null BluetoothLeAdvertiser")
                return null
            }
            return Broadcaster(advertiser)
        }
    }
}
