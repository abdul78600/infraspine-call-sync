package com.infraspine.callsync.scan

import android.content.Context
import android.provider.CallLog
import com.infraspine.callsync.domain.model.CallType
import com.infraspine.callsync.domain.util.NetworkDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MobileCallLog(
    val id: Long,
    val phoneNumber: String?,
    val callType: CallType,
    val startedAt: Long,
    val durationSeconds: Long,
    val cachedContactName: String?
)

/**
 * Reads Android's call-log provider for CRM call-log sync. Requires READ_CALL_LOG;
 * callers are responsible for checking permission before invoking it.
 */
class MobileCallLogReader(private val context: Context) {

    suspend fun loadAfterCursor(
        lastSyncedAndroidCallLogId: Long,
        lastSyncedCallStartedAt: Long
    ): List<MobileCallLog> = withContext(Dispatchers.IO) {
        val selection = when {
            lastSyncedCallStartedAt > 0L && lastSyncedAndroidCallLogId > 0L ->
                "(${CallLog.Calls.DATE} > ? OR (${CallLog.Calls.DATE} = ? AND ${CallLog.Calls._ID} > ?))"
            lastSyncedCallStartedAt > 0L -> "${CallLog.Calls.DATE} >= ?"
            lastSyncedAndroidCallLogId > 0L -> "${CallLog.Calls._ID} > ?"
            else -> null
        }
        val selectionArgs = when {
            lastSyncedCallStartedAt > 0L && lastSyncedAndroidCallLogId > 0L ->
                arrayOf(
                    lastSyncedCallStartedAt.toString(),
                    lastSyncedCallStartedAt.toString(),
                    lastSyncedAndroidCallLogId.toString()
                )
            lastSyncedCallStartedAt > 0L -> arrayOf(lastSyncedCallStartedAt.toString())
            lastSyncedAndroidCallLogId > 0L -> arrayOf(lastSyncedAndroidCallLogId.toString())
            else -> null
        }
        val sortOrder = "${CallLog.Calls.DATE} ASC, ${CallLog.Calls._ID} ASC"

        load(
            label = "after_cursor(startedAt=$lastSyncedCallStartedAt,id=$lastSyncedAndroidCallLogId)",
            selection = selection,
            selectionArgs = selectionArgs,
            sortOrder = sortOrder,
            limit = FETCH_LIMIT
        )
    }

    suspend fun loadNewerThan(lastSyncedCallLogId: Long): List<MobileCallLog> =
        loadAfterCursor(lastSyncedAndroidCallLogId = lastSyncedCallLogId, lastSyncedCallStartedAt = 0L)

    suspend fun loadSince(startedAtMillis: Long): List<MobileCallLog> = withContext(Dispatchers.IO) {
        if (startedAtMillis <= 0L) {
            load(
                label = "since(all)",
                selection = null,
                selectionArgs = null,
                sortOrder = "${CallLog.Calls.DATE} ASC, ${CallLog.Calls._ID} ASC",
                limit = FETCH_LIMIT
            )
        } else {
            load(
                label = "since(startedAt=$startedAtMillis)",
                selection = "${CallLog.Calls.DATE} >= ?",
                selectionArgs = arrayOf(startedAtMillis.toString()),
                sortOrder = "${CallLog.Calls.DATE} ASC, ${CallLog.Calls._ID} ASC",
                limit = FETCH_LIMIT
            )
        }
    }

    suspend fun loadLatestCallLog(): MobileCallLog? = withContext(Dispatchers.IO) {
        var latest: MobileCallLog? = null

        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                PROJECTION,
                null,
                null,
                "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val idIdx = cursor.getColumnIndex(CallLog.Calls._ID)
                val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val cachedNameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val id = idIdx.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: return@use
                latest = MobileCallLog(
                    id = id,
                    phoneNumber = numberIdx.takeIf { it >= 0 }?.let { cursor.getString(it) },
                    callType = typeIdx.takeIf { it >= 0 }
                        ?.let { CallType.fromCallLogType(cursor.getInt(it)) }
                        ?: CallType.UNKNOWN,
                    startedAt = dateIdx.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                    durationSeconds = durationIdx.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                    cachedContactName = cachedNameIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }
                )
            }
        }.onFailure {
            NetworkDiagnostics.logCallLogProviderFailure("latest", it)
        }
        NetworkDiagnostics.logCallLogProviderRead(
            label = "latest",
            count = if (latest == null) 0 else 1,
            first = latest?.summary().orEmpty(),
            last = latest?.summary().orEmpty()
        )

        latest
    }

    private fun load(
        label: String,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String,
        limit: Int = FETCH_LIMIT
    ): List<MobileCallLog> {
        val logs = mutableListOf<MobileCallLog>()

        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                PROJECTION,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CallLog.Calls._ID)
                val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val cachedNameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)

                while (cursor.moveToNext() && logs.size < limit) {
                    val id = idIdx.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: continue
                    logs += MobileCallLog(
                        id = id,
                        phoneNumber = numberIdx.takeIf { it >= 0 }?.let { cursor.getString(it) },
                        callType = typeIdx.takeIf { it >= 0 }
                            ?.let { CallType.fromCallLogType(cursor.getInt(it)) }
                            ?: CallType.UNKNOWN,
                        startedAt = dateIdx.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                        durationSeconds = durationIdx.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                        cachedContactName = cachedNameIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }
                    )
                }
            }
        }.onFailure {
            NetworkDiagnostics.logCallLogProviderFailure(label, it)
        }
        NetworkDiagnostics.logCallLogProviderRead(
            label = label,
            count = logs.size,
            first = logs.firstOrNull()?.summary().orEmpty(),
            last = logs.lastOrNull()?.summary().orEmpty()
        )

        return logs
    }

    private fun MobileCallLog.summary(): String =
        "{id=$id,startedAt=$startedAt,type=$callType,hasPhone=${!phoneNumber.isNullOrBlank()}}"

    companion object {
        private val PROJECTION = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME
        )

        /** Max call logs fetched per sync cycle. Caps memory use and content-provider
         *  query time on devices with large call histories. The cursor advances after
         *  each successful cycle so subsequent syncs pick up where this one stopped. */
        const val FETCH_LIMIT = 2000
    }
}
