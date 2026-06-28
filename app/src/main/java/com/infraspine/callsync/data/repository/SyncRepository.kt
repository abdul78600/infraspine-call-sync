package com.infraspine.callsync.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.infraspine.callsync.data.local.dao.RecordingDao
import com.infraspine.callsync.data.local.entity.RecordingEntity
import com.infraspine.callsync.data.prefs.CallLogSyncCursor
import com.infraspine.callsync.data.prefs.CallLogSyncStateSnapshot
import com.infraspine.callsync.data.prefs.SecureSettingsStore
import com.infraspine.callsync.data.remote.CallLogCheckItem
import com.infraspine.callsync.data.remote.CallLogExistingCheckRequest
import com.infraspine.callsync.data.remote.CallLogExistingCheckResponse
import com.infraspine.callsync.data.remote.CallLogsSyncRequest
import com.infraspine.callsync.data.remote.CrmApiFactory
import com.infraspine.callsync.data.remote.CrmApiService
import com.infraspine.callsync.data.remote.DummyCrmUploader
import com.infraspine.callsync.data.remote.RecordingCheckItem
import com.infraspine.callsync.data.remote.RecordingExistingCheckRequest
import com.infraspine.callsync.data.remote.RecordingExistingCheckResponse
import com.infraspine.callsync.data.remote.RecordingUploader
import com.infraspine.callsync.data.remote.UploadOutcome
import com.infraspine.callsync.domain.model.CallType
import com.infraspine.callsync.domain.model.SyncStatus
import com.infraspine.callsync.domain.sync.DedupKeyBuilder
import com.infraspine.callsync.domain.util.DeviceIdProvider
import com.infraspine.callsync.domain.util.FileHasher
import com.infraspine.callsync.domain.util.NetworkDiagnostics
import com.infraspine.callsync.domain.util.UploadErrorParser
import com.infraspine.callsync.scan.MobileCallLog
import com.infraspine.callsync.scan.MobileCallLogReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.infraspine.callsync.data.remote.CallLogSyncItem
import com.infraspine.callsync.data.remote.RealCrmUploader
import com.infraspine.callsync.domain.util.NetworkMonitor
import com.infraspine.callsync.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

sealed class SyncResult {
    data class Completed(
        val uploaded: Int,
        val failed: Int,
        val skippedDuplicate: Int,
        val callLogsUploaded: Int,
        val callLogsSkipped: Int,
        val callLogsSkippedDuplicate: Int,
        val callLogsFailed: Int,
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
    data class Available(
        val existingRefs: Set<String>,
        val missingRefs: Set<String>? = null
    ) : ExistenceCheckOutcome()

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
 * Coordinates the full upload pipeline:
 *  1. Scans for recordings in the user-selected folder.
 *  2. Matches them to device call-log entries.
 *  3. Uploads matched recordings to the CRM via [RealCrmUploader] or [DummyCrmUploader].
 *  4. Syncs mobile call logs to the CRM so the backend has a complete record of calls
 *     even for those without audio files.
 *  5. Scopes sync state (cursors) to a "Sync Profile" key (server + account + device)
 *     so switching servers or accounts starts a fresh history without
 *     affecting other profiles.
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
    private val hasCallLogPermission: () -> Boolean,
    private val syncHistoryDao: com.infraspine.callsync.data.local.dao.SyncHistoryDao? = null,
    private val authRepository: AuthRepository? = null
) {
    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress

    @Volatile
    private var lastAppOpenSyncAttemptAt: Long = 0L

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callLogSyncGate = SingleFlightCallLogSyncGate()

    private val dummyUploader: RecordingUploader by lazy { DummyCrmUploader() }
    private val realUploader: RecordingUploader by lazy {
        RealCrmUploader(context.applicationContext, settingsStore) { apiFactory.getService() }
    }

    suspend fun syncPending(trigger: CallLogSyncTrigger = CallLogSyncTrigger.PERIODIC): SyncResult {
        try {
            if (settingsStore.crmServerUrl.isNullOrBlank()) {
                return SyncResult.ApiNotConfigured
            }
            if (!settingsStore.hasValidSession()) {
                return SyncResult.AuthRequired
            }

            if (!networkMonitor.isConnected()) {
                return SyncResult.NetworkUnavailable
            }

            val deviceId = DeviceIdProvider.getOrCreate(context.applicationContext, settingsStore)
            val uploader = if (settingsStore.dummyTestMode) dummyUploader else realUploader
            val api = apiFactory.getService()

            val recordingResult = syncRecordings(api, uploader, deviceId)
            if (recordingResult.authRequired) {
                return SyncResult.AuthRequired
            }

            val callLogResult = syncCallLogs(api, deviceId, trigger)
            if (callLogResult.authRequired) {
                return SyncResult.AuthRequired
            }

            val didWork = recordingResult.uploaded > 0 ||
                recordingResult.failed > 0 ||
                recordingResult.skippedDuplicate > 0 ||
                callLogResult.uploaded > 0 ||
                callLogResult.skipped > 0 ||
                callLogResult.skippedDuplicate > 0 ||
                callLogResult.failed > 0 ||
                !callLogResult.errorMessage.isNullOrBlank()

            if (didWork) {
                syncHistoryDao?.let {
                    val entry = com.infraspine.callsync.data.local.entity.SyncHistoryEntity(
                        syncedAt = System.currentTimeMillis(),
                        recordingsUploaded = recordingResult.uploaded,
                        recordingsFailed = recordingResult.failed,
                        recordingsSkipped = recordingResult.skippedDuplicate,
                        callLogsUploaded = callLogResult.uploaded,
                        callLogsFailed = callLogResult.failed
                    )
                    it.insert(entry)
                    it.trimToLatest100()
                }
            }

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
            } else if (recordingResult.wifiBlocked) {
                SyncResult.WifiRequired
            } else {
                SyncResult.NothingToSync
            }
        } finally {
            _syncProgress.value = null
        }
    }

