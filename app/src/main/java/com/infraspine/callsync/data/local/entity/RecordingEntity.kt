package com.infraspine.callsync.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.infraspine.callsync.domain.model.CallType
import com.infraspine.callsync.domain.model.SyncStatus

/**
 * Local record of a scanned call recording, its matched call-log data (if any),
 * and its CRM upload/sync state. fileUri + fileSize uniquely identify a recording,
 * which is what duplicate detection relies on (see [RecordingDao.findByUriAndSize]).
 */
@Entity(
    tableName = "recordings",
    indices = [
        Index(value = ["fileUri", "fileSize"], unique = true),
        Index(value = ["syncStatus"]),
        Index(value = ["fileHash"])
    ]
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val fileUri: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String?,
    val lastModified: Long,

    val phoneNumber: String?,
    val callStartedAt: Long?,
    val durationSeconds: Long?,
    val callType: CallType,

    val syncStatus: SyncStatus,
    val uploadedAt: Long?,
    val serverRecordingId: String?,
    val errorMessage: String?,

    /** SHA-256 of the file content, computed lazily at sync time for dedup checks. */
    val fileHash: String? = null
)
