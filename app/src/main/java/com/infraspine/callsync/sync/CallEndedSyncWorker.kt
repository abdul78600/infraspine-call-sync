package com.infraspine.callsync.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.infraspine.callsync.data.prefs.CallLogSyncCursor
import com.infraspine.callsync.data.prefs.SecureSettingsStore
import com.infraspine.callsync.domain.util.DeviceIdProvider
import com.infraspine.callsync.domain.util.NetworkDiagnostics
import com.infraspine.callsync.scan.MobileCallLog
import com.infraspine.callsync.scan.MobileCallLogReader
import kotlinx.coroutines.delay

/**
 * Runs after a PHONE_STATE -> IDLE event. It waits for the CallLog provider to
 * expose the finished call and only then queues the real sync worker. This keeps
 * duplicate IDLE broadcasts cheap and avoids declaring success before Android
 * has actually written the call-log row.
 */
class CallEndedSyncWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG) ||
            !hasPermission(Manifest.permission.READ_PHONE_STATE)
        ) {
            NetworkDiagnostics.logCallLogSyncSkipped("call-ended verification stopped because phone/call-log permission is missing")
            return Result.success()
        }

        val settingsStore = SecureSettingsStore(appContext)
        if (!settingsStore.autoSyncEnabled || !settingsStore.hasValidSession()) {
            NetworkDiagnostics.logCallLogSyncSkipped("call-ended verification stopped because auto sync/session is not active")
            return Result.success()
        }

        val reader = MobileCallLogReader(appContext)
        for (attempt in 1..CALL_LOG_PROVIDER_POLL_ATTEMPTS) {
            val latest = reader.loadLatestCallLog()
            if (latest == null) {
                NetworkDiagnostics.logCallLogSyncSkipped(
                    "call-ended verification attempt $attempt: latest call log not visible yet"
                )
                if (attempt < CALL_LOG_PROVIDER_POLL_ATTEMPTS) delay(CALL_LOG_PROVIDER_POLL_DELAY_MS)
                continue
            }

            val deviceId = DeviceIdProvider.getOrCreate(appContext, settingsStore)
            val profileKey = settingsStore.activeSyncProfileKey(deviceId)
            val cursor = settingsStore.callLogCursor(profileKey)
            if (!latest.isNewerThan(cursor)) {
                NetworkDiagnostics.logCallLogSyncSkipped(
                    "call-ended verification attempt $attempt: latest call log is already covered by cursor"
                )
                if (attempt < CALL_LOG_PROVIDER_POLL_ATTEMPTS) delay(CALL_LOG_PROVIDER_POLL_DELAY_MS)
                continue
            }

            NetworkDiagnostics.logCallLogSyncSkipped("call-ended verification found new call log; queued one-time sync")
            SyncScheduler.enqueueOneTime(
                context = appContext,
                wifiOnly = settingsStore.syncOnWifiOnly,
                delaySeconds = 0L,
                replaceExisting = true
            )
            return Result.success()
        }

        NetworkDiagnostics.logCallLogSyncSkipped("call-ended verification gave up waiting for an unprocessed CallLog provider row")
        return Result.success()
    }

    private fun MobileCallLog.isNewerThan(cursor: CallLogSyncCursor): Boolean =
        cursor.isEmpty() ||
            startedAt > cursor.lastSyncedCallStartedAt ||
            (startedAt == cursor.lastSyncedCallStartedAt && id > cursor.lastSyncedAndroidCallLogId)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val UNIQUE_WORK_NAME = "call_ended_sync_verification_work"
        private const val CALL_LOG_PROVIDER_POLL_ATTEMPTS = 6
        private const val CALL_LOG_PROVIDER_POLL_DELAY_MS = 10_000L
    }
}
