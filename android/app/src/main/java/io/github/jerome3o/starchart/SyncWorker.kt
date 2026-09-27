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
 * Catch-up upload of unsynced fixes. LocationService pushes new fixes as
 * they arrive; this runs hourly (and on "Sync now") to pick up anything
 * that failed, e.g. fixes recorded while offline.
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result = when (Sync.uploadPending(applicationContext)) {
        Sync.Outcome.OK -> Result.success().also { PhoneCommands.poll(applicationContext) }
        Sync.Outcome.UNAUTHORIZED -> Result.failure()
        Sync.Outcome.FAILED -> Result.retry()
    }

    companion object {
        private const val PERIODIC_WORK = "sync-fixes"
        const val SYNC_NOW_WORK = "sync-now"

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
                .enqueueUniqueWork(SYNC_NOW_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