    suspend fun syncCallLogsOnly(trigger: CallLogSyncTrigger = CallLogSyncTrigger.MANUAL): CallLogSyncStats {
        try {
            val deviceId = DeviceIdProvider.getOrCreate(context.applicationContext, settingsStore)
            val api = apiFactory.getService()
            return syncCallLogs(api, deviceId, trigger)
        } finally {
            // syncCallLogs sets _syncProgress through several phases but never clears it
            // on its own. Without this, the app-open / login call-log sync (which goes
            // through here, not syncPending) leaves the progress card stuck forever on
            // "Fetching call logs from device…" even after the sync completes.
            _syncProgress.value = null
        }
    }

    suspend fun refreshCallLogSyncState() {
        val deviceId = DeviceIdProvider.getOrCreate(context.applicationContext, settingsStore)
        val api = apiFactory.getService()
        if (api == null) return
        when (val outcome = fetchCallLogSyncState(api, deviceId)) {
            is SyncStateOutcome.Available -> {
                val profileKey = resolveProfileKey(deviceId, outcome.state.serverInstanceId)
                settingsStore.setCallLogSyncState(profileKey, outcome.state)
            }
            SyncStateOutcome.Unauthorized -> handleUnauthorizedSync()
            is SyncStateOutcome.Error -> NetworkDiagnostics.logConnectionFailure(outcome.message)
        }
    }

    suspend fun startAuthenticatedSession() {
        activateAuthenticatedSession()
        runCatching {
            refreshCallLogSyncState()
            syncCallLogsOnly(CallLogSyncTrigger.LOGIN)
        }.onFailure {
            if (it is CancellationException) throw it
            NetworkDiagnostics.logUnexpectedFailure("login call-log sync", it)
        }
    }

    fun startAuthenticatedSessionInBackground() {
        activateAuthenticatedSession()
        repositoryScope.launch {
            runCatching {
                refreshCallLogSyncState()
                syncCallLogsOnly(CallLogSyncTrigger.LOGIN)
            }.onFailure {
                if (it is CancellationException) throw it
                NetworkDiagnostics.logUnexpectedFailure("background login call-log sync", it)
            }
        }
    }

    private fun activateAuthenticatedSession() {
        settingsStore.autoSyncEnabled = true
        SyncScheduler.apply(
            context = context.applicationContext,
            autoSyncEnabled = true,
            wifiOnly = settingsStore.syncOnWifiOnly
        )
    }

