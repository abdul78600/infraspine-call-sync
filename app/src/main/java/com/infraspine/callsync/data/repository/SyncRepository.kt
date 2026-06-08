package com.infraspine.callsync.data.repository

import android.content.Context
import com.infraspine.callsync.data.local.dao.RecordingDao
import com.infraspine.callsync.data.prefs.SecureSettingsStore
import com.infraspine.callsync.data.remote.CrmApiFactory
import com.infraspine.callsync.data.remote.DummyCrmUploader
import com.infraspine.callsync.data.remote.RealCrmUploader
import com.infraspine.callsync.data.remote.RecordingUploader
import com.infraspine.callsync.data.remote.UploadOutcome
import com.infraspine.callsync.domain.model.SyncStatus
import com.infraspine.callsync.domain.util.DeviceIdProvider
import com.infraspine.callsync.domain.util.NetworkMonitor

sealed class SyncResult {
    data class Completed(val uploaded: Int, val failed: Int) : SyncResult()
    object NothingToSync : SyncResult()
    object NetworkUnavailable : SyncResult()
    object WifiRequired : SyncResult()
    object ApiNotConfigured : SyncResult()
}

/**
 * Drives the "Sync Now" flow: picks up PENDING (and previously FAILED, for manual retry)
 * recordings and uploads them one at a time, persisting status transitions as it goes
 * so progress survives interruption.
 *
 * Duplicate-upload avoidance is two-layered:
 *  1) [RecordingDao] has a unique index on (fileUri, fileSize) — a recording can only
 *     be tracked once.
 *  2) Only rows in PENDING/FAILED state are picked up here, and a row moves to SYNCED
 *     immediately on success — so a successfully uploaded recording is never re-sent.
 */
class SyncRepository(
    private val context: Context,
    private val dao: RecordingDao,
    private val settingsStore: SecureSettingsStore,
    private val networkMonitor: NetworkMonitor,
    private val apiFactory: CrmApiFactory
) {

    private val dummyUploader: RecordingUploader by lazy { DummyCrmUploader() }
    private val realUploader: RecordingUploader by lazy {
        RealCrmUploader(context.applicationContext, settingsStore) { apiFactory.getService() }
    }

    suspend fun syncPending(): SyncResult {
        if (!settingsStore.isCrmConfigured() && !settingsStore.dummyTestMode) {
            return SyncResult.ApiNotConfigured
        }

        if (!networkMonitor.isConnected()) {
            return SyncResult.NetworkUnavailable
        }

        if (settingsStore.syncOnWifiOnly && !networkMonitor.isOnWifi()) {
            return SyncResult.WifiRequired
        }

        val pending = dao.getByStatus(SyncStatus.PENDING)
        val failed = dao.getByStatus(SyncStatus.FAILED)
        val toUpload = pending + failed

        if (toUpload.isEmpty()) {
            return SyncResult.NothingToSync
        }

        val uploader = if (settingsStore.dummyTestMode) dummyUploader else realUploader
        val deviceId = DeviceIdProvider.getOrCreate(context.applicationContext, settingsStore)

        var uploaded = 0
        var failedCount = 0

        for (recording in toUpload) {
            // Re-check connectivity each iteration; large batches can outlast a Wi-Fi connection.
            if (!networkMonitor.isConnected()) break
            if (settingsStore.syncOnWifiOnly && !networkMonitor.isOnWifi()) break

            when (val outcome = uploader.upload(recording, deviceId)) {
                is UploadOutcome.Success -> {
                    dao.updateSyncResult(
                        id = recording.id,
                        status = SyncStatus.SYNCED,
                        uploadedAt = System.currentTimeMillis(),
                        serverId = outcome.serverRecordingId,
                        error = null
                    )
                    uploaded++
                }
                is UploadOutcome.Failure -> {
                    dao.updateSyncResult(
                        id = recording.id,
                        status = SyncStatus.FAILED,
                        uploadedAt = recording.uploadedAt,
                        serverId = recording.serverRecordingId,
                        error = outcome.message
                    )
                    failedCount++
                }
            }
        }

        return SyncResult.Completed(uploaded = uploaded, failed = failedCount)
    }
}
