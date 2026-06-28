package com.infraspine.callsync.data.repository

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.infraspine.callsync.data.prefs.CallLogSyncCursor
import com.infraspine.callsync.data.prefs.CallLogSyncStateSnapshot
import com.infraspine.callsync.data.remote.CallLogsSyncResponse
import com.infraspine.callsync.domain.sync.CallLogInitialSyncMode
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

enum class CallLogSyncTrigger {
    MANUAL,
    LOGIN,
    APP_OPEN,
    PERIODIC
}

internal data class CallLogSyncSuccessCounts(
    val uploaded: Int,
    val duplicateCount: Int
)

internal data class CallLogCursorDecision(
    val queryCursor: CallLogSyncCursor,
    val seededCursor: CallLogSyncCursor? = null,
    val skippedFullHistory: Boolean = false
)

internal object CallLogSyncSupport {
    fun <T> syncBatches(items: List<T>, maxBatchSize: Int): List<List<T>> {
        require(maxBatchSize > 0) { "maxBatchSize must be positive" }
        return items.chunked(maxBatchSize)
    }

    fun rejectedCount(response: CallLogsSyncResponse?): Int =
        (response?.invalid ?: 0) + (response?.failed ?: 0)

    fun failedBatchCount(batchSize: Int, rejectedCount: Int = 0): Int =
        if (rejectedCount > 0) rejectedCount.coerceAtMost(batchSize) else batchSize

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
        httpCode == 400 || httpCode == 403 || httpCode == 429

    fun shouldRunRecovery(
        trigger: CallLogSyncTrigger,
        lastRecoveryAt: Long,
        now: Long,
        recoveryIntervalMs: Long
    ): Boolean =
        when (trigger) {
            CallLogSyncTrigger.MANUAL,
            CallLogSyncTrigger.LOGIN -> true

            CallLogSyncTrigger.APP_OPEN,
            CallLogSyncTrigger.PERIODIC ->
                lastRecoveryAt <= 0L || now - lastRecoveryAt >= recoveryIntervalMs
        }

    fun mergeFetchedLogs(
        cursorFetched: List<MobileCallLog>,
        recoveryFetched: List<MobileCallLog>
    ): List<MobileCallLog> {
        if (recoveryFetched.isEmpty()) return cursorFetched
        if (cursorFetched.isEmpty()) {
            return recoveryFetched.sortedWith(compareBy<MobileCallLog> { it.startedAt }.thenBy { it.id })
        }

        val merged = LinkedHashMap<Long, MobileCallLog>(cursorFetched.size + recoveryFetched.size)
        for (log in cursorFetched) merged[log.id] = log
        for (log in recoveryFetched) merged[log.id] = log
        return merged.values.sortedWith(compareBy<MobileCallLog> { it.startedAt }.thenBy { it.id })
    }

    fun effectiveCursor(
        local: CallLogSyncCursor,
        remote: CallLogSyncStateSnapshot,
        resetRequested: Boolean,
        initialSyncMode: CallLogInitialSyncMode,
        latestLocalLog: MobileCallLog?,
        syncedAt: Long
    ): CallLogCursorDecision {
        if (resetRequested) {
            return CallLogCursorDecision(queryCursor = local)
        }

        if (remote.isEmpty() && local.isEmpty() && initialSyncMode == CallLogInitialSyncMode.FROM_NOW) {
            val seededCursor = latestLocalLog?.let {
                CallLogSyncCursor(
                    lastSyncedCallStartedAt = it.startedAt,
                    lastSyncedAndroidCallLogId = it.id,
                    lastCallLogSyncAt = syncedAt
                )
            } ?: local
            return CallLogCursorDecision(
                queryCursor = seededCursor,
                seededCursor = seededCursor.takeIf { !it.isEmpty() },
                skippedFullHistory = seededCursor != local && !seededCursor.isEmpty()
            )
        }

        if (remote.isEmpty()) {
            return CallLogCursorDecision(queryCursor = local)
        }
        if (local.isEmpty()) {
            return CallLogCursorDecision(
                queryCursor = CallLogSyncCursor(
                    lastSyncedCallStartedAt = remote.latestCallStartedAt,
                    lastSyncedAndroidCallLogId = remote.latestAndroidCallLogId,
                    lastCallLogSyncAt = local.lastCallLogSyncAt
                )
            )
        }

        return if (
            remote.latestCallStartedAt > local.lastSyncedCallStartedAt ||
            (remote.latestCallStartedAt == local.lastSyncedCallStartedAt &&
                remote.latestAndroidCallLogId > local.lastSyncedAndroidCallLogId)
        ) {
            CallLogCursorDecision(
                queryCursor = CallLogSyncCursor(
                    lastSyncedCallStartedAt = remote.latestCallStartedAt,
                    lastSyncedAndroidCallLogId = remote.latestAndroidCallLogId,
                    lastCallLogSyncAt = local.lastCallLogSyncAt
                )
            )
        } else {
            CallLogCursorDecision(queryCursor = local)
        }
    }
}

private fun JsonElement?.toEpochMillisOrZero(): Long {
    val element = this ?: return 0L
    if (element is JsonPrimitive && element.isNumber) return element.asLong
    val raw = if (element is JsonPrimitive && element.isString) element.asString else return 0L
    return raw.toLongOrNull() ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)
}
