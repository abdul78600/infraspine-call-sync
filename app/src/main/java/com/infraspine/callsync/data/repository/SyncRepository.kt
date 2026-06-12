package com.infraspine.callsync.data.repository

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.infraspine.callsync.data.prefs.CallLogSyncCursor
import com.infraspine.callsync.data.prefs.CallLogSyncStateSnapshot
import com.infraspine.callsync.data.local.dao.RecordingDao
import com.infraspine.callsync.data.local.entity.RecordingEntity
import com.infraspine.callsync.data.prefs.SecureSettingsStore
import com.infraspine.callsync.data.remote.CallLogCheckItem
import com.infraspine.callsync.data.remote.CallLogExistingCheckRequest
import com.infraspine.callsync.data.remote.CrmApiFactory
import com.infraspine.callsync.data.remote.CallLogSyncItem
import com.infraspine.callsync.data.remote.CallLogsSyncRequest
import com.infraspine.callsync.data.remote.CrmApiService
import com.infraspine.callsync.data.remote.DummyCrmUploader
import com.infraspine.callsync.data.remote.RealCrmUploader
import com.infraspine.callsync.data.remote.RecordingCheckItem
import com.infraspine.callsync.data.remote.RecordingExistingCheckRequest
import com.infraspine.callsync.data.remote.RecordingUploader
import com.infraspine.callsync.data.remote.UploadOutcome
import com.infraspine.callsync.domain.model.CallType
import com.infraspine.callsync.domain.model.SyncStatus
import com.infraspine.callsync.domain.sync.DedupKeyBuilder
import com.infraspine.callsync.domain.util.DeviceIdProvider
import com.infraspine.callsync.domain.util.FileHasher
import com.infraspine.callsync.domain.util.NetworkDiagnostics
import com.infraspine.callsync.domain.util.NetworkMonitor
import com.infraspine.callsync.domain.util.UploadErrorParser
import com.infraspine.callsync.scan.MobileCallLog
import com.infraspine.callsync.scan.MobileCallLogReader
import com.infraspine.callsync.sync.SyncScheduler
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

sealed class SyncResult {
    data class Completed(
        val uploaded: Int,
        val failed: Int,
        val skippedDuplicate: Int = 0,
        val callLogsUploaded: Int = 0,
        val callLogsSkipped: Int = 0,
        val callLogsSkippedDuplicate: Int = 0,
        val callLogsFailed: Int = 0,
        val callLogsError: String? = null
    ) : SyncResult()
    object NothingToSync : SyncResult()
    object NetworkUnavailable : SyncResult()
    object WifiRequired : SyncResult()
    object ApiNotConfigured : SyncResult()
    object AuthRequired : SyncResult()
}

/**
 * Result of asking the server which of a batch of candidate records it already
 * has, via a check-existing endpoint.
 */
private sealed class ExistenceCheckOutcome {
    /** [existingRefs] are the `clientRef`s the server already has. */
    data class Available(val existingRefs: Set<String>) : ExistenceCheckOutcome()
    /** Endpoint not deployed yet (404/501) — treat all candidates as missing. */
    object NotSupported : ExistenceCheckOutcome()
    object Unauthorized : ExistenceCheckOutcome()
    /** Network/server error — caller should skip this category for this cycle. */
    data class Error(val message: String) : ExistenceCheckOutcome()
}

private sealed class SyncStateOutcome {
    data class Available(val state: CallLogSyncStateSnapshot) : SyncStateOutcome()
    object Unauthorized : SyncStateOutcome()
    data class Error(val message: String) : SyncStateOutcome()
}

