package com.infraspine.callsync.data.repository

import com.google.gson.JsonPrimitive
import com.infraspine.callsync.data.prefs.CallLogSyncCursor
import com.infraspine.callsync.data.prefs.CallLogSyncStateSnapshot
import com.infraspine.callsync.data.remote.CallLogsSyncResponse
import com.infraspine.callsync.domain.sync.CallLogInitialSyncMode
import com.infraspine.callsync.domain.model.CallType
import com.infraspine.callsync.scan.MobileCallLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogSyncSupportTest {

    @Test
    fun nextCursorKeepsAndroidCallLogIdFromFallbackLog() {
        val response = CallLogsSyncResponse(
            latestServerCallStartedAt = JsonPrimitive("2026-06-12T10:30:00.000Z"),
            latestServerCallLogId = JsonPrimitive(99999)
        )
        val fallbackLog = mobileCallLog(id = 77L, startedAt = 1_000L)

        val cursor = CallLogSyncSupport.nextCursor(
            response = response,
            fallbackLog = fallbackLog,
            syncedAt = 123L
        )

        assertEquals(77L, cursor?.lastSyncedAndroidCallLogId)
        assertEquals(1_781_260_200_000L, cursor?.lastSyncedCallStartedAt)
        assertEquals(123L, cursor?.lastCallLogSyncAt)
    }

    @Test
    fun successCountsTreatsDuplicateOnlyResponseAsSuccess() {
        val response = CallLogsSyncResponse(
            insertedCount = 0,
            duplicateCount = 5
        )

        val counts = CallLogSyncSupport.successCounts(response, fallbackBatchSize = 5)

        assertEquals(0, counts.uploaded)
        assertEquals(5, counts.duplicateCount)
    }

    @Test
    fun shouldStopBatchSyncOn429() {
        assertTrue(CallLogSyncSupport.shouldStopBatchSync(429))
        assertTrue(CallLogSyncSupport.shouldStopBatchSync(400))
        assertFalse(CallLogSyncSupport.shouldStopBatchSync(500))
    }

    @Test
    fun singleFlightGateRejectsParallelAcquireUntilRelease() {
        val gate = SingleFlightCallLogSyncGate()

        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())

        gate.release()

        assertTrue(gate.tryAcquire())
    }

    @Test
    fun effectiveCursorSeedsFromLatestLocalLogWhenServerIsNew() {
        val decision = CallLogSyncSupport.effectiveCursor(
            local = CallLogSyncCursor(),
            remote = CallLogSyncStateSnapshot(),
            resetRequested = false,
            initialSyncMode = CallLogInitialSyncMode.FROM_NOW,
            latestLocalLog = mobileCallLog(id = 500L, startedAt = 2_000L),
            syncedAt = 333L
        )

        assertEquals(500L, decision.queryCursor.lastSyncedAndroidCallLogId)
        assertEquals(2_000L, decision.queryCursor.lastSyncedCallStartedAt)
        assertEquals(333L, decision.queryCursor.lastCallLogSyncAt)
        assertTrue(decision.skippedFullHistory)
    }

    @Test
    fun effectiveCursorDoesNotSeedWhenFullHistoryIsEnabled() {
        val decision = CallLogSyncSupport.effectiveCursor(
            local = CallLogSyncCursor(),
            remote = CallLogSyncStateSnapshot(),
            resetRequested = false,
            initialSyncMode = CallLogInitialSyncMode.FULL_HISTORY,
            latestLocalLog = mobileCallLog(id = 500L, startedAt = 2_000L),
            syncedAt = 333L
        )

        assertTrue(decision.queryCursor.isEmpty())
        assertNull(decision.seededCursor)
        assertFalse(decision.skippedFullHistory)
    }

    @Test
    fun effectiveCursorUsesRemoteCheckpointWhenAvailable() {
        val decision = CallLogSyncSupport.effectiveCursor(
            local = CallLogSyncCursor(),
            remote = CallLogSyncStateSnapshot(
                latestExternalCallId = "88",
                latestCallStartedAt = 4_000L,
                latestAndroidCallLogId = 88L,
                totalLogs = 10
            ),
            resetRequested = false,
            initialSyncMode = CallLogInitialSyncMode.FROM_NOW,
            latestLocalLog = mobileCallLog(id = 500L, startedAt = 2_000L),
            syncedAt = 333L
        )

        assertEquals(88L, decision.queryCursor.lastSyncedAndroidCallLogId)
        assertEquals(4_000L, decision.queryCursor.lastSyncedCallStartedAt)
        assertFalse(decision.skippedFullHistory)
    }

    private fun mobileCallLog(
        id: Long,
        startedAt: Long
    ) = MobileCallLog(
        id = id,
        phoneNumber = "+923001112233",
        callType = CallType.INCOMING,
        startedAt = startedAt,
        durationSeconds = 42L,
        cachedContactName = null
    )
}
