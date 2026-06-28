package com.infraspine.callsync.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.infraspine.callsync.data.prefs.SecureSettingsStore
import com.infraspine.callsync.domain.util.NetworkDiagnostics

/**
 * Runs after [CallEndedReceiver] detects a PHONE_STATE → IDLE transition.
 * The 20-second initial delay (set in [SyncScheduler.enqueueCallEndedCheck])
 * gives the CallLog provider time to persist the finished call row before the
 * real sync worker reads it.
 *
 * This worker's only job is to enqueue [AutoSyncWorker] (ON_DEMAND). The sync
 * worker itself determines — via the cursor and check-existing endpoint — whether
 * there is genuinely new data to upload. We do NOT attempt to pre-check the
 * cursor here: that logic is fragile (race between cursor advance and provider
 * write timing) and was the root cause of missed syncs on the second call.
 *
 * The primary call-detection mechanism is [CallLogChangeJobService] (content-URI
 * trigger via JobScheduler). This worker is a secondary path for the [PHONE_STATE]
 * broadcast that some OEM ROMs deliver inconsistently.
 */
class CallEndedSyncWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG) ||
            !hasPermission(Manifest.permission.READ_PHONE_STATE)
        ) {
            NetworkDiagnostics.logCallLogSyncSkipped("call-ended sync skipped: missing call-log/phone-state permission")
            return Result.success()
        }

        val settingsStore = SecureSettingsStore(appContext)
        if (!settingsStore.autoSyncEnabled || !settingsStore.hasValidSession()) {
            NetworkDiagnostics.logCallLogSyncSkipped("call-ended sync skipped: auto-sync off or no valid session")
            return Result.success()
        }

        NetworkDiagnostics.logCallLogSyncSkipped("call-ended sync: queuing on-demand sync after call")
        SyncScheduler.enqueueOneTime(
            context = appContext,
            wifiOnly = settingsStore.syncOnWifiOnly,
            delaySeconds = 0L,
            replaceExisting = false  // KEEP: don't interrupt a sync already in flight
        )
        return Result.success()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val UNIQUE_WORK_NAME = "call_ended_sync_verification_work"
    }
}
