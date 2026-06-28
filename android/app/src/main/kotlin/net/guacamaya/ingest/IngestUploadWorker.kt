package net.guacamaya.ingest

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.guacamaya.BuildConfig
import net.guacamaya.mesh.GuacamayaDatabase
import java.util.concurrent.TimeUnit

/**
 * WorkManager job that flushes collected mesh frames to the backend's `/ingest`
 * when the device has connectivity. WorkManager owns the `CONNECTED` constraint,
 * exponential backoff, and survival across process death, so the data-mule upload
 * is best-effort and self-healing without a hand-rolled connectivity listener.
 *
 * Enqueued as unique work ([enqueue]) so overlapping triggers collapse into one.
 */
class IngestUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val dao = GuacamayaDatabase.get(applicationContext).messageDao()
        val repo = IngestRepository(dao, IngestClient(BuildConfig.INGEST_BASE_URL))

        val summary = repo.uploadPending()
        Log.i(
            TAG,
            "ingest run outcome=${summary.outcome} batches=${summary.batches} " +
                "sent=${summary.framesSent} ingested=${summary.ingested} " +
                "duplicate=${summary.duplicate} rejected=${summary.rejected}",
        )
        if (summary.rejected > 0) {
            Log.w(TAG, "backend rejected ${summary.rejected} locally-verified frames — check wire-format sync")
        }

        return when (summary.outcome) {
            IngestRepository.Outcome.SUCCESS -> Result.success()
            IngestRepository.Outcome.RETRY -> Result.retry()
        }
    }

    companion object {
        private const val TAG = "guacamaya.ingest"
        const val UNIQUE_WORK = "guacamaya-ingest-upload"

        /**
         * Enqueue a one-shot, connectivity-gated upload. [ExistingWorkPolicy.KEEP]
         * means a pending/running job is not disturbed by repeated triggers (e.g.
         * every observe-on). Safe to call liberally — it's cheap and idempotent.
         */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<IngestUploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
        }
    }
}
