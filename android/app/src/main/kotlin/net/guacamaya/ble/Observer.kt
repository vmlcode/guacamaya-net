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
 * BLE GAP Observer. Software filter on the GuacaMalla Service UUID in [ScanRecord] —
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
    @Volatile private var scanProfile = BleConfig.ScanProfile.BALANCED
    @Volatile private var radarActive = false
    private var lastCallbackAt = 0L
    @Volatile private var lastGuacamayaFrameAt = 0L
    private var scanCallbackCount = 0
    private val activeCallback = AtomicReference<ScanCallback?>(null)
    private val exitRadarRunnable = Runnable {
        radarActive = false
        switchProfile(defaultScanProfile(), "radar debounce elapsed")
    }

    /**
     * Called on every Guacamaya-shaped frame. [kind] tells the listener whether the
     * frame arrived under the SOS Service UUID (an SOS payload) or the control Service
     * UUID (a delivery / backend-resolve / self-cancel payload) — the routing decision
     * is made from the UUID, never from payload-byte inspection on the hot path.
     * Implementations must be thread-safe.
     */
    fun interface FrameListener {
        fun onFrame(
            kind: BleConfig.FrameKind,
            payload22: ByteArray,
            pub32: ByteArray,
            sig64: ByteArray,
            ttl: Int,
            radio: RadioMetadata,
        )
    }

    /** Per-PDU radio metadata. `ALL_MATCHES` is intentional: RSSI filtering needs
     * repeated advertising samples, not a deduplicated device callback. */
    data class RadioMetadata(
        val rssi: Int,
        val txPower: Int?,
        val primaryPhy: Int,
        val secondaryPhy: Int,
    )

    private var listener: FrameListener? = null
    fun interface ChatFrameListener { fun onFrame(frame: ByteArray, radio: RadioMetadata) }
    private var chatListener: ChatFrameListener? = null

    fun setListener(l: FrameListener) { listener = l }
    fun setChatListener(l: ChatFrameListener) { chatListener = l }

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
            if (errorCode == ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) {
                Log.e(tag, "scan registration rejected; Android may be rate-limiting start/stop cycles")
            }
            scanning = false
            activeCallback.set(null)
            scheduleScanRestart(fallback = scanProfile != BleConfig.ScanProfile.LEGACY_STACK)
        }
    }

    private fun handle(result: ScanResult) {
        val rec = result.scanRecord ?: return
        val sawUuid = rec.serviceUuids?.any { it in BleConfig.MATCH_PARCEL_UUIDS } == true
        if (sawUuid) {
            Log.i(tag, "saw UUID rssi=${result.rssi} legacy=${result.isLegacy} len=${rec.bytes?.size ?: 0}")
            Log.i("guacamaya.probe", "saw_uuid rssi=${result.rssi} legacy=${result.isLegacy}")
        }
        val blobs = extractServiceData(rec)
        if (blobs.isEmpty()) return
        // A scan record can carry service-data under several UUIDs at once — e.g. an SOS
        // origin under SERVICE_UUID and a control relay under CONTROL_SERVICE_UUID in the
        // same advertisement. Surface each blob tagged with the kind its UUID implies.
        for ((blob, kind) in blobs) {
            if (kind == BleConfig.FrameKind.CHAT) {
                lastGuacamayaFrameAt = SystemClock.elapsedRealtime()
                chatListener?.onFrame(
                    blob,
                    RadioMetadata(result.rssi, result.txPower.takeUnless { it == ScanResult.TX_POWER_NOT_PRESENT }, result.primaryPhy, result.secondaryPhy),
                )
                continue
            }
            if (blob.size != BleConfig.SERVICE_DATA_SIZE) {
                if (sawUuid) {
                    Log.w(
                        tag,
                        "$kind frame incomplete size=${blob.size} " +
                            "(need ${BleConfig.SERVICE_DATA_SIZE}) rssi=${result.rssi} legacy=${result.isLegacy}",
                    )
                }
                continue
            }
            val ttl = blob[BleConfig.TTL_OFFSET].toInt() and 0xFF
            val payload22 = blob.copyOfRange(BleConfig.PAYLOAD_OFFSET, BleConfig.PUBKEY_OFFSET)
            val pub32 = blob.copyOfRange(BleConfig.PUBKEY_OFFSET, BleConfig.SIG_OFFSET)
            val sig64 = blob.copyOfRange(BleConfig.SIG_OFFSET, BleConfig.SERVICE_DATA_SIZE)
            Log.d(tag, "$kind frame ttl=$ttl rssi=${result.rssi} src=${result.device.address}")
            lastGuacamayaFrameAt = SystemClock.elapsedRealtime()
            listener?.onFrame(
                kind,
                payload22,
                pub32,
                sig64,
                ttl,
                RadioMetadata(
                    rssi = result.rssi,
                    txPower = result.txPower.takeUnless { it == ScanResult.TX_POWER_NOT_PRESENT },
                    primaryPhy = result.primaryPhy,
                    secondaryPhy = result.secondaryPhy,
                ),
            )
        }
    }

    /**
     * Prefer the parsed map; fall back to raw AD bytes. Some older stacks populate
     * [ScanRecord.getServiceUuids] but leave [ScanRecord.getServiceData] empty on
     * extended ADV secondary payloads. Each blob is tagged with the [BleConfig.FrameKind]
     * of the UUID it was found under, so the caller routes SOS vs control by UUID and
     * never inspects payload bytes on the hot path. Within a kind, the first blob wins
     * (a broadcaster advertises under exactly one UUID of a kind, so duplicates only
     * arise when the parsed map and the raw-AD parse both surface the same UUID).
     */
    private fun extractServiceData(rec: ScanRecord): List<Pair<ByteArray, BleConfig.FrameKind>> {
        val out = ArrayList<Pair<ByteArray, BleConfig.FrameKind>>()
        val seenKinds = mutableSetOf<BleConfig.FrameKind>()
        rec.serviceData?.forEach { (parcel, blob) ->
            val kind = BleConfig.kindOf(parcel) ?: return@forEach
            if (seenKinds.add(kind)) out.add(blob to kind)
        }
        for (uuid in BleConfig.MATCH_UUIDS) {
            val kind = BleConfig.kindOfUuid(uuid) ?: continue
            if (kind in seenKinds) continue
            parseServiceData128(rec.bytes, uuid)?.let {
                seenKinds.add(kind)
                out.add(it to kind)
            }
        }
        return out
    }

    /** Begin scanning. Idempotent. */
    fun start() {
        if (scanning) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !adapter.isLeExtendedAdvertisingSupported
        ) {
            Log.w(tag, "chip lacks LE extended advertising — extended SOS frames may not arrive")
        }
        scanProfile = if (radarActive && !isXiaomiLegacyStack()) {
            BleConfig.ScanProfile.AGGRESSIVE
        } else {
            defaultScanProfile()
        }
        Log.i(tag, "device=${Build.DEVICE} mfr=${Build.MANUFACTURER} api=${Build.VERSION.SDK_INT} profile=$scanProfile")
        startInternal()
    }

    /** Xiaomi/MTK API ≤31: start LEGACY_STACK — AGGRESSIVE often misses extended ADV. */
    private fun defaultScanProfile(): BleConfig.ScanProfile =
        if (isXiaomiLegacyStack()) BleConfig.ScanProfile.LEGACY_STACK
        else BleConfig.ScanProfile.BALANCED

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
        mainHandler.removeCallbacks(exitRadarRunnable)
        radarActive = false
    }

    val isScanning: Boolean get() = scanning

    /** Active rescue search needs a low-latency RSSI stream. Normal mesh mode
     * returns to balanced scanning as soon as the radar closes. */
    fun setRadarActive(active: Boolean) {
        if (active) {
            mainHandler.removeCallbacks(exitRadarRunnable)
            if (radarActive) return
            radarActive = true
            val wanted = if (!isXiaomiLegacyStack()) BleConfig.ScanProfile.AGGRESSIVE else defaultScanProfile()
            switchProfile(wanted, "radar opened")
        } else if (radarActive) {
            // Android can throttle apps that repeatedly stop/start scanning. Keep
            // low latency briefly while a rescuer switches between radar and map.
            mainHandler.removeCallbacks(exitRadarRunnable)
            mainHandler.postDelayed(exitRadarRunnable, RADAR_EXIT_DEBOUNCE_MS)
            Log.d(tag, "radar closed; keeping low-latency scan for ${RADAR_EXIT_DEBOUNCE_MS}ms")
        }
    }

    private fun switchProfile(wanted: BleConfig.ScanProfile, reason: String) {
        if (scanProfile == wanted) return
        scanProfile = wanted
        if (scanning) {
            Log.i(tag, "$reason; switching scan profile=$scanProfile")
            restart()
        }
    }

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

    /** If no GuacaMalla frames (legacy) or no callbacks (aggressive) for threshold → restart. */
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
                if (isXiaomiLegacyStack() && scanProfile != BleConfig.ScanProfile.LEGACY_STACK) {
                    scanProfile = BleConfig.ScanProfile.LEGACY_STACK
                } else if (scanProfile == BleConfig.ScanProfile.LEGACY_STACK) {
                    scanProfile = if (radarActive) BleConfig.ScanProfile.AGGRESSIVE else BleConfig.ScanProfile.BALANCED
                }
                restart()
            }
            mainHandler.postDelayed(this, interval)
        }
    }

    companion object {
        private const val RESTART_DELAY_MS = 1_500L
        private const val RADAR_EXIT_DEBOUNCE_MS = 10_000L
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        /** Legacy Xiaomi/sweet: check every 30 s, restart if no GuacaMalla frame in 60 s. */
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
