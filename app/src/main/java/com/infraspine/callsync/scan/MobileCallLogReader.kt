package com.infraspine.callsync.scan

import android.content.Context
import android.provider.CallLog
import com.infraspine.callsync.domain.model.CallType
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
        val logs = mutableListOf<MobileCallLog>()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME
        )
        val selection = when {
            lastSyncedAndroidCallLogId > 0L -> "${CallLog.Calls._ID} > ?"
            lastSyncedCallStartedAt > 0L -> "${CallLog.Calls.DATE} >= ?"
            else -> null
        }
        val selectionArgs = when {
            lastSyncedAndroidCallLogId > 0L -> arrayOf(lastSyncedAndroidCallLogId.toString())
            lastSyncedCallStartedAt > 0L -> arrayOf(lastSyncedCallStartedAt.toString())
            else -> null
        }
        val sortOrder = if (lastSyncedAndroidCallLogId > 0L) {
            "${CallLog.Calls._ID} ASC"
        } else {
            "${CallLog.Calls.DATE} ASC, ${CallLog.Calls._ID} ASC"
        }

        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
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

                while (cursor.moveToNext()) {
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
        }

        logs
    }

    suspend fun loadNewerThan(lastSyncedCallLogId: Long): List<MobileCallLog> =
        loadAfterCursor(lastSyncedAndroidCallLogId = lastSyncedCallLogId, lastSyncedCallStartedAt = 0L)

    suspend fun loadLatestCallLog(): MobileCallLog? = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME
        )
        var latest: MobileCallLog? = null

        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
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
        }

        latest
    }
}
