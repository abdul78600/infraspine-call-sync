package com.infraspine.callsync.ui.common

import android.content.Context
import com.infraspine.callsync.R
import com.infraspine.callsync.domain.model.CallType
import com.infraspine.callsync.domain.model.SyncStatus
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun Long?.toDisplayDateTime(): String {
    if (this == null || this <= 0L) return "—"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        .format(Date(this))
}

fun Long?.toDisplayDuration(): String {
    if (this == null || this < 0) return "—"
    val totalSeconds = this
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}

fun Long.toDisplayFileSize(): String {
    if (this <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = this.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.lastIndex) {
        size /= 1024
        unitIndex++
    }
    return String.format(Locale.getDefault(), "%.1f %s", size, units[unitIndex])
}

fun SyncStatus.displayLabel(context: Context): String = when (this) {
    SyncStatus.PENDING -> context.getString(R.string.status_pending)
    SyncStatus.SYNCED -> context.getString(R.string.status_synced)
    SyncStatus.FAILED -> context.getString(R.string.status_failed)
    SyncStatus.UNMATCHED -> context.getString(R.string.status_unmatched)
}

fun SyncStatus.colorRes(): Int = when (this) {
    SyncStatus.PENDING -> R.color.status_pending
    SyncStatus.SYNCED -> R.color.status_synced
    SyncStatus.FAILED -> R.color.status_failed
    SyncStatus.UNMATCHED -> R.color.status_unmatched
}

fun CallType.displayLabel(context: Context): String = when (this) {
    CallType.INCOMING -> context.getString(R.string.call_incoming)
    CallType.OUTGOING -> context.getString(R.string.call_outgoing)
    CallType.MISSED -> context.getString(R.string.call_missed)
    CallType.REJECTED -> context.getString(R.string.call_rejected)
    CallType.BLOCKED -> context.getString(R.string.call_blocked)
    CallType.UNKNOWN -> context.getString(R.string.call_unknown)
}

fun CallType.colorRes(): Int = when (this) {
    CallType.INCOMING -> R.color.call_incoming
    CallType.OUTGOING -> R.color.call_outgoing
    CallType.MISSED -> R.color.call_missed
    CallType.REJECTED -> R.color.call_unknown
    CallType.BLOCKED -> R.color.call_unknown
    CallType.UNKNOWN -> R.color.call_unknown
}

fun String?.orUnmatched(context: Context): String =
    this?.takeIf { it.isNotBlank() } ?: context.getString(R.string.unmatched_label)
