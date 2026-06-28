package com.infraspine.callsync.sync

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.infraspine.callsync.CallSyncApplication
import com.infraspine.callsync.R
import com.infraspine.callsync.data.repository.CallLogSyncTrigger
import com.infraspine.callsync.data.repository.SyncProgress
import com.infraspine.callsync.data.repository.SyncResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AutoSyncWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = appContext as CallSyncApplication
        val container = app.container
        val recordingRepository = container.recordingRepository
        val syncRepository = container.syncRepository

        // Show initial foreground notification so Android lets us run
        setForeground(buildForegroundInfo(null))

        // 1. Scan for new files first, otherwise we only sync what was found while app was open
        recordingRepository.scanAndMatch()

        var workerResult: Result = Result.success()

        coroutineScope {
            // Collect progress and update notification in parallel
            val progressJob = launch {
                syncRepository.syncProgress.collectLatest { progress ->
                    setForeground(buildForegroundInfo(progress))
                }
            }

            val syncResult = syncRepository.syncPending(CallLogSyncTrigger.PERIODIC)
            
            workerResult = when (syncResult) {
                is SyncResult.Completed,
                SyncResult.NothingToSync -> Result.success()

                SyncResult.NetworkUnavailable,
                SyncResult.WifiRequired -> Result.retry()

                SyncResult.ApiNotConfigured -> Result.success()

                SyncResult.AuthRequired -> {
                    val reloggedIn = runCatching { container.authRepository.autoReLogin() }.getOrElse { false }
                    if (reloggedIn) {
                        // Retry once with new token
                        when (syncRepository.syncPending(CallLogSyncTrigger.PERIODIC)) {
                            is SyncResult.Completed,
                            SyncResult.NothingToSync -> Result.success()
                            SyncResult.NetworkUnavailable,
                            SyncResult.WifiRequired -> Result.retry()
                            else -> Result.success()
                        }
                    } else {
                        Result.success()
                    }
                }
            }

            progressJob.cancel()
        }

        // Dismiss the notification when done
        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID)

        return workerResult
    }

    private fun buildForegroundInfo(progress: SyncProgress?): ForegroundInfo {
        val notification = buildNotification(progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(progress: SyncProgress?): Notification {
        val title = "InfraSpine Sync"
        val text = when {
            progress != null && progress.total > 0 ->
                "Uploading ${progress.current + 1} of ${progress.total} recordings…"
            else -> "Syncing in background…"
        }

        return NotificationCompat.Builder(appContext, SyncNotificationChannel.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(
                progress?.total ?: 0,
                progress?.current ?: 0,
                progress == null
            )
            .build()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "auto_sync_work"
        private const val NOTIFICATION_ID = 1001
    }
}
