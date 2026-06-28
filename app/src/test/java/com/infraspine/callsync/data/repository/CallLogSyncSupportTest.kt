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
        assertTrue(CallLogSyncSupport.shouldStopBatchSync(403))
        assertTrue(CallLogSyncSupport.shouldStopBatchSync(400))
        assertFalse(CallLogSyncSupport.shouldStopBatchSync(500))
    }

    @Test
    fun syncBatchesRespectBackendLimitForOneThousandRecords() {
        val batches = CallLogSyncSupport.syncBatches((1..1000).toList(), maxBatchSize = 200)

        assertEquals(5, batches.size)
        assertEquals(listOf(200, 200, 200, 200, 200), batches.map { it.size })
        assertEquals(1, batches.first().first())
        assertEquals(1000, batches.last().last())
    }

    @Test
    fun syncBatchesKeepTailUnderBackendLimit() {
        val batches = CallLogSyncSupport.syncBatches((1..1001).toList(), maxBatchSize = 200)

        assertEquals(6, batches.size)
        assertEquals(listOf(200, 200, 200, 200, 200, 1), batches.map { it.size })
    }

    @Test
    fun rejectedCountIncludesInvalidAndFailedRows() {
        val response = CallLogsSyncResponse(
            invalid = 2,
            failed = 3
        )

        assertEquals(5, CallLogSyncSupport.rejectedCount(response))
    }

    @Test
    fun failedBatchCountCountsOnlyCurrentChunkOrRejectedRows() {
        assertEquals(200, CallLogSyncSupport.failedBatchCount(batchSize = 200))
        assertEquals(7, CallLogSyncSupport.failedBatchCount(batchSize = 200, rejectedCount = 7))
        assertEquals(200, CallLogSyncSupport.failedBatchCount(batchSize = 200, rejectedCount = 250))
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

    @Test
    fun effectiveCursorUsesRemoteCheckpointWhenDateIsNewerEvenIfIdIsLower() {
        val decision = CallLogSyncSupport.effectiveCursor(
            local = CallLogSyncCursor(
                lastSyncedCallStartedAt = 4_000L,
                lastSyncedAndroidCallLogId = 500L
            ),
            remote = CallLogSyncStateSnapshot(
                latestExternalCallId = "88",
                latestCallStartedAt = 5_000L,
                latestAndroidCallLogId = 88L,
                totalLogs = 10
            ),
            resetRequested = false,
            initialSyncMode = CallLogInitialSyncMode.FROM_NOW,
            latestLocalLog = null,
            syncedAt = 333L
        )

        assertEquals(88L, decision.queryCursor.lastSyncedAndroidCallLogId)
        assertEquals(5_000L, decision.queryCursor.lastSyncedCallStartedAt)
    }

    @Test
    fun effectiveCursorUsesRemoteCheckpointWhenLocalCursorIsAhead() {
        val decision = CallLogSyncSupport.effectiveCursor(
            local = CallLogSyncCursor(
                lastSyncedCallStartedAt = 6_000L,
                lastSyncedAndroidCallLogId = 600L,
                lastCallLogSyncAt = 222L
            ),
            remote = CallLogSyncStateSnapshot(
                latestExternalCallId = "88",
                latestCallStartedAt = 5_000L,
                latestAndroidCallLogId = 88L,
                totalLogs = 10
            ),
            resetRequested = false,
            initialSyncMode = CallLogInitialSyncMode.FROM_NOW,
            latestLocalLog = null,
            syncedAt = 333L
        )

        assertEquals(88L, decision.queryCursor.lastSyncedAndroidCallLogId)
        assertEquals(5_000L, decision.queryCursor.lastSyncedCallStartedAt)
        assertEquals(222L, decision.queryCursor.lastCallLogSyncAt)
    }

    @Test
    fun shouldRunRecoveryAlwaysForManualTrigger() {
        assertTrue(
            CallLogSyncSupport.shouldRunRecovery(
                trigger = CallLogSyncTrigger.MANUAL,
                lastRecoveryAt = 10_000L,
                now = 20_000L,
                recoveryIntervalMs = 86_400_000L
            )
        )
    }

    @Test
    fun shouldRunRecoveryForAppOpenAndPeriodicOnlyWhenDue() {
        assertFalse(
            CallLogSyncSupport.shouldRunRecovery(
                trigger = CallLogSyncTrigger.APP_OPEN,
                lastRecoveryAt = 15_000L,
                now = 20_000L,
                recoveryIntervalMs = 10_000L
            )
        )
        assertTrue(
            CallLogSyncSupport.shouldRunRecovery(
                trigger = CallLogSyncTrigger.APP_OPEN,
                lastRecoveryAt = 5_000L,
                now = 20_000L,
                recoveryIntervalMs = 10_000L
            )
        )
        assertFalse(
            CallLogSyncSupport.shouldRunRecovery(
                trigger = CallLogSyncTrigger.PERIODIC,
                lastRecoveryAt = 15_000L,
                now = 20_000L,
                recoveryIntervalMs = 10_000L
            )
        )
        assertTrue(
            CallLogSyncSupport.shouldRunRecovery(
                trigger = CallLogSyncTrigger.PERIODIC,
                lastRecoveryAt = 5_000L,
                now = 20_000L,
                recoveryIntervalMs = 10_000L
            )
        )
    }

    @Test
    fun mergeFetchedLogsKeepsOlderRecoveryCallsWithoutDuplicatingCursorResults() {
        val olderMissing = mobileCallLog(id = 10L, startedAt = 1_000L)
        val duplicateNewer = mobileCallLog(id = 30L, startedAt = 3_000L)
        val newerCursor = mobileCallLog(id = 30L, startedAt = 3_000L)
        val newestCursor = mobileCallLog(id = 40L, startedAt = 4_000L)

        val merged = CallLogSyncSupport.mergeFetchedLogs(
            cursorFetched = listOf(newerCursor, newestCursor),
            recoveryFetched = listOf(olderMissing, duplicateNewer)
        )

        assertEquals(listOf(10L, 30L, 40L), merged.map { it.id })
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
