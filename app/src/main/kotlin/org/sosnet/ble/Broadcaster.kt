package org.sosnet.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

@SuppressLint("MissingPermission")
class Broadcaster private constructor(
    private val advertiser: BluetoothLeAdvertiser,
    private val codedPhySupported: Boolean,
) {
    private val tag = "sosnet.ble.Broadcaster"
    private val handler = Handler(Looper.getMainLooper())

    private val activeSet = AtomicReference<AdvertisingSet?>(null)
    @Volatile private var activeCallback: AdvertisingSetCallback? = null
    @Volatile private var pendingPayload: Triple<ByteArray, ByteArray, ByteArray>? = null
    @Volatile private var retryAttempt = 0

    val isAdvertising: Boolean get() = activeSet.get() != null

    fun start(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray) {
        require(payload22.size == 22 && pub32.size == 32 && sig64.size == 64)
        pendingPayload = Triple(payload22, pub32, sig64)
        retryAttempt = 0
        startInternal(payload22, pub32, sig64)
    }

    private fun startInternal(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray) {
        val data = buildAdvertiseData(payload22, pub32, sig64)
        val params = BleConfig.advertisingParameters(codedPhySupported)

        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS) {
                    activeSet.set(set)
                    retryAttempt = 0
                    Log.i(tag, "advertising started, txPower=$txPower dBm")
                } else {
                    Log.e(tag, "advertising start failed status=$status")
                    scheduleRetry()
                }
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

    private fun scheduleRetry() {
        val pending = pendingPayload ?: return
        if (retryAttempt >= MAX_RETRIES) return
        val delayMs = (1 shl retryAttempt) * 1000L
        retryAttempt++
        handler.postDelayed({
            if (activeSet.get() == null && pendingPayload != null) {
                startInternal(pending.first, pending.second, pending.third)
            }
        }, delayMs)
    }

    fun swap(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray) {
        val set = activeSet.get() ?: run {
            start(payload22, pub32, sig64)
            return
        }
        require(payload22.size == 22 && pub32.size == 32 && sig64.size == 64)
        pendingPayload = Triple(payload22, pub32, sig64)
        set.setAdvertisingData(buildAdvertiseData(payload22, pub32, sig64))
    }

    private fun buildAdvertiseData(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray): AdvertiseData {
        val combined = ByteArray(BleConfig.SERVICE_DATA_SIZE).also {
            System.arraycopy(payload22, 0, it, 0, 22)
            System.arraycopy(pub32, 0, it, 22, 32)
            System.arraycopy(sig64, 0, it, 22 + 32, 64)
        }
        return AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .addServiceUuid(BleConfig.SERVICE_PARCEL_UUID)
            .addServiceData(BleConfig.SERVICE_PARCEL_UUID, combined)
            .build()
    }

    fun stop() {
        pendingPayload = null
        retryAttempt = 0
        handler.removeCallbacksAndMessages(null)
        val cb = activeCallback
        if (cb != null) advertiser.stopAdvertisingSet(cb)
        activeSet.set(null)
        activeCallback = null
    }

    companion object {
        private const val MAX_RETRIES = 5

        fun create(context: Context): Broadcaster? {
            val caps = BleCapabilities.probeCapabilities(context)
            if (caps.status == BleCapabilities.Status.BtOff ||
                caps.status == BleCapabilities.Status.NoBluetooth ||
                caps.status == BleCapabilities.Status.NoMultipleAdv ||
                caps.status == BleCapabilities.Status.NoExtendedAdv
            ) {
                Log.w("sosnet.ble.Broadcaster", "unsupported: ${caps.status}")
                return null
            }
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val advertiser = bm?.adapter?.bluetoothLeAdvertiser ?: return null
            return Broadcaster(advertiser, caps.codedPhy)
        }
    }
}
