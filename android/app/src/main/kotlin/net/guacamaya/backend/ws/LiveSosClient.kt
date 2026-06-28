package net.guacamaya.backend.ws

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.util.Base64
import kotlin.random.Random

/** A live community SOS pushed by the backend over WebSocket (channel `solicito-ayuda`). */
data class LiveSos(
    val recordId: String,
    val nodeId: String,
    val lat: Double,
    val lon: Double,
    val sosType: String,
    val critical: Boolean,
    val timestamp: Long,
)

/**
 * Minimal WebSocket client (downlink, live) that subscribes to a backend channel and
 * streams community SOS records — so a connected phone sees reports from other regions
 * without polling, complementing the local BLE mesh.
 *
 * Hand-rolled RFC 6455 over a raw [Socket] (no OkHttp), using [WsFrame]. **Cleartext
 * `ws://` only** for now (matches the demo backend + debug network-security-config); a
 * `wss://` host is skipped and logged — TLS is future hardening. Reconnects with
 * exponential backoff; the mesh never depends on this connection.
 *
 * Community channels (`solicito-ayuda`, `estoy-bien`) need no token. NOTE: if the backend
 * sets a WS key, the upgrade is gated for ALL connections — then even community channels
 * need a token the app shouldn't carry; that's a backend-side concern to relax for phones.
 */
class LiveSosClient(
    private val baseUrl: String,
    private val channel: String = "solicito-ayuda",
) {
    private val tag = "guacamaya.ws"

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope, onSos: (LiveSos) -> Unit) {
        if (running) return
        running = true
        job = scope.launch(Dispatchers.IO) { loop(onSos) }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        job?.cancel()
    }

    private suspend fun loop(onSos: (LiveSos) -> Unit) {
        var backoff = 2_000L
        while (running) {
            try {
                connectAndRead(onSos)
                backoff = 2_000L // a clean session resets backoff
            } catch (e: Exception) {
                Log.w(tag, "live SOS session ended: ${e.message}")
            }
            if (running) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun connectAndRead(onSos: (LiveSos) -> Unit) {
        val uri = URI(baseUrl)
        if (uri.scheme?.lowercase() != "http") {
            // wss over TLS is not implemented; skip rather than fail loudly every backoff.
            Log.w(tag, "live SOS supports ws:// only for now (got ${uri.scheme}); skipping")
            throw IOException("unsupported scheme ${uri.scheme}")
        }
        val host = uri.host ?: throw IOException("no host in $baseUrl")
        val port = if (uri.port != -1) uri.port else 80

        Socket(host, port).use { sock ->
            socket = sock
            val out = sock.getOutputStream()
            val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
            handshake(host, port, out, input)
            out.write(WsFrame.clientText("""{"type":"subscribe","channel":"$channel"}"""))
            out.flush()
            Log.i(tag, "subscribed to '$channel' at ws://$host:$port/ws")

            while (running) {
                val msg = WsFrame.read(input) ?: break
                when (msg.opcode) {
                    WsOpcode.TEXT -> handleText(msg.text(), onSos)
                    WsOpcode.PING -> { out.write(WsFrame.clientControl(WsOpcode.PONG, msg.payload)); out.flush() }
                    WsOpcode.CLOSE -> break
                }
            }
        }
    }

    private fun handshake(host: String, port: Int, out: OutputStream, input: DataInputStream) {
        val key = Base64.getEncoder().encodeToString(Random.nextBytes(16))
        val req = buildString {
            append("GET /ws HTTP/1.1\r\n")
            append("Host: $host:$port\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        out.write(req.toByteArray(Charsets.US_ASCII))
        out.flush()

        val statusLine = readHeaderLine(input) ?: throw IOException("no handshake response")
        if (!statusLine.contains("101")) throw IOException("ws upgrade rejected: $statusLine")
        // drain remaining headers up to the blank line; frame bytes follow.
        while (true) {
            val line = readHeaderLine(input) ?: break
            if (line.isEmpty()) break
        }
    }

    private fun readHeaderLine(input: DataInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun handleText(text: String, onSos: (LiveSos) -> Unit) {
        try {
            val o = JSONObject(text)
            if (o.optString("type") != "record") return
            val data = o.optJSONObject("data") ?: return
            if (data.optString("channel") != channel) return
            val payload = data.optJSONObject("payload") ?: return
            val id = data.optString("id")
            if (id.isBlank()) return
            onSos(
                LiveSos(
                    recordId = id,
                    nodeId = payload.optString("nodeId"),
                    lat = payload.optDouble("lat", Double.NaN),
                    lon = payload.optDouble("lon", Double.NaN),
                    sosType = payload.optString("sosType", "other"),
                    critical = payload.optBoolean("critical", false),
                    timestamp = data.optLong("timestamp"),
                )
            )
        } catch (e: Exception) {
            Log.w(tag, "ignoring malformed ws record: ${e.message}")
        }
    }
}
