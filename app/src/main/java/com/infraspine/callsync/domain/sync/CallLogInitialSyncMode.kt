package com.infraspine.callsync.domain.sync

enum class CallLogInitialSyncMode(val persistedValue: String) {
    FROM_NOW("from_now"),
    FULL_HISTORY("full_history");

    companion object {
        fun fromPersisted(value: String?): CallLogInitialSyncMode =
            entries.firstOrNull { it.persistedValue == value } ?: FULL_HISTORY
    }
}
