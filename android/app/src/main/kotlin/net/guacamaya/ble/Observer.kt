package net.guacamaya.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * BLE GAP Observer. Software filter on the Guacamalla Service UUID in [ScanRecord] —
 * hardware ScanFilter is avoided because many stacks omit extended service-data
 * from the record when filtering in silicon. See docs/protocol-flows.md Flow 2.
 *
 * Tuned for older Android stacks (MTK / Xiaomi class): immediate scan reports,
 * fallback scan profile on failure, raw-AD service-data parse when the stack
 * omits [ScanRecord.getServiceData], and periodic scan refresh when callbacks stall.
 */
class Observer private constructor(
    private val adapter: BluetoothAdapter,
) {
    private val tag = "guacamaya.ble.Observer"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var scanning = false
    @Volatile private var scanProfile = BleConfig.ScanProfile.AGGRESSIVE
    private var lastCallbackAt = 0L
    @Volatile private var lastGuacamayaFrameAt = 0L
    private var scanCallbackCount = 0
    private val activeCallback = AtomicReference<ScanCallback?>(null)

    /** Called on every Guacamaya-shaped frame. Implementations must be thread-safe. */
    fun interface FrameListener {
        fun onFrame(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray, ttl: Int, rssi: Int)
    }

    private var listener: FrameListener? = null

    fun setListener(l: FrameListener) { listener = l }

    private fun newCallback(): ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            lastCallbackAt = SystemClock.elapsedRealtime()
            val n = ++scanCallbackCount
            if (n % 100 == 0) {
                Log.i("guacamaya.probe", "scan callbacks=$n profile=$scanProfile")
            }
            if (n % 2000 == 0) {
                Log.i(tag, "scan callbacks=$n profile=$scanProfile")
            }
            handle(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            lastCallbackAt = SystemClock.elapsedRealtime()
            results?.forEach { handle(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(tag, "scan failed code=$errorCode profile=$scanProfile")
            scanning = false
            activeCallback.set(null)
            scheduleScanRestart(fallback = scanProfile == BleConfig.ScanProfile.AGGRESSIVE)
        }
    }

    private fun handle(result: ScanResult) {
        val rec = result.scanRecord ?: return
        val sawUuid = rec.serviceUuids?.contains(BleConfig.SERVICE_PARCEL_UUID) == true
        if (sawUuid) {
            Log.i(tag, "saw UUID rssi=${result.rssi} legacy=${result.isLegacy} len=${rec.bytes?.size ?: 0}")
            Log.i("guacamaya.probe", "saw_uuid rssi=${result.rssi} legacy=${result.isLegacy}")
        }
        val blob = extractServiceData(rec)
        if (blob == null || blob.size != BleConfig.SERVICE_DATA_SIZE) {
            val sawUuid = rec.serviceUuids?.contains(BleConfig.SERVICE_PARCEL_UUID) == true
            if (sawUuid || blob != null) {
                Log.w(
                    tag,
                    "Guacamalla frame incomplete size=${blob?.size ?: -1} " +
                        "(need ${BleConfig.SERVICE_DATA_SIZE}) rssi=${result.rssi} legacy=${result.isLegacy}",
                )
            }
            return
        }
        val ttl = blob[BleConfig.TTL_OFFSET].toInt() and 0xFF
        val payload22 = blob.copyOfRange(BleConfig.PAYLOAD_OFFSET, BleConfig.PUBKEY_OFFSET)
        val pub32 = blob.copyOfRange(BleConfig.PUBKEY_OFFSET, BleConfig.SIG_OFFSET)
        val sig64 = blob.copyOfRange(BleConfig.SIG_OFFSET, BleConfig.SERVICE_DATA_SIZE)
        Log.d(tag, "frame ttl=$ttl rssi=${result.rssi} src=${result.device.address}")
        lastGuacamayaFrameAt = SystemClock.elapsedRealtime()
        listener?.onFrame(payload22, pub32, sig64, ttl, result.rssi)
    }

    /**
     * Prefer the parsed map; fall back to raw AD bytes. Some older stacks populate
     * [ScanRecord.getServiceUuids] but leave [ScanRecord.getServiceData] empty on
     * extended ADV secondary payloads.
     */
    private fun extractServiceData(rec: ScanRecord): ByteArray? {
        rec.serviceData?.get(BleConfig.SERVICE_PARCEL_UUID)?.let { return it }
        return parseServiceData128(rec.bytes, BleConfig.SERVICE_UUID)
    }

    /** Begin scanning. Idempotent. */
    fun start() {
        if (scanning) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !adapter.isLeExtendedAdvertisingSupported
        ) {
            Log.w(tag, "chip lacks LE extended advertising — extended SOS frames may not arrive")
        }
        scanProfile = defaultScanProfile()
        Log.i(tag, "device=${Build.DEVICE} mfr=${Build.MANUFACTURER} api=${Build.VERSION.SDK_INT} profile=$scanProfile")
        startInternal()
    }

    /** Xiaomi/MTK API ≤31: start LEGACY_STACK — AGGRESSIVE often misses extended ADV. */
    private fun defaultScanProfile(): BleConfig.ScanProfile =
        if (isXiaomiLegacyStack()) BleConfig.ScanProfile.LEGACY_STACK
        else BleConfig.ScanProfile.AGGRESSIVE

    private fun isXiaomiLegacyStack(): Boolean {
        val mfr = Build.MANUFACTURER.lowercase()
        if (mfr != "xiaomi" && mfr != "redmi" && mfr != "poco") return false
        if (Build.VERSION.SDK_INT <= 31) return true
        val device = Build.DEVICE.lowercase()
        return device in XIAOMI_LEGACY_DEVICES
    }

    /** Stop and immediately restart — recovers stalled scans on older stacks. */
    fun restart() {
        stopInternal()
        startInternal()
    }

    fun stop() {
        stopInternal()
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.removeCallbacks(restartRunnable)
    }

    val isScanning: Boolean get() = scanning

    private fun startInternal() {
        val callback = newCallback()
        activeCallback.set(callback)
        val settings = BleConfig.scanSettings(adapter, scanProfile)
        try {
            adapter.bluetoothLeScanner?.startScan(null, settings, callback) ?: run {
                Log.e(tag, "BluetoothLeScanner is null — is BT off?")
                activeCallback.set(null)
                return
            }
        } catch (e: SecurityException) {
            Log.e(tag, "startScan denied — location/BT permission? ${e.message}")
            activeCallback.set(null)
            return
        }
        scanning = true
        lastCallbackAt = SystemClock.elapsedRealtime()
        lastGuacamayaFrameAt = lastCallbackAt
        mainHandler.removeCallbacks(watchdogRunnable)
        val interval = if (scanProfile == BleConfig.ScanProfile.LEGACY_STACK) {
            LEGACY_WATCHDOG_INTERVAL_MS
        } else {
            WATCHDOG_INTERVAL_MS
        }
        mainHandler.postDelayed(watchdogRunnable, interval)
        Log.i(tag, "scan started profile=$scanProfile service=${BleConfig.SERVICE_UUID}")
    }

    private fun stopInternal() {
        if (!scanning) return
        val cb = activeCallback.getAndSet(null)
        if (cb != null) {
            adapter.bluetoothLeScanner?.stopScan(cb)
        }
        scanning = false
        Log.i(tag, "scan stopped")
    }

    private fun scheduleScanRestart(fallback: Boolean) {
        mainHandler.removeCallbacks(restartRunnable)
        if (fallback) {
            scanProfile = BleConfig.ScanProfile.LEGACY_STACK
            Log.i(tag, "retrying scan with LEGACY_STACK profile")
        }
        mainHandler.postDelayed(restartRunnable, RESTART_DELAY_MS)
    }

    private val restartRunnable = Runnable {
        if (listener == null) return@Runnable
        stopInternal()
        startInternal()
    }

    /** If no Guacamalla frames (legacy) or no callbacks (aggressive) for threshold → restart. */
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!scanning) return
            val now = SystemClock.elapsedRealtime()
            val legacy = scanProfile == BleConfig.ScanProfile.LEGACY_STACK
            val idleCallbacks = now - lastCallbackAt
            val idleGuacamaya = now - lastGuacamayaFrameAt
            val threshold = if (legacy) LEGACY_FRAME_STALL_MS else STALL_THRESHOLD_MS
            val interval = if (legacy) LEGACY_WATCHDOG_INTERVAL_MS else WATCHDOG_INTERVAL_MS
            val stalled = if (legacy) idleGuacamaya >= threshold else idleCallbacks >= threshold
            if (stalled || (isXiaomiLegacyStack() && idleGuacamaya >= LEGACY_FRAME_STALL_MS)) {
                Log.w(
                    tag,
                    "scan stalled callbacks=${idleCallbacks}ms guacamalla=${idleGuacamaya}ms " +
                        "profile=$scanProfile — restarting",
                )
                if (isXiaomiLegacyStack() && scanProfile == BleConfig.ScanProfile.AGGRESSIVE) {
                    scanProfile = BleConfig.ScanProfile.LEGACY_STACK
                } else if (scanProfile == BleConfig.ScanProfile.LEGACY_STACK) {
                    scanProfile = BleConfig.ScanProfile.AGGRESSIVE
                }
                restart()
            }
            mainHandler.postDelayed(this, interval)
        }
    }

    companion object {
        private const val RESTART_DELAY_MS = 1_500L
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        /** Legacy Xiaomi/sweet: check every 30 s, restart if no Guacamalla frame in 60 s. */
        private const val LEGACY_WATCHDOG_INTERVAL_MS = 30_000L
        private const val LEGACY_FRAME_STALL_MS = 60_000L
        /** No BLE callbacks for 3 min while observing → force scan refresh. */
        private const val STALL_THRESHOLD_MS = 180_000L

        /** AD type: Service Data — 128-bit UUID. */
        private const val AD_TYPE_SERVICE_DATA_128 = 0x21

        /** Xiaomi / Redmi Note 10 class — codename sweet = Note 10 Pro. */
        private val XIAOMI_LEGACY_DEVICES = setOf(
            "sweet", "mojito", "rosemary", "lime", "courgette", "camellia", "camellian",
        )

        /** BLE AD encoding for a 128-bit service UUID (Bluetooth Core Spec). */
        internal fun uuidToLeBytes(uuid: UUID): ByteArray {
            val msb = uuid.mostSignificantBits
            val lsb = uuid.leastSignificantBits
            return byteArrayOf(
                (msb ushr 32).toByte(), (msb ushr 40).toByte(), (msb ushr 48).toByte(), (msb ushr 56).toByte(),
                (msb ushr 16).toByte(), (msb ushr 24).toByte(), msb.toByte(), (msb ushr 8).toByte(),
                (lsb ushr 56).toByte(), (lsb ushr 48).toByte(), (lsb ushr 40).toByte(), (lsb ushr 32).toByte(),
                (lsb ushr 24).toByte(), (lsb ushr 16).toByte(), (lsb ushr 8).toByte(), lsb.toByte(),
            )
        }

        /** Walk raw AD bytes when [ScanRecord.getServiceData] is empty. */
        internal fun parseServiceData128(ad: ByteArray?, target: UUID): ByteArray? {
            if (ad == null || ad.isEmpty()) return null
            val want = uuidToLeBytes(target)
            var i = 0
            while (i < ad.size) {
                val len = ad[i].toInt() and 0xFF
                if (len == 0) break
                if (i + len >= ad.size) break
                val type = ad[i + 1].toInt() and 0xFF
                val dataOff = i + 2
                val dataLen = len - 1
                if (type == AD_TYPE_SERVICE_DATA_128 && dataLen >= 16) {
                    var match = true
                    for (j in 0 until 16) {
                        if (ad[dataOff + j] != want[j]) {
                            match = false
                            break
                        }
                    }
                    if (match) {
                        val payloadLen = dataLen - 16
                        if (payloadLen <= 0) return null
                        return ad.copyOfRange(dataOff + 16, dataOff + dataLen)
                    }
                }
                i += len + 1
            }
            return null
        }

        fun create(context: Context): Observer? {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter ?: run {
                Log.w("guacamaya.ble.Observer", "no BluetoothManager")
                return null
            }
            if (adapter.bluetoothLeScanner == null) {
                Log.w("guacamaya.ble.Observer", "null BluetoothLeScanner — is BT off?")
                return null
            }
            return Observer(adapter)
        }
    }
}
