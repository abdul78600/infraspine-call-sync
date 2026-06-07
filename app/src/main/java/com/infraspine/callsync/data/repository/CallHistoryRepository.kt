package com.infraspine.callsync.data.repository

import com.infraspine.callsync.data.local.dao.RecordingDao
import com.infraspine.callsync.domain.model.CallHistoryEntry
import com.infraspine.callsync.scan.CallLogMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the device's full call history — independent of the recording-scan
 * pipeline — and annotates each call with whether a recording was ever matched
 * to it. This lets agents see calls that never produced a recording (missed,
 * not answered, disconnected before the recorder started, recorder app not
 * running, etc.), not just the ones that happen to have audio.
 */
class CallHistoryRepository(
    private val dao: RecordingDao,
    private val callLogMatcher: CallLogMatcher
) {

    /**
     * Returns one page of the device call log (newest first), each entry flagged with
     * [CallHistoryEntry.hasRecording]. Requires READ_CALL_LOG — callers should check
     * permission before invoking this (mirrors [RecordingRepository]'s pattern).
     *
     * A page shorter than [limit] signals the end of the call log to callers doing
     * infinite-scroll pagination.
     */
    suspend fun loadCallHistoryPage(offset: Int, limit: Int): List<CallHistoryEntry> = withContext(Dispatchers.IO) {
        val page = callLogMatcher.loadCallLogPage(offset, limit)
        if (page.isEmpty()) return@withContext emptyList()

        val matchedTimestamps = dao.getMatchedCallStartTimestamps().toSet()

        page.map { entry ->
            CallHistoryEntry(
                phoneNumber = entry.phoneNumber,
                startedAt = entry.startedAt,
                durationSeconds = entry.durationSeconds,
                callType = entry.callType,
                hasRecording = entry.startedAt in matchedTimestamps
            )
        }
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}
