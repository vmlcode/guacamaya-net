package net.guacamaya.ingest

import net.guacamaya.mesh.MessageDao
import java.io.IOException

/**
 * Drives the data-mule upload loop: pull un-uploaded verified frames in batches,
 * rebuild the 118 B `/ingest` frame, POST them, and mark the batch uploaded.
 *
 * Pure of Android/WorkManager deps so it is unit-testable with a fake [MessageDao]
 * and [IngestApi]. [IngestUploadWorker] is the thin Android wrapper.
 *
 * Bookkeeping rule: mark the whole batch uploaded on any HTTP 2xx. Frames we send
 * already passed our own reject cascade, so the backend returns them as `ingested`
 * or `duplicate` (both = backend holds it); `rejected` is effectively impossible and
 * is surfaced in [UploadSummary] for the caller to log rather than retried forever.
 */
class IngestRepository(
    private val dao: MessageDao,
    private val api: IngestApi,
    private val batchSize: Int = MAX_BATCH,
) {

    enum class Outcome {
        /** Uploaded at least one batch, or there was nothing to do — terminal success. */
        SUCCESS,

        /** Transport/server failure mid-run — caller should retry with backoff. */
        RETRY,
    }

    data class UploadSummary(
        val outcome: Outcome,
        val batches: Int = 0,
        val framesSent: Int = 0,
        val ingested: Int = 0,
        val duplicate: Int = 0,
        val rejected: Int = 0,
    )

    /**
     * Upload all pending frames, oldest first, in batches of [batchSize]. Stops early
     * (returning [Outcome.RETRY]) on the first transport failure; the marked batches
     * persist, so the retry resumes where it left off. [maxBatches] bounds a single
     * run so one worker invocation can't spin unbounded during a BLE storm.
     */
    suspend fun uploadPending(maxBatches: Int = DEFAULT_MAX_BATCHES): UploadSummary {
        var batches = 0
        var framesSent = 0
        var ingested = 0
        var duplicate = 0
        var rejected = 0

        repeat(maxBatches) {
            val batch = dao.selectUploadable(batchSize)
            if (batch.isEmpty()) {
                return UploadSummary(Outcome.SUCCESS, batches, framesSent, ingested, duplicate, rejected)
            }

            val frames = batch.map { IngestFrame.toBase64(it.payloadRaw, it.pubkey, it.sig) }
            val result = try {
                api.upload(frames)
            } catch (_: IOException) {
                return UploadSummary(Outcome.RETRY, batches, framesSent, ingested, duplicate, rejected)
            }
            if (!result.ok) {
                return UploadSummary(Outcome.RETRY, batches, framesSent, ingested, duplicate, rejected)
            }

            dao.markUploaded(batch.map { it.id })
            batches++
            framesSent += frames.size
            ingested += result.ingested
            duplicate += result.duplicate
            rejected += result.rejected
        }

        return UploadSummary(Outcome.SUCCESS, batches, framesSent, ingested, duplicate, rejected)
    }

    companion object {
        /** Server caps the batch at MAX_INGEST_BATCH (default 200). Stay at/under it. */
        const val MAX_BATCH = 200

        /** Bound per-run work; periodic/expedited re-runs drain the rest. */
        const val DEFAULT_MAX_BATCHES = 50
    }
}
