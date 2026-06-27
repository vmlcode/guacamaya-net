package org.sosnet.aware

import android.content.Context
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.WifiAwareSession
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * NAN Data Path (NDP) for payloads > 255 B. See docs/protocol-flows.md Flow 5.
 *
 * NDP wiring is not integrated into [org.sosnet.service.SosForegroundService] yet.
 * This stub keeps the module compiling on devices without Wi-Fi Aware hardware.
 */
class NanDataPath private constructor(
    private val context: Context,
    private val session: WifiAwareSession,
) {
    private val tag = "sosnet.aware.NanDataPath"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class Mode { SENDER, RECEIVER }

    fun interface HeavyReceiver {
        fun onHeavy(payload: ByteArray, peer: PeerHandle)
    }

    suspend fun sendHeavy(heavy: ByteArray, port: Int = NDP_PORT): SendResult {
        require(heavy.size > AwareConfig.SSI_MAX) {
            "heavy payload must exceed ${AwareConfig.SSI_MAX} B; use NanMessenger for smaller"
        }
        Log.w(tag, "sendHeavy: NDP not wired in foreground service yet (${heavy.size} B)")
        return SendResult.Error("NDP not wired")
    }

    suspend fun receiveHeavy(peer: PeerHandle, port: Int = NDP_PORT, onReceived: HeavyReceiver): ReceiveResult {
        Log.w(tag, "receiveHeavy: NDP not wired in foreground service yet")
        return ReceiveResult.Error("NDP not wired")
    }

    fun detach() { scope.cancel() }

    sealed class SendResult {
        data class Ok(val sentBytes: Int) : SendResult()
        object PublishFailed : SendResult()
        data class Error(val message: String) : SendResult()
    }

    sealed class ReceiveResult {
        data class Ok(val receivedBytes: Int) : ReceiveResult()
        object NoNetwork : ReceiveResult()
        object NoPeerIpv6 : ReceiveResult()
        data class Error(val message: String) : ReceiveResult()
    }

    companion object {
        const val NDP_PORT = 7654
        const val NDP_PROVISION_TIMEOUT_MS = 30_000
        const val NDP_SOCKET_TIMEOUT_MS = 10_000
        const val NDP_ACCEPT_TIMEOUT_MS = 30_000

        fun create(context: Context, session: WifiAwareSession): NanDataPath =
            NanDataPath(context, session)
    }
}