    suspend fun syncCallLogsOnAppOpen() {
        if (!settingsStore.hasValidSession()) return

        val now = System.currentTimeMillis()
        if (now - lastAppOpenSyncAttemptAt < APP_OPEN_SYNC_THROTTLE_MS) return
        lastAppOpenSyncAttemptAt = now

        runCatching {
            refreshCallLogSyncState()
            syncCallLogsOnly(CallLogSyncTrigger.APP_OPEN)
        }.onFailure {
            NetworkDiagnostics.logUnexpectedFailure("app-open call-log sync", it)
        }
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
        val authRequired: Boolean = false,
        val wifiBlocked: Boolean = false
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
        if (settingsStore.syncOnWifiOnly && !networkMonitor.isOnWifi()) {
            return RecordingSyncStats(wifiBlocked = true)
        }

        var skippedDuplicate = 0

        if (api != null && !settingsStore.dummyTestMode) {
            _syncProgress.value = SyncProgress(current = 0, total = 0, phase = "checking_recordings")
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
                    handleUnauthorizedSync()
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

        _syncProgress.value = SyncProgress(current = 0, total = candidates.size, phase = "recordings")

        for ((index, recording) in candidates.withIndex()) {
            _syncProgress.value = SyncProgress(current = index, total = candidates.size, phase = "recordings")

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
                    handleUnauthorizedSync()
                    return RecordingSyncStats(uploaded = uploaded, failed = failedCount, skippedDuplicate = skippedDuplicate, authRequired = true)
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

            existing += parseCheckExistingRefs(response.body()?.existing)
        }

        return ExistenceCheckOutcome.Available(existing)
    }

    // ---------------------------------------------------------------------
    // Call logs
    // ---------------------------------------------------------------------

    private suspend fun syncCallLogs(
        api: CrmApiService?,
        deviceId: String,
        trigger: CallLogSyncTrigger
    ): CallLogSyncStats {
        if (settingsStore.dummyTestMode || !settingsStore.isCrmConfigured() || !hasCallLogPermission()) {
            val reason = when {
                settingsStore.dummyTestMode -> "dummy/test mode is enabled"
                !settingsStore.isCrmConfigured() -> "CRM server URL or token missing"
                !hasCallLogPermission() -> "READ_CALL_LOG permission denied"
                else -> "precondition failed"
            }
            NetworkDiagnostics.logCallLogSyncSkipped(reason)
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
        if (!callLogSyncGate.tryAcquire()) {
            NetworkDiagnostics.logCallLogSyncSkipped("call log sync already in progress")
            return CallLogSyncStats()
        }

        try {
            var profileKey = settingsStore.activeSyncProfileKey(deviceId)
            var syncStateError: String? = null
            if (!settingsStore.isCallLogResetRequested(profileKey)) {
                _syncProgress.value = SyncProgress(current = 0, total = 0, phase = "fetching_call_logs")
                val stateOutcome = withTimeoutOrNull(CHECK_EXISTING_TIMEOUT_MS) {
                    fetchCallLogSyncState(api, deviceId)
                }

                when (stateOutcome) {
                    is SyncStateOutcome.Available -> {
                        profileKey = resolveProfileKey(deviceId, stateOutcome.state.serverInstanceId, profileKey)
                        settingsStore.setCallLogSyncState(profileKey, stateOutcome.state)
                    }
                    SyncStateOutcome.Unauthorized -> {
                        handleUnauthorizedSync()
                        return CallLogSyncStats(authRequired = true)
                    }
                    is SyncStateOutcome.Error -> syncStateError = stateOutcome.message
                    null -> syncStateError = "Fetching sync state timed out"
                }
            }

            val localCursor = settingsStore.callLogCursor(profileKey)
            if (!syncStateError.isNullOrBlank() && localCursor.isEmpty()) {
                NetworkDiagnostics.logConnectionFailure(syncStateError)
                return CallLogSyncStats(errorMessage = syncStateError)
            }
            val resetRequested = settingsStore.isCallLogResetRequested(profileKey)
            val remoteState = settingsStore.callLogSyncState(profileKey)
            val cursorDecision = CallLogSyncSupport.effectiveCursor(
                local = localCursor,
                remote = remoteState,
                resetRequested = resetRequested,
                initialSyncMode = settingsStore.callLogInitialSyncMode,
                latestLocalLog = if (localCursor.isEmpty() && remoteState.isEmpty() && !resetRequested) {
                    callLogReader.loadLatestCallLog()
                } else {
                    null
                },
                syncedAt = System.currentTimeMillis()
            )
            cursorDecision.seededCursor?.let {
                updateCallLogCursor(profileKey, it)
                NetworkDiagnostics.logCallLogSyncSkipped("server has no checkpoint; seeded call-log sync from now")
            }
            val queryCursor = cursorDecision.queryCursor
            NetworkDiagnostics.logCallLogSyncCursor(
                localStartedAt = localCursor.lastSyncedCallStartedAt,
                localAndroidId = localCursor.lastSyncedAndroidCallLogId,
                remoteStartedAt = remoteState.latestCallStartedAt,
                remoteAndroidId = remoteState.latestAndroidCallLogId,
                effectiveStartedAt = queryCursor.lastSyncedCallStartedAt,
                effectiveAndroidId = queryCursor.lastSyncedAndroidCallLogId
            )
            val syncStartedAt = System.currentTimeMillis()
            val shouldRunRecovery = CallLogSyncSupport.shouldRunRecovery(
                trigger = trigger,
                lastRecoveryAt = settingsStore.lastCallLogRecoveryAt(profileKey),
                now = syncStartedAt,
                recoveryIntervalMs = CALL_LOG_RECOVERY_INTERVAL_MS
            )
            // Progress already set to "fetching_call_logs" before state fetch, 
            // but we keep it here as well for clarity if state fetch was skipped.
            if (_syncProgress.value?.phase != "fetching_call_logs") {
                _syncProgress.value = SyncProgress(current = 0, total = 0, phase = "fetching_call_logs")
            }
            val cursorFetched = callLogReader.loadAfterCursor(
                lastSyncedAndroidCallLogId = queryCursor.lastSyncedAndroidCallLogId,
                lastSyncedCallStartedAt = queryCursor.lastSyncedCallStartedAt
            )
            val recoveryFetched = if (shouldRunRecovery) {
                val recoveryWindowStartedAt = (syncStartedAt - CALL_LOG_RECOVERY_WINDOW_MS).coerceAtLeast(0L)
                callLogReader.loadSince(recoveryWindowStartedAt)
            } else {
                emptyList()
            }
            val fetched = CallLogSyncSupport.mergeFetchedLogs(cursorFetched, recoveryFetched)
            val cursorAdvanceLog = cursorFetched.maxByOrNull { it.id }
            if (fetched.isEmpty()) {
                persistCallLogCursorAfterSuccessfulPass(
                    profileKey = profileKey,
                    localCursor = localCursor,
                    queryCursor = queryCursor,
                    cursorAdvanceLog = cursorAdvanceLog,
                    syncedAt = syncStartedAt
                )
                markCallLogRecoveryCompleted(profileKey, shouldRunRecovery, syncStartedAt)
                NetworkDiagnostics.logCallLogSync(
                    totalFetched = 0,
                    uploaded = 0,
                    skipped = 0,
                    failed = 0,
                    lastSyncedCallLogId = settingsStore.callLogCursor(profileKey).lastSyncedAndroidCallLogId
                )
                return CallLogSyncStats()
            }

            val filteredLogs = filterUploadableCallLogs(fetched)
            val uploadable = filteredLogs.uploadable
            val skipped = filteredLogs.skippedCount
            if (filteredLogs.skippedCount > 0) {
                NetworkDiagnostics.logCallLogSkipReasons(
                    totalFetched = fetched.size,
                    skipped = filteredLogs.skippedCount,
                    summary = filteredLogs.reasonSummary()
                )
            }

            if (uploadable.isEmpty()) {
                persistCallLogCursorAfterSuccessfulPass(
                    profileKey = profileKey,
                    localCursor = localCursor,
                    queryCursor = queryCursor,
                    cursorAdvanceLog = cursorAdvanceLog,
                    syncedAt = syncStartedAt
                )
                markCallLogRecoveryCompleted(profileKey, shouldRunRecovery, syncStartedAt)
                NetworkDiagnostics.logCallLogSync(
                    totalFetched = fetched.size,
                    uploaded = 0,
                    skipped = skipped,
                    failed = 0,
                    lastSyncedCallLogId = settingsStore.callLogCursor(profileKey).lastSyncedAndroidCallLogId
                )
                return CallLogSyncStats(skipped = skipped)
            }

            var missing = uploadable
            var skippedDuplicate = 0

            if (!settingsStore.dummyTestMode) {
                _syncProgress.value = SyncProgress(current = 0, total = 0, phase = "checking_call_logs")
                val outcome = withTimeoutOrNull(CHECK_EXISTING_TIMEOUT_MS) {
                    checkExistingCallLogs(api, deviceId, uploadable)
                } ?: run {
                    // Treat timeout as a transient server error — do NOT fall back to
                    // uploading everything, because that causes an infinite loop:
                    // recovery scans keep returning the same already-synced records,
                    // and every cycle re-uploads them when check-existing times out.
                    NetworkDiagnostics.logConnectionFailure("check-existing timed out after ${CHECK_EXISTING_TIMEOUT_MS / 1000}s — skipping cycle")
                    ExistenceCheckOutcome.Error("check-existing timed out after ${CHECK_EXISTING_TIMEOUT_MS / 1000}s")
                }
                when (outcome) {
                    is ExistenceCheckOutcome.Available -> {
                        val missingRefs = outcome.missingRefs
                            ?: return CallLogSyncStats(
                                skipped = skipped,
                                errorMessage = "Call log check-existing response did not identify missing records"
                            )
                        val existing = uploadable.filter { it.id.toString() in outcome.existingRefs }
                        val stillMissing = uploadable.filter { it.id.toString() in missingRefs }
                        skippedDuplicate = existing.size
                        missing = stillMissing
                    }
                    ExistenceCheckOutcome.NotSupported -> {
                        // Endpoint not deployed yet — upload everything, as before.
                    }
                    ExistenceCheckOutcome.Unauthorized -> {
                        handleUnauthorizedSync()
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
            }

            if (missing.isEmpty()) {
                persistCallLogCursorAfterSuccessfulPass(
                    profileKey = profileKey,
                    localCursor = localCursor,
                    queryCursor = queryCursor,
                    cursorAdvanceLog = cursorAdvanceLog,
                    syncedAt = syncStartedAt
                )
                markCallLogRecoveryCompleted(profileKey, shouldRunRecovery, syncStartedAt)
                NetworkDiagnostics.logCallLogSync(
                    totalFetched = fetched.size,
                    uploaded = 0,
                    skipped = skipped,
                    failed = 0,
                    lastSyncedCallLogId = settingsStore.callLogCursor(profileKey).lastSyncedAndroidCallLogId
                )
                return CallLogSyncStats(skipped = skipped, skippedDuplicate = skippedDuplicate)
            }

            val batches = CallLogSyncSupport.syncBatches(missing, CALL_LOG_SYNC_BATCH_SIZE)
            NetworkDiagnostics.logCallLogSyncRequest(
                totalFetched = fetched.size,
                uploadable = missing.size,
                skipped = skipped,
                sample = sampleCallLogPayload(missing.take(3), deviceId)
            )

            var uploaded = 0
            var serverDuplicateCount = 0

            _syncProgress.value = SyncProgress(current = 0, total = missing.size, phase = "call_logs")

            for ((batchIndex, batch) in batches.withIndex()) {
                _syncProgress.value = SyncProgress(current = uploaded, total = missing.size, phase = "call_logs")

                val payload = batch.map { it.toSyncItem(deviceId) }
                NetworkDiagnostics.logCallLogSyncBatchRequest(
                    batchIndex = batchIndex + 1,
                    batchCount = batches.size,
                    batchSize = payload.size,
                    totalMissing = missing.size,
                    sample = sampleCallLogSyncItems(payload.take(3))
                )

                val response = try {
                    api.syncCallLogs(CallLogsSyncRequest(logs = payload))
                } catch (io: IOException) {
                    val message = NetworkDiagnostics.classify(throwable = io)
                    val failed = CallLogSyncSupport.failedBatchCount(batch.size)
                    NetworkDiagnostics.logConnectionFailure(message)
                    NetworkDiagnostics.logCallLogSync(
                        fetched.size,
                        uploaded,
                        skipped + skippedDuplicate + serverDuplicateCount,
                        failed,
                        queryCursor.lastSyncedAndroidCallLogId
                    )
                    return CallLogSyncStats(
                        uploaded = uploaded,
                        skipped = skipped,
                        skippedDuplicate = skippedDuplicate + serverDuplicateCount,
                        failed = failed,
                        errorMessage = message
                    )
                }

                if (!response.isSuccessful) {
                    val rawBody = runCatching { response.errorBody()?.string() }.getOrNull()
                    val failed = CallLogSyncSupport.failedBatchCount(batch.size)
                    NetworkDiagnostics.logCallLogSyncResponse(response.code(), rawBody)
                    val serverMessage = UploadErrorParser.extractMessage(rawBody)
                    val requestError = NetworkDiagnostics.classify(httpCode = response.code(), serverMessage = serverMessage)
                    NetworkDiagnostics.logConnectionFailure(requestError)

                    if (response.code() == 401) {
                        handleUnauthorizedSync()
                        NetworkDiagnostics.logCallLogSync(
                            fetched.size,
                            uploaded,
                            skipped + skippedDuplicate + serverDuplicateCount,
                            failed,
                            queryCursor.lastSyncedAndroidCallLogId
                        )
                        return CallLogSyncStats(
                            uploaded = uploaded,
                            skipped = skipped,
                            skippedDuplicate = skippedDuplicate + serverDuplicateCount,
                            failed = failed,
                            authRequired = true,
                            errorMessage = serverMessage
                        )
                    }

                    if (response.code() == 403) {
                        val permissionMessage = serverMessage ?: "Permission denied: crm.callLogs.create is required"
                        NetworkDiagnostics.logCallLogSync(
                            fetched.size,
                            uploaded,
                            skipped + skippedDuplicate + serverDuplicateCount,
                            failed,
                            queryCursor.lastSyncedAndroidCallLogId
                        )
                        return CallLogSyncStats(
                            uploaded = uploaded,
                            skipped = skipped,
                            skippedDuplicate = skippedDuplicate + serverDuplicateCount,
                            failed = failed,
                            errorMessage = permissionMessage
                        )
                    }

                    if (response.code() == 429) {
                        NetworkDiagnostics.logCallLogSyncSkipped("call log sync rate limited by server")
                    }

                    NetworkDiagnostics.logCallLogSync(
                        fetched.size,
                        uploaded,
                        skipped + skippedDuplicate + serverDuplicateCount,
                        failed,
                        queryCursor.lastSyncedAndroidCallLogId
                    )
                    return CallLogSyncStats(
                        uploaded = uploaded,
                        skipped = skipped,
                        skippedDuplicate = skippedDuplicate + serverDuplicateCount,
                        failed = failed,
                        errorMessage = serverMessage ?: requestError
                    )
                }

                val body = response.body()
                val rejected = CallLogSyncSupport.rejectedCount(body)
                if (body?.success == false || rejected > 0) {
                    val counts = CallLogSyncSupport.successCounts(body, fallbackBatchSize = 0)
                    uploaded += counts.uploaded
                    serverDuplicateCount += counts.duplicateCount
                    val message = body?.message
                        ?: "Server rejected $rejected call log(s) in batch ${batchIndex + 1}/${batches.size}"
                    val failed = CallLogSyncSupport.failedBatchCount(batch.size, rejected)
                    NetworkDiagnostics.logCallLogSyncResponse(
                        200,
                        "success=${body?.success} invalid=${body?.invalid ?: 0} failed=${body?.failed ?: 0} message=$message"
                    )
                    NetworkDiagnostics.logConnectionFailure(message)
                    NetworkDiagnostics.logCallLogSync(
                        fetched.size,
                        uploaded,
                        skipped + skippedDuplicate + serverDuplicateCount,
                        failed,
                        queryCursor.lastSyncedAndroidCallLogId
                    )
                    return CallLogSyncStats(
                        uploaded = uploaded,
                        skipped = skipped,
                        skippedDuplicate = skippedDuplicate + serverDuplicateCount,
                        failed = failed,
                        errorMessage = message
                    )
                }

                val counts = CallLogSyncSupport.successCounts(body, fallbackBatchSize = batch.size)
                uploaded += counts.uploaded
                serverDuplicateCount += counts.duplicateCount
            }

            val totalSkippedDuplicate = skippedDuplicate + serverDuplicateCount
            persistCallLogCursorAfterSuccessfulPass(
                profileKey = profileKey,
                localCursor = localCursor,
                queryCursor = queryCursor,
                cursorAdvanceLog = cursorAdvanceLog,
                syncedAt = System.currentTimeMillis()
            )
            markCallLogRecoveryCompleted(profileKey, shouldRunRecovery, System.currentTimeMillis())
            val finalCursor = settingsStore.callLogCursor(profileKey)
            NetworkDiagnostics.logCallLogSync(
                fetched.size,
                uploaded,
                skipped + totalSkippedDuplicate,
                0,
                finalCursor.lastSyncedAndroidCallLogId
            )
            return CallLogSyncStats(uploaded = uploaded, skipped = skipped, skippedDuplicate = totalSkippedDuplicate)
        } finally {
            callLogSyncGate.release()
        }
    }

    private suspend fun checkExistingCallLogs(
        api: CrmApiService,
        deviceId: String,
        uploadable: List<MobileCallLog>
    ): ExistenceCheckOutcome {
        val items = uploadable.map { it.toCheckItem() }
        val chunks = items.chunked(CHECK_EXISTING_BATCH_SIZE)

        // Set progress early so the user sees a determinate bar if there are many logs,
        // or at least knows we are at 0/1 for a single chunk.
        _syncProgress.value = SyncProgress(current = 0, total = chunks.size, phase = "checking_call_logs")

        val existing = mutableSetOf<String>()
        val missing = mutableSetOf<String>()
        for ((chunkIndex, chunk) in chunks.withIndex()) {
            val request = CallLogExistingCheckRequest(deviceId = deviceId, records = chunk)
            NetworkDiagnostics.logCallLogCheckExistingRequest(
                totalRecords = items.size,
                chunkIndex = chunkIndex + 1,
                chunkSize = chunk.size,
                wrapperKey = "records",
                deviceIdPresent = deviceId.isNotBlank(),
                firstItemJson = chunk.firstOrNull()?.let { GSON.toJson(it) } ?: "<empty>"
            )
            val response = try {
                api.checkExistingCallLogs(request)
            } catch (e: CancellationException) {
                throw e
            } catch (throwable: Throwable) {
                return ExistenceCheckOutcome.Error(NetworkDiagnostics.classify(throwable = throwable))
            }

            if (!response.isSuccessful) {
                val rawBody = runCatching { response.errorBody()?.string() }.getOrNull()
                NetworkDiagnostics.logCallLogCheckExistingResponse(response.code(), rawBody)
                when (response.code()) {
                    404, 501 -> return ExistenceCheckOutcome.NotSupported
                    401 -> return ExistenceCheckOutcome.Unauthorized
                    403 -> return ExistenceCheckOutcome.Error(
                        UploadErrorParser.extractMessage(rawBody)
                            ?: "Permission denied: crm.callLogs.create is required"
                    )
                    else -> {
                        val serverMessage = UploadErrorParser.extractMessage(rawBody)
                        return ExistenceCheckOutcome.Error(
                            NetworkDiagnostics.classify(
                                httpCode = response.code(),
                                serverMessage = serverMessage ?: "call log check-existing failed for chunk ${chunkIndex + 1}/${chunks.size}"
                            )
                        )
                    }
                }
            }

            val body = response.body()
            existing += parseCheckExistingRefs(body?.existing)
            missing += parseCheckExistingRefs(body?.missing)

            val chunkRefs = chunk.mapTo(mutableSetOf()) { it.clientRef }
            val accountedRefs = existing.intersect(chunkRefs) + missing.intersect(chunkRefs)
            val unaccountedRefs = chunkRefs - accountedRefs
            if (unaccountedRefs.isNotEmpty()) {
                return ExistenceCheckOutcome.Error(
                    "Call log check-existing response omitted ${unaccountedRefs.size} records in chunk ${chunkIndex + 1}/${chunks.size}"
                )
            }

            // Update progress after each chunk
            _syncProgress.value = SyncProgress(
                current = chunkIndex + 1,
                total = chunks.size,
                phase = "checking_call_logs"
            )
        }

        return ExistenceCheckOutcome.Available(existingRefs = existing, missingRefs = missing)
    }

    private suspend fun fetchCallLogSyncState(
        api: CrmApiService?,
        deviceId: String
    ): SyncStateOutcome {
        if (api == null) return SyncStateOutcome.Error("CRM server URL is not configured")

        val statusResponse = try {
            api.getCallLogSyncStatus(deviceId)
        } catch (io: IOException) {
            null
        }
        if (statusResponse != null) {
            if (statusResponse.isSuccessful) {
                val body = statusResponse.body()
                val externalCallId = body?.latestExternalCallId.toStringValueOrNull()
                return SyncStateOutcome.Available(
                    CallLogSyncStateSnapshot(
                        latestExternalCallId = externalCallId,
                        latestCallStartedAt = body?.latestCallStartedAt.toEpochMillisOrZero(),
                        latestAndroidCallLogId = externalCallId?.toLongOrNull() ?: 0L,
                        latestSyncedAt = body?.latestSyncedAt.toEpochMillisOrZero(),
                        totalLogs = body?.totalLogs ?: 0,
                        serverInstanceId = body?.instanceId
                    )
                )
            }
            if (statusResponse.code() == 401) return SyncStateOutcome.Unauthorized
            if (statusResponse.code() == 403) {
                val rawBody = runCatching { statusResponse.errorBody()?.string() }.getOrNull()
                return SyncStateOutcome.Error(
                    UploadErrorParser.extractMessage(rawBody)
                        ?: "Permission denied: crm.callLogs.create is required"
                )
            }
            if (statusResponse.code() != 404 && statusResponse.code() != 501) {
                val rawBody = runCatching { statusResponse.errorBody()?.string() }.getOrNull()
                val serverMessage = UploadErrorParser.extractMessage(rawBody)
                return SyncStateOutcome.Error(
                    NetworkDiagnostics.classify(httpCode = statusResponse.code(), serverMessage = serverMessage)
                )
            }
        }

        val response = try {
            api.getCallLogSyncState(deviceId)
        } catch (io: IOException) {
            return SyncStateOutcome.Error(NetworkDiagnostics.classify(throwable = io))
        }

        if (!response.isSuccessful) {
            if (response.code() == 401) return SyncStateOutcome.Unauthorized
            if (response.code() == 403) {
                val rawBody = runCatching { response.errorBody()?.string() }.getOrNull()
                return SyncStateOutcome.Error(
                    UploadErrorParser.extractMessage(rawBody)
                        ?: "Permission denied: crm.callLogs.create is required"
                )
            }
            val rawBody = runCatching { response.errorBody()?.string() }.getOrNull()
            val serverMessage = UploadErrorParser.extractMessage(rawBody)
            return SyncStateOutcome.Error(
                NetworkDiagnostics.classify(httpCode = response.code(), serverMessage = serverMessage)
            )
        }

        val body = response.body()
        return SyncStateOutcome.Available(
            CallLogSyncStateSnapshot(
                latestExternalCallId = body?.latestAndroidCallLogId.toStringValueOrNull(),
                latestCallStartedAt = body?.latestCallStartedAt.toEpochMillisOrZero(),
                latestAndroidCallLogId = body?.latestAndroidCallLogId.toLongOrZero(),
                totalLogs = body?.totalLogs ?: 0
            )
        )
    }

    private fun resolveProfileKey(
        deviceId: String,
        serverInstanceId: String?,
        previousProfileKey: String? = null
    ): String {
        settingsStore.bindServerInstanceId(settingsStore.crmServerUrl, serverInstanceId)
        val resolvedProfileKey = settingsStore.activeSyncProfileKey(deviceId, serverInstanceId)
        if (previousProfileKey != null) {
            settingsStore.migrateCallLogProfileState(previousProfileKey, resolvedProfileKey)
        }
        return resolvedProfileKey
    }

    private fun updateCallLogCursor(profileKey: String, log: MobileCallLog, syncedAt: Long) {
        updateCallLogCursor(profileKey, cursorFromLog(log, syncedAt))
    }

    private fun updateCallLogCursor(profileKey: String, cursor: CallLogSyncCursor) {
        settingsStore.setCallLogCursor(profileKey, cursor)
        settingsStore.setCallLogResetRequested(profileKey, false)
    }

    private fun filterUploadableCallLogs(logs: List<MobileCallLog>): FilteredCallLogs {
        val uploadable = mutableListOf<MobileCallLog>()
        val skippedReasons = linkedMapOf(
            CallLogSkipReason.UNKNOWN_CALL_TYPE to 0,
            CallLogSkipReason.MISSING_STARTED_AT to 0,
            CallLogSkipReason.INVALID_PHONE_NUMBER to 0
        )

        for (log in logs) {
            val skipReason = log.skipReason()
            if (skipReason == null) {
                uploadable += log
            } else {
                skippedReasons[skipReason] = (skippedReasons[skipReason] ?: 0) + 1
            }
        }

        return FilteredCallLogs(
            uploadable = uploadable,
            skippedReasons = skippedReasons.filterValues { it > 0 }
        )
    }

    private fun persistCallLogCursorAfterSuccessfulPass(
        profileKey: String,
        localCursor: CallLogSyncCursor,
        queryCursor: CallLogSyncCursor,
        cursorAdvanceLog: MobileCallLog?,
        syncedAt: Long
    ) {
        when {
            cursorAdvanceLog != null -> updateCallLogCursor(profileKey, cursorAdvanceLog, syncedAt)
            queryCursor != localCursor && !queryCursor.isEmpty() ->
                updateCallLogCursor(profileKey, queryCursor.copy(lastCallLogSyncAt = syncedAt))
        }
    }

    private fun markCallLogRecoveryCompleted(profileKey: String, recoveryExecuted: Boolean, completedAt: Long) {
        if (recoveryExecuted) {
            settingsStore.setLastCallLogRecoveryAt(profileKey, completedAt)
        }
    }

    private fun cursorFromLog(log: MobileCallLog, syncedAt: Long): CallLogSyncCursor =
        CallLogSyncCursor(
            lastSyncedCallStartedAt = log.startedAt,
            lastSyncedAndroidCallLogId = log.id,
            lastCallLogSyncAt = syncedAt
        )

    private fun MobileCallLog.skipReason(): CallLogSkipReason? =
        when {
            callType == CallType.UNKNOWN -> CallLogSkipReason.UNKNOWN_CALL_TYPE
            startedAt <= 0L -> CallLogSkipReason.MISSING_STARTED_AT
            normalizedPhoneNumber() == null -> CallLogSkipReason.INVALID_PHONE_NUMBER
            else -> null
        }

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

    private fun sampleCallLogPayload(logs: List<MobileCallLog>, deviceId: String): String =
        sampleCallLogSyncItems(logs.map { it.toSyncItem(deviceId) })

    private fun sampleCallLogSyncItems(items: List<CallLogSyncItem>): String =
        items.joinToString(prefix = "[", postfix = "]") {
            "{id=${it.externalCallId}, type=${it.callType}, startedAt=${it.callStartedAt}, hasPhone=${it.phoneNumber.isNotBlank()}}"
        }

    private fun MobileCallLog.normalizedPhoneNumber(): String? =
        DedupKeyBuilder.normalizePhoneNumber(phoneNumber)

    private fun parseCheckExistingRefs(element: JsonElement?): Set<String> {
        if (element == null || !element.isJsonArray) return emptySet()

        return element.asJsonArray.mapNotNull { item ->
            when {
                item == null || item.isJsonNull -> null
                item.isJsonPrimitive -> item.asJsonPrimitive.asStringValue()
                item.isJsonObject -> item.asJsonObject.extractRefValue()
                else -> null
            }
        }.toSet()
    }

    private fun JsonObject.extractRefValue(): String? =
        CHECK_EXISTING_REF_KEYS.firstNotNullOfOrNull { key ->
            get(key)?.takeIf { !it.isJsonNull }?.let { value ->
                when {
                    value.isJsonPrimitive -> value.asJsonPrimitive.asStringValue()
                    else -> null
                }
            }
        }

    private fun JsonPrimitive.asStringValue(): String? =
        when {
            isString -> asString.takeIf { it.isNotBlank() }
            isNumber -> asNumber.toString()
            else -> null
        }

    private data class FilteredCallLogs(
        val uploadable: List<MobileCallLog>,
        val skippedReasons: Map<CallLogSkipReason, Int>
    ) {
        val skippedCount: Int
            get() = skippedReasons.values.sum()

        fun reasonSummary(): String =
            skippedReasons.entries.joinToString(", ") { (reason, count) ->
                "${reason.name.lowercase()}:$count"
            }
    }

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

    private fun JsonElement?.toStringValueOrNull(): String? {
        val element = this ?: return null
        return when {
            element is JsonPrimitive && element.isString -> element.asString.takeIf { it.isNotBlank() }
            element is JsonPrimitive && element.isNumber -> element.asLong.toString()
            else -> null
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

    /**
     * Called when the server rejects a sync request with 401. First attempts a
     * silent re-login using the password saved at login — if the token simply
     * expired and the credentials are still valid, the next request (or the
     * remainder of this app-open pass) succeeds with the fresh token and the
     * user never notices. If the re-login fails (password changed or revoked),
     * the session is genuinely dead, so we log out: the next session check
     * (MainActivity start destination / syncPending) routes the user to login.
     */
    private suspend fun handleUnauthorizedSync() {
        val repo = authRepository
        if (repo == null) {
            NetworkDiagnostics.logCallLogSyncSkipped("server returned 401 but no auth repository is wired")
            return
        }
        val reloggedIn = try {
            repo.autoReLogin()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            false
        }
        if (reloggedIn) {
            NetworkDiagnostics.logCallLogSyncSkipped("server returned 401 — silent re-login succeeded; next sync uses the fresh token")
        } else {
            NetworkDiagnostics.logCallLogSyncSkipped("server returned 401 — silent re-login failed; logging out")
            repo.logout()
        }
    }

    companion object {
        val ISO_MILLIS_UTC: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** Max records per check-existing and sync request. */
        private const val CHECK_EXISTING_BATCH_SIZE = 200
        private const val CALL_LOG_SYNC_BATCH_SIZE = 200

        /** Total time budget for the check-existing round-trip(s). On timeout we
         *  return an Error (skip the cycle) — NOT NotSupported — so we never
         *  fall back to uploading everything. Uploading without deduplication
         *  causes an infinite re-upload loop via the recovery scan. */
        private const val CHECK_EXISTING_TIMEOUT_MS = 45_000L
        private const val APP_OPEN_SYNC_THROTTLE_MS = 15_000L
        private const val CALL_LOG_RECOVERY_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
        private const val CALL_LOG_RECOVERY_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private val CHECK_EXISTING_REF_KEYS = listOf("clientRef", "externalCallId", "id", "_id")
        private val GSON = Gson()
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

internal enum class CallLogSkipReason {
    UNKNOWN_CALL_TYPE,
    MISSING_STARTED_AT,
    INVALID_PHONE_NUMBER
}
