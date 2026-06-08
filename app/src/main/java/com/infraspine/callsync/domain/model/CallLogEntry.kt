package com.infraspine.callsync.domain.model

/**
 * Single call log row read from the device, used as a matching candidate
 * for a scanned recording.
 */
data class CallLogEntry(
    val id: Long? = null,
    val phoneNumber: String?,
    val startedAt: Long,
    val durationSeconds: Long,
    val callType: CallType,
    val cachedContactName: String? = null
)

/**
 * Result of attempting to pair a [ScannedAudioFile] with a [CallLogEntry] by
 * nearest start-time. `entry` is null when nothing within tolerance was found,
 * which the caller stores as [SyncStatus.UNMATCHED].
 */
data class CallMatchResult(
    val entry: CallLogEntry?
)
