package com.infraspine.callsync.data.repository

import com.google.gson.JsonPrimitive
import com.infraspine.callsync.data.remote.CallLogsSyncResponse
import com.infraspine.callsync.domain.model.CallType
import com.infraspine.callsync.scan.MobileCallLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
