package com.infraspine.callsync.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.infraspine.callsync.data.local.entity.SyncHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SyncHistoryEntity)

    @Query("SELECT * FROM sync_history ORDER BY syncedAt DESC LIMIT 100")
    fun observeAll(): Flow<List<SyncHistoryEntity>>

    @Query("DELETE FROM sync_history WHERE id NOT IN (SELECT id FROM sync_history ORDER BY syncedAt DESC LIMIT 100)")
    suspend fun trimToLatest100()
}
