package com.infraspine.callsync.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.infraspine.callsync.data.local.entity.RecordingEntity
import com.infraspine.callsync.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    /**
     * Ignores on conflict so re-scanning the same folder never creates duplicate rows
     * (unique index on fileUri+fileSize in [RecordingEntity] enforces this at the DB level).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(recording: RecordingEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(recordings: List<RecordingEntity>): List<Long>

    @Update
    suspend fun update(recording: RecordingEntity)

    @Query("SELECT * FROM recordings WHERE fileUri = :uri AND fileSize = :size LIMIT 1")
    suspend fun findByUriAndSize(uri: String, size: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings ORDER BY callStartedAt DESC, lastModified DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE syncStatus = :status ORDER BY callStartedAt DESC, lastModified DESC")
    fun observeByStatus(status: SyncStatus): Flow<List<RecordingEntity>>

    /**
     * Matches [query] against file name or phone number. The repository wraps the raw
     * search text in `%...%` before binding, so this stays a plain parameterized LIKE
     * (Room binds `:query` safely — no string concatenation, no injection risk).
     */
    @Query(
        """SELECT * FROM recordings
           WHERE (fileName LIKE :query OR phoneNumber LIKE :query)
           ORDER BY callStartedAt DESC, lastModified DESC"""
    )
    fun observeBySearch(query: String): Flow<List<RecordingEntity>>

    @Query(
        """SELECT * FROM recordings
           WHERE syncStatus = :status AND (fileName LIKE :query OR phoneNumber LIKE :query)
           ORDER BY callStartedAt DESC, lastModified DESC"""
    )
    fun observeByStatusAndSearch(status: SyncStatus, query: String): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE syncStatus = :status")
    suspend fun getByStatus(status: SyncStatus): List<RecordingEntity>

    /**
     * Distinct call-start timestamps that already have a matched recording —
     * used by the Call History screen to flag which device call-log rows
     * already produced a tracked recording, without loading full entities.
     */
    @Query("SELECT DISTINCT callStartedAt FROM recordings WHERE callStartedAt IS NOT NULL")
    suspend fun getMatchedCallStartTimestamps(): List<Long>

    @Query("SELECT COUNT(*) FROM recordings")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM recordings WHERE syncStatus = :status")
    fun observeCountByStatus(status: SyncStatus): Flow<Int>

    @Query("UPDATE recordings SET syncStatus = :status, uploadedAt = :uploadedAt, serverRecordingId = :serverId, errorMessage = :error WHERE id = :id")
    suspend fun updateSyncResult(id: Long, status: SyncStatus, uploadedAt: Long?, serverId: String?, error: String?)

    @Query("DELETE FROM recordings")
    suspend fun deleteAll()
}
