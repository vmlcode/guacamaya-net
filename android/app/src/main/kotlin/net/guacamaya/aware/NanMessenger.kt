package net.guacamaya.aware

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference

/**
 * Wi-Fi Aware messenger for payloads up to 255 B (BLE frame layout, 118 B).
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
    private val context: Context,
) {
    private val tag = "guacamaya.aware.NanMessenger"
    private val handler = Handler(Looper.getMainLooper())

    private val sessionRef = AtomicReference<WifiAwareSession?>(null)
    private val publishRef = AtomicReference<PublishDiscoverySession?>(null)
    private val subscribeRef = AtomicReference<SubscribeDiscoverySession?>(null)
    private var dutyOpen: Runnable? = null
    private var dutyClose: Runnable? = null

    fun interface Listener {
        fun onFrame(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray, peer: PeerHandle)
    }
    fun interface DigestListener { fun onDigest(digest: ByteArray, peer: PeerHandle) }

    private var listener: Listener? = null
    private var digestListener: DigestListener? = null
    fun setListener(l: Listener) { listener = l }
    fun setDigestListener(l: DigestListener) { digestListener = l }

    fun publishDigest(ids: Collection<String>) = publish(byteArrayOf(DIGEST_TYPE) + NanDigestCodec.encode(ids))

    /** True when Wi-Fi Aware is attached and usable. */
    val isAttached: Boolean get() = sessionRef.get() != null

    /** Attach (or re-attach) to the Wi-Fi Aware cluster. Idempotent. */
    fun attach(onAttached: () -> Unit = {}, onFailed: (Int) -> Unit = {}) {
        if (sessionRef.get() != null) {
            onAttached()
            return
        }
        if (!manager.isAvailable) {
            Log.w(tag, "attach skipped — WifiAware not available")
            onFailed(-1)
            return
        }
        val awarePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(context, awarePermission) != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "attach skipped — $awarePermission not granted")
            onFailed(-3)
            return
        }
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
            attach(
                onAttached = { publish(ssi) },
                onFailed = { Log.e(tag, "publish: attach failed (code=$it), frame lost") },
            )
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

        session.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                publishRef.set(session)
                Log.i(tag, "publish started")
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                Log.d(tag, "msg from peer=${peerHandle.hashCode()} size=${message.size}")
            }
        }, handler)
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
            attach(
                onAttached = { subscribe() },
                onFailed = { Log.e(tag, "subscribe: attach failed (code=$it)") },
            )
            return
        }
        val config = SubscribeConfig.Builder()
            .setServiceName(AwareConfig.SERVICE_NAME)
            .build()
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
                if (ssi.firstOrNull() == DIGEST_TYPE) {
                    digestListener?.onDigest(ssi.copyOfRange(1, ssi.size), peerHandle)
                    return
                }
                if (ssi.size != AwareConfig.SSI_SIZE) {
                    Log.w(tag, "discovered but malformed ssi size=${ssi.size}")
                    return
                }
                val p22 = ssi.copyOfRange(0, 22)
                val pub32 = ssi.copyOfRange(22, 22 + 32)
                val sig64 = ssi.copyOfRange(22 + 32, 22 + 32 + 64)
                listener?.onFrame(p22, pub32, sig64, peerHandle)
            }
        }, handler)
    }

    fun stopSubscribe() {
        subscribeRef.getAndSet(null)?.close()
    }

    /** Listen in bounded discovery bursts instead of pinning Wi-Fi Aware on. */
    fun startDutyCycled(cycle: NanDutyCycle) {
        stopDutyCycled()
        lateinit var open: Runnable
        lateinit var close: Runnable
        open = Runnable {
            subscribe()
            handler.postDelayed(close, cycle.listenMs)
        }
        close = Runnable {
            stopSubscribe()
            handler.postDelayed(open, cycle.sleepMs)
        }
        dutyOpen = open
        dutyClose = close
        handler.post(open)
    }

    fun stopDutyCycled() {
        dutyOpen?.let(handler::removeCallbacks)
        dutyClose?.let(handler::removeCallbacks)
        dutyOpen = null
        dutyClose = null
        stopSubscribe()
    }

    fun detach() {
        stopPublish()
        stopDutyCycled()
        sessionRef.getAndSet(null)?.close()
    }

    companion object {
        private const val DIGEST_TYPE: Byte = 0x7e
        fun create(context: Context): NanMessenger? {
            val mgr = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
                ?: run {
                    Log.w("guacamaya.aware.NanMessenger", "no WifiAwareManager")
                    return null
                }
            return NanMessenger(mgr, context.applicationContext)
        }
    }
}
