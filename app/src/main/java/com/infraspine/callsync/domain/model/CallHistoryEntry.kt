package com.infraspine.callsync.domain.model

/**
 * A single device call-log row for display in the Call History screen, annotated
 * with whether a recording was matched to it. Unlike [CallLogEntry] (an internal
 * matching candidate), this is shown to the agent regardless of whether the call
 * ever produced — or was expected to produce — a recording (e.g. missed calls,
 * calls that disconnected before the recorder could capture them).
 */
data class CallHistoryEntry(
    val callLogId: Long,
    val phoneNumber: String?,
    val startedAt: Long,
    val durationSeconds: Long,
    val callType: CallType,
    val hasRecording: Boolean
)
