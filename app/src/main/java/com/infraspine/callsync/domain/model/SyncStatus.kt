package com.infraspine.callsync.domain.model

/**
 * Lifecycle of a recording with respect to CRM upload.
 */
enum class SyncStatus {
    PENDING,
    SYNCED,
    FAILED,
    UNMATCHED;

    companion object {
        fun fromName(name: String?): SyncStatus =
            entries.firstOrNull { it.name == name } ?: PENDING
    }
}
