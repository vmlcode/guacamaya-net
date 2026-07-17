package net.guacamaya.ingest

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Result of one `POST /ingest` call. The backend returns aggregate counts, not
 * per-frame outcomes (`backend/src/channels/routes.ts`):
 *
 *   { success, ingested, duplicate, rejected, locationsIngested, reasons,
 *     controlAccepted, controlDuplicate, controlRejected, controlReasons,
 *     controlFrames: [<base64>, ...] }
 *
 * `ok` is true on any HTTP 2xx. Both `ingested` and `duplicate` mean the backend
 * now holds the frame; `rejected` should be ~0 for frames we send (we only store
 * frames that already passed our own identical reject cascade), so a non-zero
 * count is a data-integrity signal worth logging.
 *
 * [controlFrames] are backend-minted 118 B control frames (base64, TTL-stripped) the
 * server produced in response to the SOSes we uploaded — delivery (0x01) when an SOS
 * is still fresh, backend-resolve (0x02) once it is terminal. The uploader re-injects
 * them into the mesh so they reach the victim (and relay onward). Never self-cancel.
 */
data class IngestResult(
    val ok: Boolean,
    val ingested: Int = 0,
    val duplicate: Int = 0,
    val rejected: Int = 0,
    val controlAccepted: Int = 0,
    val controlDuplicate: Int = 0,
    val controlRejected: Int = 0,
    val controlFrames: List<String> = emptyList(),
    val certificateRevocationList: MuleCrl? = null,
)

data class MuleCrl(
    val sequence: Long,
    val issuedAt: Long,
    val nextUpdate: Long,
    val revokedPublicKeys: List<String>,
    val backendSignature: String,
)

/**
 * The uploader's view of the backend. Implemented by [IngestClient]; faked in tests
 * so [IngestRepository] stays free of Android/HTTP dependencies.
 */
interface IngestApi {
    /**
     * POST a batch of base64 SOS [frames] and (optional) base64 [controlFrames]
     * (self-cancels). Either list may be empty; the backend accepts a batch with just
     * one non-empty side. Throws [IOException] on transport failure.
     */
    suspend fun upload(frames: List<String>, controlFrames: List<String>): IngestResult
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

    override suspend fun upload(frames: List<String>, controlFrames: List<String>): IngestResult {
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
            conn.outputStream.use { it.write(buildBody(frames, controlFrames).toByteArray(Charsets.UTF_8)) }
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
                controlAccepted = json.optInt("controlAccepted", 0),
                controlDuplicate = json.optInt("controlDuplicate", 0),
                controlRejected = json.optInt("controlRejected", 0),
                controlFrames = json.optJSONArray("controlFrames")?.let { arr ->
                    List(arr.length()) { arr.getString(it) }
                } ?: emptyList(),
                certificateRevocationList = json.optJSONObject("certificateRevocationList")?.let { crl ->
                    val keys = crl.optJSONArray("revokedPublicKeys") ?: JSONArray()
                    MuleCrl(
                        sequence = crl.getLong("sequence"),
                        issuedAt = crl.getLong("issuedAt"),
                        nextUpdate = crl.getLong("nextUpdate"),
                        revokedPublicKeys = List(keys.length()) { keys.getString(it) },
                        backendSignature = crl.getString("backendSignature"),
                    )
                },
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Include only non-empty arrays. The backend requires at least one valid batch, so
     * a request carrying only SOS, only control, or both is always accepted — but a
     * key absent vs `[]` keeps the body minimal and sidesteps any empty-array edge in
     * `validateIngestBatch`.
     */
    private fun buildBody(frames: List<String>, controlFrames: List<String>): String {
        val obj = JSONObject()
        if (frames.isNotEmpty()) obj.put("frames", JSONArray(frames))
        if (controlFrames.isNotEmpty()) obj.put("controlFrames", JSONArray(controlFrames))
        return obj.toString()
    }
}
