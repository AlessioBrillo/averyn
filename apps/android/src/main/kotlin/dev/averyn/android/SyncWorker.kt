package dev.averyn.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.averyn.sync.ActivitySync
import dev.averyn.sync.SyncRunResult
import java.util.concurrent.TimeUnit

private const val SYNC_WORK_NAME = "activity-sync"

/**
 * Asks WorkManager to upload finished activities when the network allows (TDD-0002 §5.7). Safe to call often:
 * a pending run is replaced, and the upload endpoint is idempotent, so a run cut short just starts over.
 */
fun enqueueSync(context: Context) {
    val request =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
    WorkManager.getInstance(context).enqueueUniqueWork(SYNC_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
}

/** Try again later (with backoff) only if something can still succeed without the user: not when sign-in is needed. */
internal fun shouldRetry(result: SyncRunResult): Boolean = result.retryable > 0 && !result.needsLogin

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AverynApplication
        val serverUrl = app.auth.serverUrl ?: return Result.success() // no server configured: nothing to do
        val token =
            try {
                app.auth.freshAccessToken()
            } catch (e: Exception) {
                return Result.retry() // no network or the IdP is unreachable
            }
        val sync = ActivitySync.create(app.store, serverUrl)
        try {
            return if (shouldRetry(sync.syncPending(token))) Result.retry() else Result.success()
        } finally {
            sync.close()
        }
    }
}
