package com.infraspine.callsync.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

/**
 * Enables/disables the periodic [AutoSyncWorker] in response to the "Auto sync" toggle
 * and keeps its network constraint aligned with the "Sync only on Wi-Fi" setting.
 * Call [apply] whenever either setting changes (including on app start).
 */
object SyncScheduler {

    private const val PERIODIC_INTERVAL_MINUTES = 15L
    const val CALL_ENDED_DELAY_SECONDS = 20L
    const val NETWORK_RESTORED_DELAY_SECONDS = 5L

    fun apply(context: Context, autoSyncEnabled: Boolean, wifiOnly: Boolean) {
        val workManager = WorkManager.getInstance(context.applicationContext)

        if (!autoSyncEnabled) {
            workManager.cancelUniqueWork(AutoSyncWorker.UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(AutoSyncWorker.ON_DEMAND_UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(CallEndedSyncWorker.UNIQUE_WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(networkConstraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            AutoSyncWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun enqueueOneTime(
        context: Context,
        wifiOnly: Boolean,
        delaySeconds: Long,
        replaceExisting: Boolean = false
    ) {
        val request = OneTimeWorkRequestBuilder<AutoSyncWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(networkConstraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                AutoSyncWorker.ON_DEMAND_UNIQUE_WORK_NAME,
                if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
    }

    fun enqueueCallEndedCheck(context: Context, delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<CallEndedSyncWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                CallEndedSyncWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
    }

    private fun networkConstraints(wifiOnly: Boolean): Constraints {
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        return Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()
    }
}
