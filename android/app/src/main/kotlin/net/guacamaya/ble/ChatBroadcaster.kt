package net.guacamaya.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.util.Log

class ChatBroadcaster private constructor(private val context: Context) {
    private val advertiser get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeAdvertiser
    private var callback: AdvertisingSetCallback? = null

    fun advertise(frame: ByteArray, txPower: Int, active: Boolean) {
        stop()
        val params = AdvertisingSetParameters.Builder()
            .setConnectable(false).setScannable(false)
            .setPrimaryPhy(android.bluetooth.BluetoothDevice.PHY_LE_1M)
            .setSecondaryPhy(android.bluetooth.BluetoothDevice.PHY_LE_1M)
            .setInterval(if (active) AdvertisingSetParameters.INTERVAL_LOW else AdvertisingSetParameters.INTERVAL_MEDIUM)
            .setTxPowerLevel(txPower).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceUuid(BleConfig.CHAT_PARCEL_UUID).addServiceData(BleConfig.CHAT_PARCEL_UUID, frame).build()
        val cb = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, power: Int, status: Int) {
                if (status != ADVERTISE_SUCCESS) Log.w(TAG, "chat advertise failed status=$status")
            }
        }
        callback = cb
        advertiser?.startAdvertisingSet(params, data, null, null, null, cb)
    }

    fun stop() { callback?.let { advertiser?.stopAdvertisingSet(it) }; callback = null }

    companion object {
        private const val TAG = "guacamaya.ble.Chat"
        fun create(context: Context) = ChatBroadcaster(context.applicationContext)
    }
}
