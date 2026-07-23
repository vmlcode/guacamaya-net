package net.guacamaya.ingest

import net.guacamaya.mesh.MessageDao
import java.io.IOException
import java.util.Base64

/**
 * Drives the data-mule upload loop: pull un-uploaded verified frames (SOS, self-cancel
 * control, and Encontrado reports) in batches, rebuild the 118 B `/ingest` frame, POST
 * them, and mark the batch uploaded.
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
        val controlFramesSent: Int = 0,
        val ingested: Int = 0,
        val duplicate: Int = 0,
        val rejected: Int = 0,
        val controlAccepted: Int = 0,
        val controlDuplicate: Int = 0,
        val controlRejected: Int = 0,
        val foundFramesSent: Int = 0,
        val foundAccepted: Int = 0,
        val foundDuplicate: Int = 0,
        val foundRejected: Int = 0,
        /** Backend proximity verdicts for the "Encontrado" frames this run uploaded. */
        val foundResults: List<FoundVerdict> = emptyList(),
        /**
         * Backend-minted 118 B control frames (base64, TTL-stripped) returned across all
         * batches this run — delivery (0x01) / backend-resolve (0x02) the server produced in
         * response to the SOSes we uploaded. The worker re-injects them into the mesh so they
         * reach the victim. Never self-cancel (0x03), so re-injection cannot loop back into the
         * outbound queue. Accumulated across batches so one worker run drains them in one pass.
         */
        val mintedControlFrames: List<String> = emptyList(),
        /** Newest signed CRL carried home by this mule run. */
        val certificateRevocationList: MuleCrl? = null,
    )

    /**
     * Upload all pending SOS frames AND queued self-cancel control frames, oldest first, in
     * batches of [batchSize]. Each POST carries whichever side has work (SOS only, control
     * only, or both); the backend requires at least one non-empty batch, and this loop only
     * POSTs when at least one side is non-empty. Stops early (returning [Outcome.RETRY]) on
     * the first transport failure; the marked batches persist, so the retry resumes where it
     * left off. [maxBatches] bounds a single run so one worker invocation can't spin
     * unbounded during a BLE storm.
     */
    suspend fun uploadPending(maxBatches: Int = DEFAULT_MAX_BATCHES): UploadSummary {
        var batches = 0
        var framesSent = 0
        var controlFramesSent = 0
        var foundFramesSent = 0
        var ingested = 0
        var duplicate = 0
        var rejected = 0
        var controlAccepted = 0
        var controlDuplicate = 0
        var controlRejected = 0
        var foundAccepted = 0
        var foundDuplicate = 0
        var foundRejected = 0
        val foundResults = mutableListOf<FoundVerdict>()
        val minted = mutableListOf<String>()
        var crl: MuleCrl? = null

        fun summary(outcome: Outcome) = UploadSummary(
            outcome = outcome,
            batches = batches,
            framesSent = framesSent,
            controlFramesSent = controlFramesSent,
            foundFramesSent = foundFramesSent,
            ingested = ingested,
            duplicate = duplicate,
            rejected = rejected,
            controlAccepted = controlAccepted,
            controlDuplicate = controlDuplicate,
            controlRejected = controlRejected,
            foundAccepted = foundAccepted,
            foundDuplicate = foundDuplicate,
            foundRejected = foundRejected,
            foundResults = foundResults.toList(),
            mintedControlFrames = minted.toList(),
            certificateRevocationList = crl,
        )

        repeat(maxBatches) {
            val batch = dao.selectUploadable(batchSize)
            val controlBatch = dao.selectUploadableControl(batchSize)
            val foundBatch = dao.selectUploadableFound(batchSize)
            if (batch.isEmpty() && controlBatch.isEmpty() && foundBatch.isEmpty()) {
                return summary(Outcome.SUCCESS)
            }

            val frames = batch.map { IngestFrame.toBase64(it.payloadRaw, it.pubkey, it.sig) }
            // Control/found frames are stored as their canonical 118 B blob, so they
            // only need base64 — same std encoder IngestFrame uses, keeping this
            // class Android-free.
            val controlFrames = controlBatch.map { Base64.getEncoder().encodeToString(it.frame) }
            val foundFrames = foundBatch.map { Base64.getEncoder().encodeToString(it.frame) }
            val result = try {
                api.upload(frames, controlFrames, foundFrames)
            } catch (_: IOException) {
                return summary(Outcome.RETRY)
            }
            if (!result.ok) {
                return summary(Outcome.RETRY)
            }

            dao.markUploaded(batch.map { it.id })
            dao.markControlUploaded(controlBatch.map { it.signatureHex })
            dao.markFoundUploaded(foundBatch.map { it.signatureHex })
            batches++
            framesSent += frames.size
            controlFramesSent += controlFrames.size
            foundFramesSent += foundFrames.size
            ingested += result.ingested
            duplicate += result.duplicate
            rejected += result.rejected
            controlAccepted += result.controlAccepted
            controlDuplicate += result.controlDuplicate
            controlRejected += result.controlRejected
            foundAccepted += result.foundAccepted
            foundDuplicate += result.foundDuplicate
            foundRejected += result.foundRejected
            foundResults += result.foundResults
            minted += result.controlFrames
            result.certificateRevocationList?.let { candidate ->
                if (crl == null || candidate.sequence > crl!!.sequence) crl = candidate
            }
        }

        return summary(Outcome.SUCCESS)
    }

    companion object {
        /** Server caps the batch at MAX_INGEST_BATCH (default 200). Stay at/under it. */
        const val MAX_BATCH = 200

        /** Bound per-run work; periodic/expedited re-runs drain the rest. */
        const val DEFAULT_MAX_BATCHES = 50
    }
}
