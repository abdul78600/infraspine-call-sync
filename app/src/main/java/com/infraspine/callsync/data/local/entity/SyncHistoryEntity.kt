package com.infraspine.callsync.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_history")
data class SyncHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val syncedAt: Long,
    val recordingsUploaded: Int,
    val recordingsFailed: Int,
    val recordingsSkipped: Int,
    val callLogsUploaded: Int,
    val callLogsFailed: Int
)
