package com.infraspine.callsync.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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

    private const val INTERVAL_MINUTES = 15L

    fun apply(context: Context, autoSyncEnabled: Boolean, wifiOnly: Boolean) {
        val workManager = WorkManager.getInstance(context.applicationContext)

        if (!autoSyncEnabled) {
            workManager.cancelUniqueWork(AutoSyncWorker.UNIQUE_WORK_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            AutoSyncWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
