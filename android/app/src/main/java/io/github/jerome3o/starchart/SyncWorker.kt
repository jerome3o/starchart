package io.github.jerome3o.starchart

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Uploads unsynced fixes to the server in batches. Runs hourly while the
 * device is linked, plus on demand from the "Sync now" button.
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        val token = Sync.token(context) ?: return Result.success()
        val db = LocationDb(context)

        try {
            while (true) {
                val batch = db.fixesAfter(Sync.lastSyncedId(context), BATCH_SIZE)
                if (batch.isEmpty()) break
                val accepted = Sync.uploadFixes(token, batch)
                Sync.recordProgress(context, batch.last().id, accepted)
            }
        } catch (e: Sync.UnauthorizedException) {
            // Token revoked server-side; unlink so the UI says so.
            Sync.unlink(context)
            return Result.failure()
        } catch (e: Exception) {
            return Result.retry()
        }
        return Result.success()
    }

    companion object {
        private const val BATCH_SIZE = 500
        private const val PERIODIC_WORK = "sync-fixes"

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
