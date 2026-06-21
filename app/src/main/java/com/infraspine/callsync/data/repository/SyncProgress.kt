package com.infraspine.callsync.data.repository

data class SyncProgress(
    val current: Int,
    val total: Int,
    val phase: String = "recordings"
) {
    val isActive get() = total > 0
}
