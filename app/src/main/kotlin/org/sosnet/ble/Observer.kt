package org.sosnet.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log

/**
 * BLE GAP Observer. Software filter on the SOSNet Service UUID in [ScanRecord] —
 * hardware ScanFilter is avoided because many stacks omit extended service-data
 * from the record when filtering in silicon. See docs/protocol-flows.md Flow 2.
 *
 * On match, extracts the 22 B payload + 64 B signature from the service-data block
 * and forwards to a [FrameListener]. The expensive Ed25519 verify happens downstream
 * in `mesh.FloodRouter`, not here.
 */
class Observer private constructor(
    private val adapter: BluetoothAdapter,
) {
    private val tag = "sosnet.ble.Observer"

    @Volatile private var scanning = false

    /** Called on every SOSNet-shaped frame. Implementations must be thread-safe. */
    fun interface FrameListener {
        fun onFrame(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray, rssi: Int)
    }

    private var listener: FrameListener? = null

    fun setListener(l: FrameListener) { listener = l }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handle(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handle(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(tag, "scan failed code=$errorCode")
            scanning = false
        }

        private fun handle(result: ScanResult) {
            val rec = result.scanRecord ?: return
            if (rec.serviceUuids?.contains(BleConfig.SERVICE_PARCEL_UUID) != true) return

            val blob: ByteArray? = rec.serviceData?.get(BleConfig.SERVICE_PARCEL_UUID)
            if (blob == null || blob.size != BleConfig.SERVICE_DATA_SIZE) {
                Log.w(tag, "SOSNet UUID seen but serviceData size=${blob?.size ?: -1} (need ${BleConfig.SERVICE_DATA_SIZE}) rssi=${result.rssi}")
                return
            }
            val payload22 = blob.copyOfRange(0, 22)
            val pub32 = blob.copyOfRange(22, 22 + 32)
            val sig64 = blob.copyOfRange(22 + 32, 22 + 32 + 64)
            Log.d(tag, "frame rssi=${result.rssi} src=${result.device.address}")
            listener?.onFrame(payload22, pub32, sig64, result.rssi)
        }
    }

    /** Begin scanning. Idempotent. */
    fun start() {
        if (scanning) return
        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
        // Broadcaster uses BLE 5 extended ADV (118 B). Legacy-only scan misses it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settingsBuilder.setLegacy(false)
            settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
        val settings = settingsBuilder.build()
        // No hardware ScanFilter: many stacks match the UUID in silicon but omit
        // extended service-data from ScanRecord. Filter in software instead.
        adapter.bluetoothLeScanner?.startScan(null, settings, callback) ?: run {
            Log.e(tag, "BluetoothLeScanner is null — is BT off?")
            return
        }
        scanning = true
        Log.i(tag, "scan started (software filter), service=${BleConfig.SERVICE_UUID}")
    }

    fun stop() {
        if (!scanning) return
        adapter.bluetoothLeScanner?.stopScan(callback)
        scanning = false
        Log.i(tag, "scan stopped")
    }

    val isScanning: Boolean get() = scanning

    companion object {
        fun create(context: Context): Observer? {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter ?: run {
                Log.w("sosnet.ble.Observer", "no BluetoothManager")
                return null
            }
            if (adapter.bluetoothLeScanner == null) {
                Log.w("sosnet.ble.Observer", "null BluetoothLeScanner — is BT off?")
                return null
            }
            return Observer(adapter)
        }
    }
}
