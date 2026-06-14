package com.infraspine.callsync.data.remote

/**
 * Request/response shapes for the "check-existing" endpoints used to find out
 * which call logs / recordings the server already has, before uploading.
 *
 * Each record carries a client-chosen identifier that the server echoes back in
 * the response so the app can map server-known rows back to local records.
 */

// ---- Call logs check-existing ----

data class CallLogExistingCheckRequest(
    val deviceId: String,
    val records: List<CallLogCheckItem>
)

data class CallLogCheckItem(
    /** Local CallLog `_ID`, as a string, echoed back by the server. */
    val clientRef: String,
    val phoneNumber: String,
    /** Lowercase: incoming | outgoing | missed | rejected | blocked */
    val callType: String,
    /** ISO-8601 UTC, e.g. "2026-06-08T12:30:00.000Z" */
    val callStartedAt: String,
    val durationSeconds: Long
)

data class CallLogExistingCheckResponse(
    val existing: List<String>? = null,
    val missing: List<String>? = null,
    val message: String? = null
)

// ---- Call recordings check-existing ----

data class RecordingExistingCheckRequest(
    val deviceId: String,
    val records: List<RecordingCheckItem>
)

data class RecordingCheckItem(
    /** Local Room recording id, as a string, echoed back by the server. */
    val clientRef: String,
    val phoneNumber: String,
    /** ISO-8601 UTC, e.g. "2026-06-08T12:30:00.000Z" */
    val callStartedAt: String,
    val durationSeconds: Long?,
    val fileSize: Long,
    /** SHA-256 hex of the file content, or null if it could not be computed. */
    val fileHash: String?
)

data class RecordingExistingCheckResponse(
    val existing: List<String>? = null,
    val missing: List<String>? = null,
    val message: String? = null
)
