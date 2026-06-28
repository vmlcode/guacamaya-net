package net.guacamaya.backend

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Read-only HTTP client for the optional Guacamaya backend (downlink). Built on
 * [HttpURLConnection] — no OkHttp/Retrofit, matching [net.guacamaya.ingest.IngestClient].
 *
 * Only the endpoints a phone legitimately consumes, all unauthenticated:
 *   - GET /health                       — connectivity probe
 *   - GET /pubkey                       — backend Ed25519 pubkey (verify official records)
 *   - GET /channels/:id/records?since=  — official alerts (verified server-side)
 *
 * The app must NEVER carry server API keys (admin/read/ws). See backend_final.md §2.
 */
class BackendClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 15_000,
) {

    /** True if GET /health returns `{ "ok": true }`. Swallows transport errors → false. */
    fun health(): Boolean = try {
        val body = get("/health")
        JSONObject(body).optBoolean("ok", false)
    } catch (_: Exception) {
        false
    }

    /** Backend public key hex, or null if unreachable/malformed. */
    fun pubkey(): String? = try {
        JSONObject(get("/pubkey")).optString("publicKey").ifBlank { null }
    } catch (_: Exception) {
        null
    }

    /**
     * Records of an official channel since [sinceMs]. Raw payload text is preserved
     * for signature verification (see [RecordJson]). Throws [IOException] on transport
     * failure so the caller can distinguish "offline" from "empty".
     */
    fun records(channel: String, sinceMs: Long = 0): List<OfficialRecord> {
        val body = get("/channels/$channel/records?since=$sinceMs")
        return RecordJson.parseRecords(body)
    }

    private fun get(path: String): String {
        val conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.errorStream?.use { it.readBytes() }
                throw IOException("GET $path → HTTP $code")
            }
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
