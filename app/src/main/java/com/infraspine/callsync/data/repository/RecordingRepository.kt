package com.infraspine.callsync.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.infraspine.callsync.data.local.dao.RecordingDao
import com.infraspine.callsync.data.local.entity.RecordingEntity
import com.infraspine.callsync.domain.model.CallType
import com.infraspine.callsync.domain.model.SyncStatus
import com.infraspine.callsync.scan.CallLogMatcher
import com.infraspine.callsync.scan.RecordingFolderManager
import com.infraspine.callsync.scan.RecordingScanner
import com.infraspine.callsync.ui.recordings.RecordingSortOrder
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

    /**
     * Observes recordings matching [searchQuery] (file name or phone number, case-insensitive
     * substring) and optionally [status]. Pass a blank query to fall back to the unfiltered list.
     */
    fun observeFiltered(
        status: SyncStatus?,
        searchQuery: String,
        sortOrder: RecordingSortOrder = RecordingSortOrder.DATE_DESC
    ): Flow<List<RecordingEntity>> {
        val trimmed = searchQuery.trim()
        val orderBy = sortOrder.sql

        // Fast path: use pre-compiled queries when no custom sort is needed
        if (sortOrder == RecordingSortOrder.DATE_DESC) {
            if (trimmed.isEmpty()) {
                return if (status == null) observeAll() else observeByStatus(status)
            }
            val likePattern = "%${trimmed.replace("%", "\\%").replace("_", "\\_")}%"
            return if (status == null) dao.observeBySearch(likePattern)
            else dao.observeByStatusAndSearch(status, likePattern)
        }

        // Dynamic query for custom sort orders
        val args = mutableListOf<Any>()
        val sb = StringBuilder("SELECT * FROM recordings WHERE 1=1")
        if (status != null) {
            sb.append(" AND syncStatus = ?")
            args.add(status.name)
        }
        if (trimmed.isNotEmpty()) {
            val like = "%${trimmed.replace("%", "\\%").replace("_", "\\_")}%"
            sb.append(" AND (fileName LIKE ? OR phoneNumber LIKE ?)")
            args.add(like)
            args.add(like)
        }
        sb.append(" ORDER BY $orderBy")
        return dao.observeRaw(SimpleSQLiteQuery(sb.toString(), args.toTypedArray()))
    }

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
            if (existing != null && existing.syncStatus == SyncStatus.UNMATCHED && callLogAvailable) {
                val rematch = callLogMatcher.match(file.lastModified, callLog).entry
                if (rematch != null) {
                    dao.updateUnmatchedRecordingMatch(
                        id = existing.id,
                        phoneNumber = rematch.phoneNumber,
                        callStartedAt = rematch.startedAt,
                        durationSeconds = rematch.durationSeconds,
                        callType = rematch.callType,
                        status = SyncStatus.PENDING
                    )
                    newCount++
                }
            }
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
