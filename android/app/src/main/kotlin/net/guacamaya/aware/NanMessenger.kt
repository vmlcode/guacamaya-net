package net.guacamaya.aware

import android.content.Context
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import net.guacamaya.ble.BleConfig

/**
 * Wi-Fi Aware messenger for payloads up to 255 B (BLE frame layout, 119 B).
 *
 * Two roles:
 *  - [publish] — radiate the SSI as part of the NAN service discovery frame.
 *    Any subscriber in range receives it via the MAC layer without IP or auth.
 *  - [subscribe] — listen for GuacaMalla publishers; the [Listener] fires per match.
 *
 * Wi-Fi Aware must be available on the device (feature `android.hardware.wifi.aware`)
 * and enabled in system settings. Caller is responsible for runtime permission.
 *
 * See docs/protocol-flows.md Flow 4.
 */
class NanMessenger private constructor(
    private val manager: WifiAwareManager,
) {
    private val tag = "guacamaya.aware.NanMessenger"
    private val handler = Handler(Looper.getMainLooper())

    private val sessionRef = AtomicReference<WifiAwareSession?>(null)
    private val publishRef = AtomicReference<PublishDiscoverySession?>(null)
    private val subscribeRef = AtomicReference<SubscribeDiscoverySession?>(null)

    fun interface Listener {
        fun onFrame(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray, ttl: Int, peer: PeerHandle)
    }

    private var listener: Listener? = null
    fun setListener(l: Listener) { listener = l }

    /** True when Wi-Fi Aware is attached and usable. */
    val isAttached: Boolean get() = sessionRef.get() != null

    /** Attach (or re-attach) to the Wi-Fi Aware cluster. Idempotent. */
    fun attach(onAttached: () -> Unit = {}, onFailed: (Int) -> Unit = {}) {
        if (sessionRef.get() != null) {
            onAttached()
            return
        }
        if (!manager.isAvailable) {
            Log.w(tag, "WifiAware not available")
            onFailed(-1)
            return
        }
        try {
            manager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession?) {
                    sessionRef.set(session)
                    Log.i(tag, "Aware attached")
                    onAttached()
                }

                override fun onAttachFailed() {
                    Log.e(tag, "Aware attach failed")
                    onFailed(-2)
                }
            }, handler)
        } catch (se: SecurityException) {
            Log.w(tag, "Aware attach denied — NEARBY_WIFI_DEVICES? ${se.message}")
            onFailed(-3)
        } catch (t: Throwable) {
            Log.w(tag, "Aware attach unavailable: ${t.message}")
            onFailed(-4)
        }
    }

    /**
     * Publish [ssi] (max 255 B) as the GuacaMalla service. Subscribers in range
     * receive it via onServiceDiscovered without any pairing.
     *
     * Re-publishing with a new SSI calls updatePublish on the existing session
     * rather than tearing down and re-creating.
     */
    fun publish(ssi: ByteArray) {
        require(ssi.size <= AwareConfig.SSI_MAX) { "SSI must be ≤ ${AwareConfig.SSI_MAX} B" }

        val session = sessionRef.get() ?: run {
            Log.w(tag, "publish called before attach; attaching then publishing")
            attach(onAttached = { publish(ssi) })
            return
        }

        val config = PublishConfig.Builder()
            .setServiceName(AwareConfig.SERVICE_NAME)
            .setServiceSpecificInfo(ssi)
            .build()

        val existing = publishRef.get()
        if (existing != null) {
            existing.updatePublish(config)
            Log.d(tag, "publish updated, ${ssi.size} B")
            return
        }

        try {
            session.publish(config, object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    publishRef.set(session)
                    Log.i(tag, "publish started")
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    Log.d(tag, "msg from peer=${peerHandle.hashCode()} size=${message.size}")
                }
            }, handler)
        } catch (se: SecurityException) {
            Log.w(tag, "publish denied — NEARBY_WIFI_DEVICES? ${se.message}")
        } catch (t: Throwable) {
            Log.w(tag, "publish failed: ${t.message}")
        }
    }

    fun stopPublish() {
        publishRef.getAndSet(null)?.close()
    }

    /**
     * Subscribe to the GuacaMalla service. The [Listener] fires for every publisher
     * seen, including their SSI bytes.
     */
    fun subscribe() {
        val session = sessionRef.get() ?: run {
            attach(onAttached = { subscribe() })
            return
        }
        val config = SubscribeConfig.Builder()
            .setServiceName(AwareConfig.SERVICE_NAME)
            .build()
        try {
            session.subscribe(config, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    subscribeRef.set(session)
                    Log.i(tag, "subscribe started")
                }

                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray,
                    matchFilter: MutableList<ByteArray>,
                ) {
                    val ssi = serviceSpecificInfo
                    if (ssi.size != AwareConfig.SSI_SIZE) {
                        Log.w(tag, "discovered but malformed ssi size=${ssi.size}")
                        return
                    }
                    val ttl = ssi[BleConfig.TTL_OFFSET].toInt() and 0xFF
                    val p22 = ssi.copyOfRange(BleConfig.PAYLOAD_OFFSET, BleConfig.PUBKEY_OFFSET)
                    val pub32 = ssi.copyOfRange(BleConfig.PUBKEY_OFFSET, BleConfig.SIG_OFFSET)
                    val sig64 = ssi.copyOfRange(BleConfig.SIG_OFFSET, AwareConfig.SSI_SIZE)
                    listener?.onFrame(p22, pub32, sig64, ttl, peerHandle)
                }
            }, handler)
        } catch (se: SecurityException) {
            Log.w(tag, "subscribe denied — NEARBY_WIFI_DEVICES? ${se.message}")
        } catch (t: Throwable) {
            Log.w(tag, "subscribe failed: ${t.message}")
        }
    }

    fun stopSubscribe() {
        subscribeRef.getAndSet(null)?.close()
    }

    fun detach() {
        stopPublish()
        stopSubscribe()
        sessionRef.getAndSet(null)?.close()
    }

    companion object {
        fun create(context: Context): NanMessenger? {
            val mgr = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
                ?: run {
                    Log.w("guacamaya.aware.NanMessenger", "no WifiAwareManager")
                    return null
                }
            return NanMessenger(mgr)
        }
    }
}
