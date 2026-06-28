package net.guacamaya.ingest

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Result of one `POST /ingest` call. The backend returns aggregate counts only,
 * not per-frame outcomes (`backend/src/channels/routes.ts`):
 *
 *   { success, ingested, duplicate, rejected, locationsIngested, reasons }
 *
 * `ok` is true on any HTTP 2xx. Both `ingested` and `duplicate` mean the backend
 * now holds the frame; `rejected` should be ~0 for frames we send (we only store
 * frames that already passed our own identical reject cascade), so a non-zero
 * count is a data-integrity signal worth logging.
 */
data class IngestResult(
    val ok: Boolean,
    val ingested: Int = 0,
    val duplicate: Int = 0,
    val rejected: Int = 0,
)

/**
 * The uploader's view of the backend. Implemented by [IngestClient]; faked in tests
 * so [IngestRepository] stays free of Android/HTTP dependencies.
 */
interface IngestApi {
    /** POST a batch of base64 frames. Throws [IOException] on transport failure. */
    suspend fun upload(frames: List<String>): IngestResult
}

/**
 * Zero-dependency HTTP client for `POST {baseUrl}/ingest`, built on [HttpURLConnection]
 * (no OkHttp/Retrofit). The endpoint is intentionally unauthenticated — it is
 * zero-trust by Ed25519 signature, re-verified server-side — so no API key is sent.
 *
 * Cleartext HTTP to the emulator host (10.0.2.2) is allowed via
 * res/xml/network_security_config.xml; use HTTPS for any real deployment.
 */
class IngestClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) : IngestApi {

    override suspend fun upload(frames: List<String>): IngestResult {
        val url = URL(baseUrl.trimEnd('/') + "/ingest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(buildBody(frames).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                // Drain the error stream so the connection can be pooled/closed cleanly.
                conn.errorStream?.use { it.readBytes() }
                throw IOException("ingest HTTP $code")
            }
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val json = JSONObject(text)
            return IngestResult(
                ok = true,
                ingested = json.optInt("ingested", 0),
                duplicate = json.optInt("duplicate", 0),
                rejected = json.optInt("rejected", 0),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun buildBody(frames: List<String>): String =
        JSONObject().put("frames", JSONArray(frames)).toString()
}
