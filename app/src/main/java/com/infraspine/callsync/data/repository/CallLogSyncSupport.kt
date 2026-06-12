package com.infraspine.callsync.data.repository

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.infraspine.callsync.data.prefs.CallLogSyncCursor
import com.infraspine.callsync.data.remote.CallLogsSyncResponse
import com.infraspine.callsync.scan.MobileCallLog
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

internal class SingleFlightCallLogSyncGate {
    private val inFlight = AtomicBoolean(false)

    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    fun release() {
        inFlight.set(false)
    }
}

internal data class CallLogSyncSuccessCounts(
    val uploaded: Int,
    val duplicateCount: Int
)

internal object CallLogSyncSupport {
    fun successCounts(
        response: CallLogsSyncResponse?,
        fallbackBatchSize: Int
    ): CallLogSyncSuccessCounts {
        val uploaded = response?.insertedCount ?: response?.uploaded ?: 0
        val duplicateCount = response?.duplicateCount ?: response?.skipped ?: 0
        if (uploaded > 0 || duplicateCount > 0) {
            return CallLogSyncSuccessCounts(
                uploaded = uploaded,
                duplicateCount = duplicateCount
            )
        }

        return CallLogSyncSuccessCounts(
            uploaded = fallbackBatchSize,
            duplicateCount = 0
        )
    }

    fun nextCursor(
        response: CallLogsSyncResponse?,
        fallbackLog: MobileCallLog,
        syncedAt: Long
    ): CallLogSyncCursor? {
        val responseStartedAt = response?.latestServerCallStartedAt.toEpochMillisOrZero()
        if (responseStartedAt <= 0L) return null

        return CallLogSyncCursor(
            lastSyncedCallStartedAt = responseStartedAt,
            lastSyncedAndroidCallLogId = fallbackLog.id,
            lastCallLogSyncAt = syncedAt
        )
    }

    fun remainingCount(total: Int, completed: Int): Int =
        (total - completed).coerceAtLeast(0)

    fun shouldStopBatchSync(httpCode: Int): Boolean =
        httpCode == 400 || httpCode == 429
}

private fun JsonElement?.toEpochMillisOrZero(): Long {
    val element = this ?: return 0L
    if (element is JsonPrimitive && element.isNumber) return element.asLong
    val raw = if (element is JsonPrimitive && element.isString) element.asString else return 0L
    return raw.toLongOrNull() ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)
}
