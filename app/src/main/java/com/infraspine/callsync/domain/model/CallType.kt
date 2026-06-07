package com.infraspine.callsync.domain.model

/**
 * Mirrors Android CallLog.Calls types, normalized for storage and display.
 */
enum class CallType {
    INCOMING,
    OUTGOING,
    MISSED,
    UNKNOWN;

    companion object {
        fun fromCallLogType(type: Int): CallType = when (type) {
            android.provider.CallLog.Calls.INCOMING_TYPE -> INCOMING
            android.provider.CallLog.Calls.OUTGOING_TYPE -> OUTGOING
            android.provider.CallLog.Calls.MISSED_TYPE -> MISSED
            else -> UNKNOWN
        }

        fun fromName(name: String?): CallType =
            entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}
