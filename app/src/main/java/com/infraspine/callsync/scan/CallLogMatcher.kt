package com.infraspine.callsync.scan

import android.content.Context
import android.provider.CallLog
import com.infraspine.callsync.domain.model.CallLogEntry
import com.infraspine.callsync.domain.model.CallMatchResult
import com.infraspine.callsync.domain.model.CallType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class CallLogPageCursor(
    val startedAt: Long,
    val callLogId: Long
)

data class CallLogDateRange(
    val startAtInclusive: Long,
    val endAtInclusive: Long
)

data class CallLogPage(
    val entries: List<CallLogEntry>,
    val nextCursor: CallLogPageCursor?
)

/**
 * Matches scanned recordings to call log entries by proximity of timestamps.
 *
 * Recording apps typically name/timestamp files at call END time, while the call log
 * stores call START time. We therefore compare the recording's lastModified timestamp
 * against (callStart + durationSeconds), in addition to comparing directly against
 * callStart, and pick whichever candidate is closest — as long as it's within
 * [MATCH_TOLERANCE_MS]. This keeps matching robust across different recorder apps'
 * file-naming conventions without needing to parse file names.
 */
class CallLogMatcher(private val context: Context) {

    /**
     * Loads the device call log once. Call this on a background thread and reuse
     * the result across multiple [match] calls during a single scan to avoid
     * re-querying the content provider per file.
     */
    suspend fun loadCallLog(): List<CallLogEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<CallLogEntry>()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE
        )

        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)

                while (cursor.moveToNext()) {
                    entries += CallLogEntry(
                        phoneNumber = numberIdx.takeIf { it >= 0 }?.let { cursor.getString(it) },
                        startedAt = dateIdx.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                        durationSeconds = durationIdx.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                        callType = typeIdx.takeIf { it >= 0 }
                            ?.let { CallType.fromCallLogType(cursor.getInt(it)) }
                            ?: CallType.UNKNOWN
                    )
                }
            }
        }

        entries
    }

    /**
     * Loads a single page of the device call log, newest first, for paginated UI lists.
     * Uses a `(DATE, _ID)` cursor so later pages only read older rows instead of
     * re-reading the full prefix on every scroll.
     */
    suspend fun loadCallLogPage(
        afterCursor: CallLogPageCursor?,
        limit: Int
    ): CallLogPage = withContext(Dispatchers.IO) {
        val entries = queryCallLog(afterCursor = afterCursor, limit = limit + 1)
        val pageEntries = entries.take(limit)
        val nextCursor = if (entries.size > limit) {
            pageEntries.lastOrNull()?.let { entry ->
                CallLogPageCursor(
                    startedAt = entry.startedAt,
                    callLogId = entry.id ?: 0L
                )
            }
        } else {
            null
        }

        CallLogPage(entries = pageEntries, nextCursor = nextCursor)
    }

    suspend fun loadRecentCallLog(limit: Int?, dateRange: CallLogDateRange? = null): List<CallLogEntry> = withContext(Dispatchers.IO) {
        queryCallLog(afterCursor = null, limit = limit, dateRange = dateRange)
    }

    private fun queryCallLog(
        afterCursor: CallLogPageCursor?,
        limit: Int?,
        dateRange: CallLogDateRange? = null
    ): List<CallLogEntry> {
        val entries = mutableListOf<CallLogEntry>()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE
        )
        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        afterCursor?.let {
            selectionParts += "((${CallLog.Calls.DATE} < ?) OR (${CallLog.Calls.DATE} = ? AND ${CallLog.Calls._ID} < ?))"
            selectionArgs += it.startedAt.toString()
            selectionArgs += it.startedAt.toString()
            selectionArgs += it.callLogId.toString()
        }
        dateRange?.let {
            selectionParts += "${CallLog.Calls.DATE} >= ?"
            selectionArgs += it.startAtInclusive.toString()
            selectionParts += "${CallLog.Calls.DATE} <= ?"
            selectionArgs += it.endAtInclusive.toString()
        }
        val selection = selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val selectionArgArray = selectionArgs.takeIf { it.isNotEmpty() }?.toTypedArray()

        runCatching {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgArray,
                "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(CallLog.Calls._ID)
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)

                while (it.moveToNext()) {
                    entries += CallLogEntry(
                        id = idIdx.takeIf { idx -> idx >= 0 }?.let { idx -> it.getLong(idx) },
                        phoneNumber = numberIdx.takeIf { idx -> idx >= 0 }?.let { idx -> it.getString(idx) },
                        startedAt = dateIdx.takeIf { idx -> idx >= 0 }?.let { idx -> it.getLong(idx) } ?: 0L,
                        durationSeconds = durationIdx.takeIf { idx -> idx >= 0 }?.let { idx -> it.getLong(idx) } ?: 0L,
                        callType = typeIdx.takeIf { idx -> idx >= 0 }
                            ?.let { idx -> CallType.fromCallLogType(it.getInt(idx)) }
                            ?: CallType.UNKNOWN
                    )
                }
            }
        }

        return entries
            .sortedWith(
                compareByDescending<CallLogEntry> { it.startedAt }
                    .thenByDescending { it.id ?: 0L }
            )
            .let { sorted -> limit?.let { sorted.take(it) } ?: sorted }
    }

    /**
     * Finds the closest call log entry to [recordingTimestampMs] within tolerance.
     * Considers both the call's start time and its end time (start + duration) as
     * candidate anchors, since recordings may be timestamped at call start or end
     * depending on the recorder app.
     */
    fun match(recordingTimestampMs: Long, callLog: List<CallLogEntry>): CallMatchResult {
        var best: CallLogEntry? = null
        var bestDelta = Long.MAX_VALUE

        for (entry in callLog) {
            val endAt = entry.startedAt + entry.durationSeconds * 1000L
            val deltaToStart = abs(recordingTimestampMs - entry.startedAt)
            val deltaToEnd = abs(recordingTimestampMs - endAt)
            val delta = minOf(deltaToStart, deltaToEnd)

            if (delta < bestDelta) {
                bestDelta = delta
                best = entry
            }
        }

        return if (best != null && bestDelta <= MATCH_TOLERANCE_MS) {
            CallMatchResult(best)
        } else {
            CallMatchResult(entry = null)
        }
    }

    companion object {
        /** Maximum allowed gap between a recording's timestamp and a call's start/end to count as a match. */
        const val MATCH_TOLERANCE_MS = 3 * 60 * 1000L // 3 minutes
    }
}
