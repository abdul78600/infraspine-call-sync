package com.infraspine.callsync.data.repository

import android.content.Context
import com.infraspine.callsync.data.local.dao.RecordingDao
import com.infraspine.callsync.data.prefs.SecureSettingsStore
import com.infraspine.callsync.data.remote.CrmApiFactory
import com.infraspine.callsync.data.remote.CallLogSyncItem
import com.infraspine.callsync.data.remote.CallLogsSyncRequest
import com.infraspine.callsync.data.remote.DummyCrmUploader
import com.infraspine.callsync.data.remote.RealCrmUploader
import com.infraspine.callsync.data.remote.RecordingUploader
import com.infraspine.callsync.data.remote.UploadOutcome
import com.infraspine.callsync.domain.model.CallType
import com.infraspine.callsync.domain.model.SyncStatus
import com.infraspine.callsync.domain.util.DeviceIdProvider
import com.infraspine.callsync.domain.util.NetworkDiagnostics
import com.infraspine.callsync.domain.util.NetworkMonitor
import com.infraspine.callsync.domain.util.UploadErrorParser
import com.infraspine.callsync.scan.MobileCallLog
import com.infraspine.callsync.scan.MobileCallLogReader
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

sealed class SyncResult {
    data class Completed(
        val uploaded: Int,
        val failed: Int,
        val callLogsUploaded: Int = 0,
        val callLogsSkipped: Int = 0,
        val callLogsFailed: Int = 0
    ) : SyncResult()
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
    private val apiFactory: CrmApiFactory,
    private val callLogReader: MobileCallLogReader,
    private val hasCallLogPermission: () -> Boolean
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

        val callLogResult = syncCallLogs(deviceId)
        val didWork = uploaded > 0 ||
            failedCount > 0 ||
            callLogResult.uploaded > 0 ||
            callLogResult.skipped > 0 ||
            callLogResult.failed > 0 ||
            toUpload.isNotEmpty()

        return if (didWork) {
            SyncResult.Completed(
                uploaded = uploaded,
                failed = failedCount,
                callLogsUploaded = callLogResult.uploaded,
                callLogsSkipped = callLogResult.skipped,
                callLogsFailed = callLogResult.failed
            )
        } else {
            SyncResult.NothingToSync
        }
    }

    suspend fun syncCallLogsOnly(): CallLogSyncStats {
        if (!settingsStore.isCrmConfigured() || settingsStore.dummyTestMode) return CallLogSyncStats()
        if (!networkMonitor.isConnected()) return CallLogSyncStats()
        if (settingsStore.syncOnWifiOnly && !networkMonitor.isOnWifi()) return CallLogSyncStats()

        val deviceId = DeviceIdProvider.getOrCreate(context.applicationContext, settingsStore)
        return syncCallLogs(deviceId)
    }

    private suspend fun syncCallLogs(deviceId: String): CallLogSyncStats {
        if (settingsStore.dummyTestMode || !settingsStore.isCrmConfigured() || !hasCallLogPermission()) {
            NetworkDiagnostics.logCallLogSync(
                totalFetched = 0,
                uploaded = 0,
                skipped = 0,
                failed = 0,
                lastSyncedCallLogId = settingsStore.lastSyncedCallLogId
            )
            return CallLogSyncStats()
        }

        val api = apiFactory.getService() ?: return CallLogSyncStats()
        val lastSyncedId = settingsStore.lastSyncedCallLogId
        val fetched = callLogReader.loadNewerThan(lastSyncedId)
        if (fetched.isEmpty()) {
            NetworkDiagnostics.logCallLogSync(0, 0, 0, 0, lastSyncedId)
            return CallLogSyncStats()
        }

        val uploadable = fetched.filter { it.isUploadable() }
        val skipped = fetched.size - uploadable.size
        val maxFetchedId = fetched.maxOf { it.id }

        if (uploadable.isEmpty()) {
            settingsStore.lastSyncedCallLogId = maxFetchedId
            NetworkDiagnostics.logCallLogSync(fetched.size, 0, skipped, 0, maxFetchedId)
            return CallLogSyncStats(skipped = skipped)
        }

        return try {
            val response = api.syncCallLogs(
                CallLogsSyncRequest(
                    logs = uploadable.map { it.toSyncItem(deviceId) }
                )
            )

            if (response.isSuccessful) {
                settingsStore.lastSyncedCallLogId = maxFetchedId
                val body = response.body()
                val uploaded = body?.uploaded ?: uploadable.size
                val serverSkipped = body?.skipped ?: 0
                val totalSkipped = skipped + serverSkipped
                NetworkDiagnostics.logCallLogSync(fetched.size, uploaded, totalSkipped, 0, maxFetchedId)
                CallLogSyncStats(uploaded = uploaded, skipped = totalSkipped)
            } else {
                val rawBody = runCatching { response.errorBody()?.string() }.getOrNull()
                NetworkDiagnostics.logCallLogSyncResponse(response.code(), rawBody)
                val serverMessage = UploadErrorParser.extractMessage(rawBody)
                NetworkDiagnostics.logConnectionFailure(
                    NetworkDiagnostics.classify(httpCode = response.code(), serverMessage = serverMessage)
                )
                NetworkDiagnostics.logCallLogSync(fetched.size, 0, skipped, uploadable.size, lastSyncedId)
                CallLogSyncStats(skipped = skipped, failed = uploadable.size)
            }
        } catch (io: IOException) {
            val message = NetworkDiagnostics.classify(throwable = io)
            NetworkDiagnostics.logConnectionFailure(message)
            NetworkDiagnostics.logCallLogSync(fetched.size, 0, skipped, uploadable.size, lastSyncedId)
            CallLogSyncStats(skipped = skipped, failed = uploadable.size)
        }
    }

    private fun MobileCallLog.isUploadable(): Boolean =
        callType != CallType.UNKNOWN && startedAt > 0L

    private fun MobileCallLog.toSyncItem(deviceId: String): CallLogSyncItem =
        CallLogSyncItem(
            externalCallId = id.toString(),
            phoneNumber = phoneNumber,
            callStartedAt = startedAt.toIso8601(),
            durationSeconds = durationSeconds,
            callType = callType.apiValue(),
            deviceId = deviceId
        )

    private fun Long.toIso8601(): String =
        ISO_MILLIS_UTC.format(Instant.ofEpochMilli(this))

    companion object {
        val ISO_MILLIS_UTC: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}

data class CallLogSyncStats(
    val uploaded: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0
)
