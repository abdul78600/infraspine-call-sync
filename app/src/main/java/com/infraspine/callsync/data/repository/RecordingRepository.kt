package com.infraspine.callsync.data.repository

import com.infraspine.callsync.data.local.dao.RecordingDao
import com.infraspine.callsync.data.local.entity.RecordingEntity
import com.infraspine.callsync.domain.model.CallType
import com.infraspine.callsync.domain.model.SyncStatus
import com.infraspine.callsync.scan.CallLogMatcher
import com.infraspine.callsync.scan.RecordingFolderManager
import com.infraspine.callsync.scan.RecordingScanner
import kotlinx.coroutines.flow.Flow

sealed class ScanResult {
    data class Success(val newRecordingsFound: Int, val totalScanned: Int) : ScanResult()
    object NoFolderSelected : ScanResult()
    object NoRecordingsFound : ScanResult()
    object CallLogPermissionDenied : ScanResult()
    data class Error(val message: String) : ScanResult()
}

/**
 * Coordinates folder scanning, call-log matching, and persistence. This is the
 * single entry point the ViewModel layer uses to refresh the local recordings table —
 * it owns the "scan -> match -> insert (dedup via unique index)" pipeline.
 */
class RecordingRepository(
    private val dao: RecordingDao,
    private val folderManager: RecordingFolderManager,
    private val scanner: RecordingScanner,
    private val callLogMatcher: CallLogMatcher,
    private val hasCallLogPermission: () -> Boolean
) {

    fun observeById(id: Long): Flow<RecordingEntity?> = dao.observeById(id)

    fun observeAll(): Flow<List<RecordingEntity>> = dao.observeAll()

    fun observeByStatus(status: SyncStatus): Flow<List<RecordingEntity>> = dao.observeByStatus(status)

    fun observeTotalCount(): Flow<Int> = dao.observeTotalCount()

    fun observeCountByStatus(status: SyncStatus): Flow<Int> = dao.observeCountByStatus(status)

    suspend fun scanAndMatch(): ScanResult {
        val folder = folderManager.currentFolder() ?: return ScanResult.NoFolderSelected

        val scannedFiles = runCatching { scanner.scan(folder) }
            .getOrElse { return ScanResult.Error(it.message ?: "Failed to read folder contents") }

        if (scannedFiles.isEmpty()) {
            return ScanResult.NoRecordingsFound
        }

        val callLogAvailable = hasCallLogPermission()
        val callLog = if (callLogAvailable) {
            runCatching { callLogMatcher.loadCallLog() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        var newCount = 0
        for (file in scannedFiles) {
            val existing = dao.findByUriAndSize(file.uri, file.size)
            if (existing != null) continue // duplicate — already tracked, skip silently

            val matchResult = if (callLogAvailable) {
                callLogMatcher.match(file.lastModified, callLog)
            } else {
                null
            }
            val matchedEntry = matchResult?.entry

            val entity = RecordingEntity(
                fileUri = file.uri,
                fileName = file.name,
                fileSize = file.size,
                mimeType = file.mimeType,
                lastModified = file.lastModified,
                phoneNumber = matchedEntry?.phoneNumber,
                callStartedAt = matchedEntry?.startedAt,
                durationSeconds = matchedEntry?.durationSeconds,
                callType = matchedEntry?.callType ?: CallType.UNKNOWN,
                syncStatus = if (matchedEntry == null) SyncStatus.UNMATCHED else SyncStatus.PENDING,
                uploadedAt = null,
                serverRecordingId = null,
                errorMessage = null
            )

            val insertedId = dao.insert(entity)
            if (insertedId != -1L) newCount++
        }

        return if (!callLogAvailable) {
            // Files were still scanned and stored as Unmatched; surface the permission gap to the user.
            ScanResult.CallLogPermissionDenied
        } else {
            ScanResult.Success(newRecordingsFound = newCount, totalScanned = scannedFiles.size)
        }
    }
}
