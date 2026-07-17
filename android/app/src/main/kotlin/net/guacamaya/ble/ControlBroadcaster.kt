package net.guacamaya.ble

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
 * BLE GAP Broadcaster for **control frames** (delivery / backend-resolve / self-cancel),
 * advertised under [BleConfig.CONTROL_SERVICE_UUID] — a separate UUID from the SOS
 * [BleConfig.SERVICE_UUID] so the Observer routes a control PDU by UUID, never by
 * payload-byte inspection.
 *
 * Deliberately lighter than the SOS [Broadcaster]:
 *  - **Best-effort, no PHY fallback.** The SOS Broadcaster retries 1M/1M → 1M/CODED on a
 *    start failure; control relay is not life-critical broadcast (a missed relay still
 *    reaches the victim through other hops / the backend `/ingest` mule path), so a single
 *    [BleConfig.parametersCompat] attempt is enough and avoids the start/stop cycles
 *    Android throttles.
 *  - **Relay TTL is caller-supplied** ([net.guacamaya.mesh.FloodRouter] passes
 *    `ttl - 1`; a node never re-originates a control frame, only forwards one that already
 *    passed its own zero-trust verify). TTL 0 is not advertised — relay dies at the edge.
 *  - Same 119 B on-wire envelope as SOS: `[ttl][22 B payload][32 B pubkey][64 B sig]`.
 */
class ControlBroadcaster private constructor(
    private val advertiser: BluetoothLeAdvertiser,
) : ControlRelay {
    private val tag = "guacamaya.ble.ControlBroadcaster"

    private val activeSet = AtomicReference<AdvertisingSet?>(null)
    @Volatile private var activeCallback: AdvertisingSetCallback? = null

    private data class Quad(val p22: ByteArray, val pub: ByteArray, val sig: ByteArray, val ttl: Int)

    /** True if a control frame is currently on the air. */
    val isAdvertising: Boolean get() = activeSet.get() != null

    /**
     * Put (or replace) a control frame on the air under [BleConfig.CONTROL_SERVICE_UUID].
     * [ttl] is the unsigned hop budget the caller already decremented; 0 means "do not
     * relay" and is a no-op (the frame reached its hop edge). Best-effort: a start failure
     * is logged, not retried with a different PHY.
     */
    override fun relay(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray, ttl: Int) {
        if (ttl <= 0) {
            Log.d(tag, "relay suppressed ttl=$ttl (hop edge)")
            return
        }
        beginAdvertising(Quad(payload22, pub32, sig64, ttl))
    }

    private fun beginAdvertising(quad: Quad) {
        val combined = frame(quad.p22, quad.pub, quad.sig, quad.ttl)

        val data = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .addServiceUuid(BleConfig.CONTROL_PARCEL_UUID)
            .addServiceData(BleConfig.CONTROL_PARCEL_UUID, combined)
            .build()

        // Stop any prior control set before starting a fresh one. Control relay is
        // low-frequency, so a stop+start per relay is acceptable and simpler than a swap.
        activeCallback?.let { advertiser.stopAdvertisingSet(it) }
        activeSet.set(null)

        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS) {
                    activeSet.set(set)
                    Log.i(tag, "control relay started ttl=${quad.ttl} txPower=$txPower dBm frame=${combined.size} B")
                } else {
                    Log.w(tag, "control relay start failed status=$status (best-effort, not retried)")
                }
            }

            override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                activeSet.set(null)
                activeCallback = null
                Log.d(tag, "control relay stopped")
            }
        }
        activeCallback = callback
        advertiser.startAdvertisingSet(BleConfig.parametersCompat, data, null, null, null, callback)
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
        fun create(context: Context): ControlBroadcaster? {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter ?: run {
                Log.w("guacamaya.ble.ControlBroadcaster", "no BluetoothManager")
                return null
            }
            if (!adapter.isMultipleAdvertisementSupported) {
                Log.w("guacamaya.ble.ControlBroadcaster", "chip does not advertise; BLE extended ADV required")
                return null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !adapter.isLeExtendedAdvertisingSupported
            ) {
                Log.w("guacamaya.ble.ControlBroadcaster", "extended advertising not supported on this chip")
                return null
            }
            val advertiser = adapter.bluetoothLeAdvertiser ?: run {
                Log.w("guacamaya.ble.ControlBroadcaster", "null BluetoothLeAdvertiser")
                return null
            }
            return ControlBroadcaster(advertiser)
        }
    }
}

/**
 * Minimal dependency of [net.guacamaya.mesh.FloodRouter] on the control relay: "put this
 * signed control frame on the air." Concrete [ControlBroadcaster] implements it; tests
 * inject a recording SAM. Kept separate from [ControlBroadcaster] so the router depends
 * on the one method it uses, not on the Android advertiser plumbing.
 */
fun interface ControlRelay {
    /**
     * Advertise a control frame under [BleConfig.CONTROL_SERVICE_UUID]. [ttl] is the
     * unsigned hop budget the caller already decremented; ≤ 0 means stop (hop edge).
     */
    fun relay(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray, ttl: Int)
}
