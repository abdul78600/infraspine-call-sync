package com.infraspine.callsync.ui.recordings

import com.infraspine.callsync.domain.model.SyncStatus

enum class RecordingFilter(val status: SyncStatus?) {
    ALL(null),
    PENDING(SyncStatus.PENDING),
    SYNCED(SyncStatus.SYNCED),
    FAILED(SyncStatus.FAILED),
    UNMATCHED(SyncStatus.UNMATCHED)
}