/**
 * Drives the "Sync Now" flow.
 *
 * Sync identity is scoped to a "sync profile" — the combination of CRM server
 * URL, logged-in user, and device id (see [SecureSettingsStore.activeSyncProfileKey]).
 * Switching servers or accounts starts a fresh incremental-sync history without
 * affecting other profiles.
 *
 * Before uploading, both recordings and call logs are checked against the
 * server via `check-existing` endpoints so only genuinely missing records are
 * sent — see [checkExistingRecordings] and [syncCallLogs]. If those endpoints
 * aren't available yet (404/501), the app falls back to its previous
 * upload-everything behavior so older backends keep working.
 *
 * Duplicate-upload avoidance is layered:
 *  1) [RecordingDao] has a unique index on (fileUri, fileSize) — a recording can only
 *     be tracked once locally.
 *  2) check-existing marks server-known records as SYNCED without re-uploading.
 *  3) Only rows in PENDING/FAILED state are picked up here, and a row moves to SYNCED
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
        if (settingsStore.crmServerUrl.isNullOrBlank()) {
            return SyncResult.ApiNotConfigured
        }
        if (!settingsStore.hasValidSession()) {
            return SyncResult.AuthRequired
        }

        if (!networkMonitor.isConnected()) {
            return SyncResult.NetworkUnavailable
        }

        if (settingsStore.syncOnWifiOnly && !networkMonitor.isOnWifi()) {
            return SyncResult.WifiRequired
        }

        val deviceId = DeviceIdProvider.getOrCreate(context.applicationContext, settingsStore)
        val uploader = if (settingsStore.dummyTestMode) dummyUploader else realUploader
        val api = apiFactory.getService()

        val recordingResult = syncRecordings(api, uploader, deviceId)
        if (recordingResult.authRequired) {
            return SyncResult.AuthRequired
        }

        val callLogResult = syncCallLogs(api, deviceId)
        if (callLogResult.authRequired) {
            return SyncResult.AuthRequired
        }

        val didWork = recordingResult.uploaded > 0 ||
            recordingResult.failed > 0 ||
            recordingResult.skippedDuplicate > 0 ||
            callLogResult.uploaded > 0 ||
            callLogResult.skipped > 0 ||
            callLogResult.skippedDuplicate > 0 ||
            callLogResult.failed > 0

        return if (didWork) {
            SyncResult.Completed(
                uploaded = recordingResult.uploaded,
                failed = recordingResult.failed,
                skippedDuplicate = recordingResult.skippedDuplicate,
                callLogsUploaded = callLogResult.uploaded,
                callLogsSkipped = callLogResult.skipped,
                callLogsSkippedDuplicate = callLogResult.skippedDuplicate,
                callLogsFailed = callLogResult.failed,
                callLogsError = callLogResult.errorMessage
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
        return syncCallLogs(apiFactory.getService(), deviceId)
    }

    suspend fun refreshCallLogSyncState() {
        if (!settingsStore.isCrmConfigured()) return
        if (!networkMonitor.isConnected()) return

        val deviceId = DeviceIdProvider.getOrCreate(context.applicationContext, settingsStore)
        val profileKey = settingsStore.activeSyncProfileKey(deviceId)
        when (val outcome = fetchCallLogSyncState(apiFactory.getService(), deviceId)) {
            is SyncStateOutcome.Available -> settingsStore.setCallLogSyncState(profileKey, outcome.state)
            SyncStateOutcome.Unauthorized -> clearExpiredSession()
            is SyncStateOutcome.Error -> NetworkDiagnostics.logConnectionFailure(outcome.message)
        }
    }

    suspend fun startAuthenticatedSession() {
        settingsStore.autoSyncEnabled = true
        SyncScheduler.apply(
            context = context.applicationContext,
            autoSyncEnabled = true,
            wifiOnly = settingsStore.syncOnWifiOnly
        )
        refreshCallLogSyncState()
        syncCallLogsOnly()
    }

    /**
     * Re-queues all SYNCED recordings as PENDING and resets the call-log cursor for
     * the active sync profile, so the next sync re-checks full history against the
     * server. Records the server already has are marked SYNCED again via
     * check-existing without re-uploading; server-side dedup is the backstop.
     */
    suspend fun resetSyncHistory() {
        val deviceId = DeviceIdProvider.getOrCreate(context.applicationContext, settingsStore)
        val profileKey = settingsStore.activeSyncProfileKey(deviceId)
        settingsStore.resetCallLogCursor(profileKey)
        settingsStore.clearCallLogSyncState(profileKey)
        settingsStore.setCallLogResetRequested(profileKey, true)
        dao.resetSyncedToPending()
    }

    // ---------------------------------------------------------------------
    // Recordings
    // ---------------------------------------------------------------------

    private data class RecordingSyncStats(
        val uploaded: Int = 0,
        val failed: Int = 0,
        val skippedDuplicate: Int = 0,
        val authRequired: Boolean = false
    )

    private suspend fun syncRecordings(
        api: CrmApiService?,
        uploader: RecordingUploader,
        deviceId: String
    ): RecordingSyncStats {
        val pending = dao.getByStatus(SyncStatus.PENDING)
        val failed = dao.getByStatus(SyncStatus.FAILED)
        var candidates = pending + failed
        if (candidates.isEmpty()) return RecordingSyncStats()

        var skippedDuplicate = 0

        if (api != null && !settingsStore.dummyTestMode) {
            // Compute missing file hashes before building the check-existing request.
            candidates = candidates.map { recording ->
                if (recording.fileHash != null) return@map recording
                val hash = FileHasher.sha256(context.applicationContext, recording.fileUri)
                if (hash != null) {
                    dao.updateFileHash(recording.id, hash)
                    recording.copy(fileHash = hash)
                } else {
                    recording
                }
            }

            when (val outcome = checkExistingRecordings(api, deviceId, candidates)) {
                is ExistenceCheckOutcome.Available -> {
                    val (existing, missing) = candidates.partition { it.id.toString() in outcome.existingRefs }
                    for (recording in existing) {
                        dao.updateSyncResult(
                            id = recording.id,
                            status = SyncStatus.SYNCED,
                            uploadedAt = System.currentTimeMillis(),
                            serverId = recording.serverRecordingId,
                            error = null
                        )
                    }
                    skippedDuplicate = existing.size
                    candidates = missing
                }
                ExistenceCheckOutcome.NotSupported -> {
                    // Endpoint not deployed yet — upload everything, as before.
                }
                ExistenceCheckOutcome.Unauthorized -> {
                    clearExpiredSession()
                    return RecordingSyncStats(authRequired = true)
                }
                is ExistenceCheckOutcome.Error -> {
                    NetworkDiagnostics.logConnectionFailure(outcome.message)
                    // Skip uploads this cycle; candidates remain PENDING/FAILED for retry.
                    return RecordingSyncStats()
                }
            }
        }

        var uploaded = 0
        var failedCount = 0

        for (recording in candidates) {
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
                UploadOutcome.Unauthorized -> {
                    clearExpiredSession()
                    return RecordingSyncStats(
                        uploaded = uploaded,
                        failed = failedCount,
                        skippedDuplicate = skippedDuplicate,
                        authRequired = true
                    )
                }
            }
        }

        return RecordingSyncStats(uploaded = uploaded, failed = failedCount, skippedDuplicate = skippedDuplicate)
    }

    private suspend fun checkExistingRecordings(
        api: CrmApiService,
        deviceId: String,
        candidates: List<RecordingEntity>
    ): ExistenceCheckOutcome {
        val items = candidates.mapNotNull { recording ->
            val phone = DedupKeyBuilder.normalizePhoneNumber(recording.phoneNumber) ?: return@mapNotNull null
            val startedAt = recording.callStartedAt ?: return@mapNotNull null
            RecordingCheckItem(
                clientRef = recording.id.toString(),
                phoneNumber = phone,
                callStartedAt = startedAt.toIso8601(),
                durationSeconds = recording.durationSeconds,
                fileSize = recording.fileSize,
                fileHash = recording.fileHash
            )
        }
        if (items.isEmpty()) return ExistenceCheckOutcome.Available(emptySet())

        val existing = mutableSetOf<String>()
        for (chunk in items.chunked(CHECK_EXISTING_BATCH_SIZE)) {
            val response = try {
                api.checkExistingRecordings(RecordingExistingCheckRequest(deviceId = deviceId, records = chunk))
            } catch (io: IOException) {
                return ExistenceCheckOutcome.Error(NetworkDiagnostics.classify(throwable = io))
            }

            if (!response.isSuccessful) {
                when (response.code()) {
                    404, 501 -> return ExistenceCheckOutcome.NotSupported
                    401 -> return ExistenceCheckOutcome.Unauthorized
                    else -> {
                        val rawBody = runCatching { response.errorBody()?.string() }.getOrNull()
                        val serverMessage = UploadErrorParser.extractMessage(rawBody)
                        return ExistenceCheckOutcome.Error(
                            NetworkDiagnostics.classify(httpCode = response.code(), serverMessage = serverMessage)
                        )
                    }
                }
            }

            response.body()?.existing?.let { existing += it }
        }

        return ExistenceCheckOutcome.Available(existing)
    }

    // ---------------------------------------------------------------------
    // Call logs
    // ---------------------------------------------------------------------

    private suspend fun syncCallLogs(api: CrmApiService?, deviceId: String): CallLogSyncStats {
        if (settingsStore.dummyTestMode || !settingsStore.isCrmConfigured() || !hasCallLogPermission()) {
            NetworkDiagnostics.logCallLogSync(
                totalFetched = 0,
                uploaded = 0,
                skipped = 0,
                failed = 0,
                lastSyncedCallLogId = 0L
            )
            return CallLogSyncStats()
        }

        if (api == null) return CallLogSyncStats()

        val profileKey = settingsStore.activeSyncProfileKey(deviceId)
        val localCursor = settingsStore.callLogCursor(profileKey)
        if (!settingsStore.isCallLogResetRequested(profileKey)) {
            when (val outcome = fetchCallLogSyncState(api, deviceId)) {
                is SyncStateOutcome.Available -> settingsStore.setCallLogSyncState(profileKey, outcome.state)
                SyncStateOutcome.Unauthorized -> {
                    clearExpiredSession()
                    return CallLogSyncStats(authRequired = true)
                }
                is SyncStateOutcome.Error -> {
                    if (localCursor.isEmpty()) {
                        NetworkDiagnostics.logConnectionFailure(outcome.message)
                        return CallLogSyncStats(errorMessage = outcome.message)
                    }
                }
            }
        }

        val queryCursor = effectiveCallLogCursor(profileKey)
        val fetched = callLogReader.loadAfterCursor(
            lastSyncedAndroidCallLogId = queryCursor.lastSyncedAndroidCallLogId,
            lastSyncedCallStartedAt = queryCursor.lastSyncedCallStartedAt
        )
        if (fetched.isEmpty()) {
            if (queryCursor != localCursor && !queryCursor.isEmpty()) {
                updateCallLogCursor(
                    profileKey,
                    queryCursor.copy(lastCallLogSyncAt = System.currentTimeMillis())
                )
            }
            NetworkDiagnostics.logCallLogSync(0, 0, 0, 0, queryCursor.lastSyncedAndroidCallLogId)
            return CallLogSyncStats()
        }

        val uploadable = fetched.filter { it.isUploadable() }
        val skipped = fetched.size - uploadable.size
        val maxFetchedLog = fetched.maxByOrNull { it.id } ?: return CallLogSyncStats()

        if (uploadable.isEmpty()) {
            updateCallLogCursor(profileKey, maxFetchedLog, System.currentTimeMillis())
            NetworkDiagnostics.logCallLogSync(fetched.size, 0, skipped, 0, maxFetchedLog.id)
            return CallLogSyncStats(skipped = skipped)
        }

        var missing = uploadable
        var skippedDuplicate = 0

        when (val outcome = checkExistingCallLogs(api, deviceId, uploadable)) {
            is ExistenceCheckOutcome.Available -> {
                val (existing, stillMissing) = uploadable.partition { it.id.toString() in outcome.existingRefs }
                skippedDuplicate = existing.size
                missing = stillMissing
            }
            ExistenceCheckOutcome.NotSupported -> {
                // Endpoint not deployed yet — upload everything, as before.
            }
            ExistenceCheckOutcome.Unauthorized -> {
                clearExpiredSession()
                NetworkDiagnostics.logCallLogSync(
                    fetched.size,
                    0,
                    skipped,
                    uploadable.size,
                    queryCursor.lastSyncedAndroidCallLogId
                )
                return CallLogSyncStats(skipped = skipped, failed = uploadable.size, authRequired = true)
            }
            is ExistenceCheckOutcome.Error -> {
                NetworkDiagnostics.logConnectionFailure(outcome.message)
                // Don't advance the cursor; retry this whole batch next sync.
                return CallLogSyncStats(skipped = skipped, errorMessage = outcome.message)
            }
        }

        if (missing.isEmpty()) {
            // Everything in this batch is accounted for: either non-uploadable or
            // already on the server.
            updateCallLogCursor(profileKey, maxFetchedLog, System.currentTimeMillis())
            NetworkDiagnostics.logCallLogSync(fetched.size, 0, skipped, 0, maxFetchedLog.id)
            return CallLogSyncStats(skipped = skipped, skippedDuplicate = skippedDuplicate)
        }

        val payload = missing.map { it.toSyncItem(deviceId) }
        NetworkDiagnostics.logCallLogSyncRequest(
            totalFetched = fetched.size,
            uploadable = payload.size,
            skipped = skipped,
            sample = payload.take(3).joinToString(prefix = "[", postfix = "]") {
                "{id=${it.externalCallId}, type=${it.callType}, startedAt=${it.callStartedAt}, hasPhone=${it.phoneNumber.isNotBlank()}}"
            }
        )

        return try {
            val response = api.syncCallLogs(CallLogsSyncRequest(logs = payload))

            if (response.isSuccessful) {
                val body = response.body()
                val uploaded = body?.insertedCount ?: body?.uploaded ?: missing.size
                val serverDuplicateCount = body?.duplicateCount ?: body?.skipped ?: 0
                val totalSkippedDuplicate = skippedDuplicate + serverDuplicateCount
                updateCallLogCursor(
                    profileKey = profileKey,
                    cursor = responseCursor(body, missing.maxByOrNull { it.id } ?: maxFetchedLog)
                        ?: cursorFromLog(maxFetchedLog, System.currentTimeMillis())
                )
                val finalCursor = settingsStore.callLogCursor(profileKey)
                NetworkDiagnostics.logCallLogSync(
                    fetched.size,
                    uploaded,
                    skipped + totalSkippedDuplicate,
                    0,
                    finalCursor.lastSyncedAndroidCallLogId
                )
                CallLogSyncStats(uploaded = uploaded, skipped = skipped, skippedDuplicate = totalSkippedDuplicate)
            } else {
                val rawBody = runCatching { response.errorBody()?.string() }.getOrNull()
                NetworkDiagnostics.logCallLogSyncResponse(response.code(), rawBody)
                val serverMessage = UploadErrorParser.extractMessage(rawBody)
                NetworkDiagnostics.logConnectionFailure(
                    NetworkDiagnostics.classify(httpCode = response.code(), serverMessage = serverMessage)
                )

                if (response.code() == 401) {
                    clearExpiredSession()
                    NetworkDiagnostics.logCallLogSync(
                        fetched.size,
                        0,
                        skipped,
                        missing.size,
                        queryCursor.lastSyncedAndroidCallLogId
                    )
                    return CallLogSyncStats(
                        skipped = skipped,
                        skippedDuplicate = skippedDuplicate,
                        failed = missing.size,
                        authRequired = true,
                        errorMessage = serverMessage
                    )
                }

                if (response.code() == 400) {
                    return syncCallLogsIndividually(
                        api = api,
                        payload = payload,
                        totalFetched = fetched.size,
                        alreadySkipped = skipped,
                        skippedDuplicate = skippedDuplicate,
                        previousCursor = queryCursor,
                        maxFetchedLog = maxFetchedLog,
                        profileKey = profileKey,
                        batchErrorMessage = serverMessage
                    )
                }

                NetworkDiagnostics.logCallLogSync(
                    fetched.size,
                    0,
                    skipped,
                    missing.size,
                    queryCursor.lastSyncedAndroidCallLogId
                )
                CallLogSyncStats(
                    skipped = skipped,
                    skippedDuplicate = skippedDuplicate,
                    failed = missing.size,
                    errorMessage = serverMessage
                )
            }
        } catch (io: IOException) {
            val message = NetworkDiagnostics.classify(throwable = io)
            NetworkDiagnostics.logConnectionFailure(message)
            NetworkDiagnostics.logCallLogSync(
                fetched.size,
                0,
                skipped,
                missing.size,
                queryCursor.lastSyncedAndroidCallLogId
            )
            CallLogSyncStats(skipped = skipped, skippedDuplicate = skippedDuplicate, failed = missing.size, errorMessage = message)
        }
    }

    /**
     * On HTTP 400 from the batch endpoint, retries each item individually. The
     * call-log cursor advances to just before the first hard-failed item (so
     * nothing before a failure is ever skipped on the next sync), or to
     * [maxFetchedLog] if every item succeeded.
     */
    private suspend fun syncCallLogsIndividually(
        api: CrmApiService,
        payload: List<CallLogSyncItem>,
        totalFetched: Int,
        alreadySkipped: Int,
        skippedDuplicate: Int,
        previousCursor: CallLogSyncCursor,
        maxFetchedLog: MobileCallLog,
        profileKey: String,
        batchErrorMessage: String?
    ): CallLogSyncStats {
        var uploaded = 0
        var failed = 0
        var firstError: String? = null
        val hardFailedIds = mutableListOf<Long>()

        for (item in payload) {
            val itemId = item.externalCallId.toLongOrNull()
            val response = try {
                api.syncCallLogs(CallLogsSyncRequest(logs = listOf(item)))
            } catch (error: IOException) {
                failed++
                if (itemId != null) hardFailedIds += itemId
                if (firstError == null) firstError = NetworkDiagnostics.classify(throwable = error)
                continue
            }

            if (response.isSuccessful) {
                uploaded++
            } else {
                failed++
                val rawBody = runCatching { response.errorBody()?.string() }.getOrNull()
                NetworkDiagnostics.logCallLogSyncResponse(response.code(), rawBody)
                val serverMessage = UploadErrorParser.extractMessage(rawBody)
                if (response.code() == 401) {
                    clearExpiredSession()
                    NetworkDiagnostics.logCallLogSync(
                        totalFetched = totalFetched,
                        uploaded = uploaded,
                        skipped = alreadySkipped,
                        failed = failed + (payload.size - uploaded - failed),
                        lastSyncedCallLogId = previousCursor.lastSyncedAndroidCallLogId
                    )
                    return CallLogSyncStats(
                        uploaded = uploaded,
                        skipped = alreadySkipped,
                        skippedDuplicate = skippedDuplicate,
                        failed = payload.size - uploaded,
                        authRequired = true,
                        errorMessage = serverMessage
                    )
                }
                if (itemId != null) hardFailedIds += itemId
                if (firstError == null) firstError = serverMessage ?: "Server rejected call log ${item.externalCallId}"
            }
        }

        val newCursor = if (hardFailedIds.isNotEmpty()) {
            cursorBeforeFirstFailed(payload, hardFailedIds.minOrNull() ?: 0L, previousCursor)
        } else {
            cursorFromLog(maxFetchedLog, System.currentTimeMillis())
        }
        if (newCursor.lastSyncedAndroidCallLogId > previousCursor.lastSyncedAndroidCallLogId ||
            newCursor.lastSyncedCallStartedAt > previousCursor.lastSyncedCallStartedAt
        ) {
            updateCallLogCursor(profileKey, newCursor)
        }

        NetworkDiagnostics.logCallLogSync(
            totalFetched = totalFetched,
            uploaded = uploaded,
            skipped = alreadySkipped,
            failed = failed,
            lastSyncedCallLogId = newCursor.lastSyncedAndroidCallLogId
        )

        return CallLogSyncStats(
            uploaded = uploaded,
            skipped = alreadySkipped,
            skippedDuplicate = skippedDuplicate,
            failed = failed,
            errorMessage = if (failed > 0) firstError ?: batchErrorMessage else null
        )
    }

    private suspend fun checkExistingCallLogs(
        api: CrmApiService,
        deviceId: String,
        uploadable: List<MobileCallLog>
    ): ExistenceCheckOutcome {
        val items = uploadable.map { it.toCheckItem() }

        val existing = mutableSetOf<String>()
        for (chunk in items.chunked(CHECK_EXISTING_BATCH_SIZE)) {
            val response = try {
                api.checkExistingCallLogs(CallLogExistingCheckRequest(deviceId = deviceId, records = chunk))
            } catch (io: IOException) {
                return ExistenceCheckOutcome.Error(NetworkDiagnostics.classify(throwable = io))
            }

            if (!response.isSuccessful) {
                when (response.code()) {
                    404, 501 -> return ExistenceCheckOutcome.NotSupported
                    401 -> return ExistenceCheckOutcome.Unauthorized
                    else -> {
                        val rawBody = runCatching { response.errorBody()?.string() }.getOrNull()
                        val serverMessage = UploadErrorParser.extractMessage(rawBody)
                        return ExistenceCheckOutcome.Error(
                            NetworkDiagnostics.classify(httpCode = response.code(), serverMessage = serverMessage)
                        )
                    }
                }
            }

            response.body()?.existing?.let { existing += it }
        }

        return ExistenceCheckOutcome.Available(existing)
    }

    private suspend fun fetchCallLogSyncState(
        api: CrmApiService?,
        deviceId: String
    ): SyncStateOutcome {
        if (api == null) return SyncStateOutcome.Error("CRM server URL is not configured")

        val response = try {
            api.getCallLogSyncState(deviceId)
        } catch (io: IOException) {
            return SyncStateOutcome.Error(NetworkDiagnostics.classify(throwable = io))
        }

        if (!response.isSuccessful) {
            if (response.code() == 401) return SyncStateOutcome.Unauthorized
            val rawBody = runCatching { response.errorBody()?.string() }.getOrNull()
            val serverMessage = UploadErrorParser.extractMessage(rawBody)
            return SyncStateOutcome.Error(
                NetworkDiagnostics.classify(httpCode = response.code(), serverMessage = serverMessage)
            )
        }

        val body = response.body()
        return SyncStateOutcome.Available(
            CallLogSyncStateSnapshot(
                latestCallStartedAt = body?.latestCallStartedAt.toEpochMillisOrZero(),
                latestAndroidCallLogId = body?.latestAndroidCallLogId.toLongOrZero(),
                totalLogs = body?.totalLogs ?: 0
            )
        )
    }

    private fun effectiveCallLogCursor(profileKey: String): CallLogSyncCursor {
        if (settingsStore.isCallLogResetRequested(profileKey)) return settingsStore.callLogCursor(profileKey)

        val local = settingsStore.callLogCursor(profileKey)
        val remote = settingsStore.callLogSyncState(profileKey)
        if (remote.isEmpty()) return local
        if (local.isEmpty()) {
            return CallLogSyncCursor(
                lastSyncedCallStartedAt = remote.latestCallStartedAt,
                lastSyncedAndroidCallLogId = remote.latestAndroidCallLogId,
                lastCallLogSyncAt = local.lastCallLogSyncAt
            )
        }

        return if (
            remote.latestAndroidCallLogId > local.lastSyncedAndroidCallLogId ||
            (remote.latestAndroidCallLogId == local.lastSyncedAndroidCallLogId &&
                remote.latestCallStartedAt > local.lastSyncedCallStartedAt)
        ) {
            CallLogSyncCursor(
                lastSyncedCallStartedAt = remote.latestCallStartedAt,
                lastSyncedAndroidCallLogId = remote.latestAndroidCallLogId,
                lastCallLogSyncAt = local.lastCallLogSyncAt
            )
        } else {
            local
        }
    }

    private fun updateCallLogCursor(profileKey: String, log: MobileCallLog, syncedAt: Long) {
        updateCallLogCursor(profileKey, cursorFromLog(log, syncedAt))
    }

    private fun updateCallLogCursor(profileKey: String, cursor: CallLogSyncCursor) {
        settingsStore.setCallLogCursor(profileKey, cursor)
        settingsStore.setCallLogResetRequested(profileKey, false)
    }

    private fun responseCursor(
        response: com.infraspine.callsync.data.remote.CallLogsSyncResponse?,
        fallbackLog: MobileCallLog
    ): CallLogSyncCursor? {
        val responseId = response?.latestServerCallLogId.toLongOrZero()
        val responseStartedAt = response?.latestServerCallStartedAt.toEpochMillisOrZero()
        if (responseId <= 0L && responseStartedAt <= 0L) return null

        return CallLogSyncCursor(
            lastSyncedCallStartedAt = if (responseStartedAt > 0L) responseStartedAt else fallbackLog.startedAt,
            lastSyncedAndroidCallLogId = if (responseId > 0L) responseId else fallbackLog.id,
            lastCallLogSyncAt = System.currentTimeMillis()
        )
    }

    private fun cursorFromLog(log: MobileCallLog, syncedAt: Long): CallLogSyncCursor =
        CallLogSyncCursor(
            lastSyncedCallStartedAt = log.startedAt,
            lastSyncedAndroidCallLogId = log.id,
            lastCallLogSyncAt = syncedAt
        )

    private fun cursorBeforeFirstFailed(
        payload: List<CallLogSyncItem>,
        firstFailedId: Long,
        previousCursor: CallLogSyncCursor
    ): CallLogSyncCursor {
        val lastSuccessful = payload
            .mapNotNull { item ->
                val id = item.externalCallId.toLongOrNull() ?: return@mapNotNull null
                if (id >= firstFailedId) return@mapNotNull null
                id to item.callStartedAt.toEpochMillisOrZero()
            }
            .maxByOrNull { it.first }
            ?: return previousCursor

        return CallLogSyncCursor(
            lastSyncedCallStartedAt = lastSuccessful.second,
            lastSyncedAndroidCallLogId = lastSuccessful.first,
            lastCallLogSyncAt = System.currentTimeMillis()
        )
    }

    private fun MobileCallLog.isUploadable(): Boolean =
        callType != CallType.UNKNOWN && startedAt > 0L && normalizedPhoneNumber() != null

    private fun MobileCallLog.toSyncItem(deviceId: String): CallLogSyncItem =
        CallLogSyncItem(
            externalCallId = id.toString(),
            phoneNumber = normalizedPhoneNumber().orEmpty(),
            callStartedAt = startedAt.toIso8601(),
            durationSeconds = durationSeconds,
            callType = callType.apiValue(),
            deviceId = deviceId
        )

    private fun MobileCallLog.toCheckItem(): CallLogCheckItem =
        CallLogCheckItem(
            clientRef = id.toString(),
            phoneNumber = normalizedPhoneNumber().orEmpty(),
            callType = callType.apiValue(),
            callStartedAt = startedAt.toIso8601(),
            durationSeconds = durationSeconds
        )

    private fun MobileCallLog.normalizedPhoneNumber(): String? =
        DedupKeyBuilder.normalizePhoneNumber(phoneNumber)

    private fun Long.toIso8601(): String =
        ISO_MILLIS_UTC.format(Instant.ofEpochMilli(this))

    private fun JsonElement?.toLongOrZero(): Long {
        val element = this ?: return 0L
        return when {
            element is JsonPrimitive && element.isNumber -> element.asLong
            element is JsonPrimitive && element.isString -> element.asString.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private fun JsonElement?.toEpochMillisOrZero(): Long {
        val element = this ?: return 0L
        if (element is JsonPrimitive && element.isNumber) return element.asLong
        val raw = if (element is JsonPrimitive && element.isString) element.asString else return 0L
        return raw.toLongOrNull() ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)
    }

    private fun String.toEpochMillisOrZero(): Long =
        toLongOrNull() ?: runCatching { Instant.parse(this).toEpochMilli() }.getOrDefault(0L)

    private fun clearExpiredSession() {
        settingsStore.clearAuth()
        settingsStore.autoSyncEnabled = false
        SyncScheduler.apply(
            context = context.applicationContext,
            autoSyncEnabled = false,
            wifiOnly = settingsStore.syncOnWifiOnly
        )
    }

    companion object {
        val ISO_MILLIS_UTC: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** Max records per check-existing request. */
        private const val CHECK_EXISTING_BATCH_SIZE = 200
    }
}

data class CallLogSyncStats(
    val uploaded: Int = 0,
    val skipped: Int = 0,
    val skippedDuplicate: Int = 0,
    val failed: Int = 0,
    val authRequired: Boolean = false,
    val errorMessage: String? = null
)
