package net.guacamaya.backend

import android.util.Log

/** A verified official alert, ready for the UI. [payloadRaw] is the verbatim JSON. */
data class OfficialAlert(
    val recordId: String,
    val channel: String,
    val timestamp: Long,
    val payloadRaw: String,
)

/**
 * Downlink repository: pulls official alerts from the backend and returns only the
 * ones whose signature verifies against the backend pubkey. Forged or altered
 * records are dropped (and logged), never surfaced.
 *
 * Pure of Android UI; uses [BackendClient] (HTTP) + [OfficialRecordVerifier] (crypto),
 * both headless-testable. The backend is optional — any failure returns a failed
 * Result and the mesh keeps working.
 */
class AlertsRepository(
    private val client: BackendClient,
    private val officialChannels: List<String> = OFFICIAL_CHANNELS,
) {
    private val tag = "guacamaya.backend"

    suspend fun fetchVerifiedAlerts(sinceMs: Long = 0): Result<List<OfficialAlert>> {
        val pubkey = client.pubkey()
            ?: return Result.failure(IllegalStateException("backend pubkey unavailable"))

        val verified = mutableListOf<OfficialAlert>()
        var dropped = 0
        for (channel in officialChannels) {
            val records = try {
                client.records(channel, sinceMs)
            } catch (e: Exception) {
                Log.w(tag, "fetch $channel failed: ${e.message}")
                continue
            }
            for (rec in records) {
                if (OfficialRecordVerifier.verify(rec, pubkey)) {
                    verified += OfficialAlert(rec.id, rec.channel, rec.timestamp, rec.payloadRaw)
                } else {
                    dropped++
                    Log.w(tag, "DROP unverifiable official record id=${rec.id} channel=${rec.channel}")
                }
            }
        }
        verified.sortByDescending { it.timestamp }
        Log.i(tag, "alerts: ${verified.size} verified, $dropped dropped")
        return Result.success(verified)
    }

    companion object {
        /** Channels the operator signs; the phone only ever reads these. */
        val OFFICIAL_CHANNELS = listOf("alertas", "refugios", "ayuda-medica")
    }
}
