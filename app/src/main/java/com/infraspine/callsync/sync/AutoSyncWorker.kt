package com.infraspine.callsync.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.infraspine.callsync.CallSyncApplication
import com.infraspine.callsync.data.repository.CallLogSyncTrigger
import com.infraspine.callsync.data.repository.SyncResult

/**
 * Periodic background sync, scheduled only while "Auto sync" is enabled in Settings
 * (see [SyncScheduler]). Honors the same Wi-Fi-only / dummy-mode rules as a manual
 * "Sync Now" by delegating to the same [SyncRepository][com.infraspine.callsync.data.repository.SyncRepository].
 */
class AutoSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CallSyncApplication
        val syncRepository = app.container.syncRepository

        return when (syncRepository.syncPending(CallLogSyncTrigger.PERIODIC)) {
            is SyncResult.Completed,
            SyncResult.NothingToSync -> Result.success()

            SyncResult.NetworkUnavailable,
            SyncResult.WifiRequired -> Result.retry()

            SyncResult.ApiNotConfigured,
            SyncResult.AuthRequired -> Result.success() // nothing actionable until configured/logged in
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "auto_sync_work"
    }
}
