package org.sosnet.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

@SuppressLint("MissingPermission")
class Observer private constructor(
    private val adapter: BluetoothAdapter,
) {
    private val tag = "sosnet.ble.Observer"
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var scanning = false
    @Volatile private var foregroundMode = false
    @Volatile private var retryAttempt = 0
    @Volatile private var wantsScan = false

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
            if (wantsScan) scheduleRetry()
        }

        private fun handle(result: ScanResult) {
            val rec = result.scanRecord ?: return
            if (rec.serviceUuids?.contains(BleConfig.SERVICE_PARCEL_UUID) != true) return

            val blob = rec.serviceData?.get(BleConfig.SERVICE_PARCEL_UUID) ?: return
            if (blob.size != BleConfig.SERVICE_DATA_SIZE) return

            listener?.onFrame(
                blob.copyOfRange(0, 22),
                blob.copyOfRange(22, 54),
                blob.copyOfRange(54, 118),
                result.rssi,
            )
        }
    }

    fun start(foreground: Boolean = false) {
        wantsScan = true
        val modeChanged = scanning && foregroundMode != foreground
        foregroundMode = foreground
        if (scanning) {
            if (modeChanged) {
                stopInternal()
            } else {
                return
            }
        }
        retryAttempt = 0
        startInternal()
    }

    private fun startInternal() {
        val settings = BleConfig.scanSettings(foregroundMode)
        adapter.bluetoothLeScanner?.startScan(null, settings, callback) ?: run {
            Log.e(tag, "BluetoothLeScanner is null")
            if (wantsScan) scheduleRetry()
            return
        }
        scanning = true
        Log.i(tag, "scan started foreground=$foregroundMode")
    }

    private fun scheduleRetry() {
        if (retryAttempt >= MAX_RETRIES) {
            Log.e(tag, "scan retry exhausted")
            return
        }
        val delayMs = minOf((1 shl retryAttempt) * 1000L, 30_000L)
        retryAttempt++
        handler.postDelayed({
            if (wantsScan && !scanning) startInternal()
        }, delayMs)
    }

    fun setForegroundMode(foreground: Boolean) {
        if (foregroundMode == foreground) return
        foregroundMode = foreground
        if (wantsScan && scanning) {
            stopInternal()
            startInternal()
        }
    }

    fun stop() {
        wantsScan = false
        retryAttempt = 0
        handler.removeCallbacksAndMessages(null)
        stopInternal()
    }

    private fun stopInternal() {
        if (!scanning) return
        adapter.bluetoothLeScanner?.stopScan(callback)
        scanning = false
        Log.i(tag, "scan stopped")
    }

    val isScanning: Boolean get() = scanning

    companion object {
        private const val MAX_RETRIES = 6

        fun create(context: Context): Observer? {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter ?: return null
            if (adapter.bluetoothLeScanner == null) return null
            return Observer(adapter)
        }
    }
}
