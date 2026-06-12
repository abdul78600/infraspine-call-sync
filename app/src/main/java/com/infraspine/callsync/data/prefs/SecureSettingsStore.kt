package com.infraspine.callsync.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.edit
import com.infraspine.callsync.domain.sync.SyncProfile

data class CallLogSyncCursor(
    val lastSyncedCallStartedAt: Long = 0L,
    val lastSyncedAndroidCallLogId: Long = 0L,
    val lastCallLogSyncAt: Long = 0L
) {
    fun isEmpty(): Boolean = lastSyncedCallStartedAt <= 0L && lastSyncedAndroidCallLogId <= 0L
}

data class CallLogSyncStateSnapshot(
    val latestCallStartedAt: Long = 0L,
    val latestAndroidCallLogId: Long = 0L,
    val totalLogs: Int = 0
) {
    fun isEmpty(): Boolean = latestCallStartedAt <= 0L && latestAndroidCallLogId <= 0L && totalLogs <= 0
}

/**
 * Stores CRM connection config and authenticated session details using
 * EncryptedSharedPreferences, so tokens are encrypted at rest and never land in
 * plaintext prefs or logs.
 */
class SecureSettingsStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var crmServerUrl: String?
        get() = prefs.getString(KEY_CRM_URL, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { putString(KEY_CRM_URL, value?.trim()) }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { putString(KEY_ACCESS_TOKEN, value?.trim()) }

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { putString(KEY_USER_ID, value?.trim()) }

    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { putString(KEY_USER_NAME, value?.trim()) }

    var userEmail: String?
        get() = prefs.getString(KEY_USER_EMAIL, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { putString(KEY_USER_EMAIL, value?.trim()) }

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit { putString(KEY_DEVICE_ID, value) }

    var selectedFolderUri: String?
        get() = prefs.getString(KEY_FOLDER_URI, null)
        set(value) = prefs.edit { putString(KEY_FOLDER_URI, value) }

    var syncOnWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit { putBoolean(KEY_WIFI_ONLY, value) }

    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_SYNC, value) }

    /** When true, uploads are simulated locally and never hit the network. */
    var dummyTestMode: Boolean
        get() = prefs.getBoolean(KEY_DUMMY_MODE, true)
        set(value) = prefs.edit { putBoolean(KEY_DUMMY_MODE, value) }

    /**
     * The active sync profile key for (crmServerUrl, userId, deviceId). Cursors and
     * "reset sync history" are scoped to this key so different servers/accounts/devices
     * never share sync state.
     */
    fun activeSyncProfileKey(deviceId: String): String =
        SyncProfile.keyFor(crmServerUrl, userId ?: userEmail ?: userName, deviceId)

    /**
     * Per-profile call-log cursor used for incremental sync. Falls back to the
     * legacy global `_ID` cursor as a one-time migration seed.
     */
    fun callLogCursor(profileKey: String): CallLogSyncCursor {
        val idKey = callLogCursorIdKey(profileKey)
        val startedAtKey = callLogCursorStartedAtKey(profileKey)
        val syncAtKey = callLogCursorSyncAtKey(profileKey)

        if (prefs.contains(idKey) || prefs.contains(startedAtKey) || prefs.contains(syncAtKey)) {
            return CallLogSyncCursor(
                lastSyncedCallStartedAt = prefs.getLong(startedAtKey, 0L),
                lastSyncedAndroidCallLogId = prefs.getLong(idKey, 0L),
                lastCallLogSyncAt = prefs.getLong(syncAtKey, 0L)
            )
        }

        val legacyValue = prefs.getLong(KEY_LAST_SYNCED_CALL_LOG_ID, 0L)
        val anyProfileCursorExists = prefs.all.keys.any { it.startsWith(KEY_LAST_SYNCED_CALL_LOG_ID_PREFIX) }
        val seed = if (legacyValue > 0L && !anyProfileCursorExists) legacyValue else 0L
        val cursor = CallLogSyncCursor(lastSyncedAndroidCallLogId = seed)
        setCallLogCursor(profileKey, cursor)
        return cursor
    }

    fun setCallLogCursor(profileKey: String, cursor: CallLogSyncCursor) {
        prefs.edit {
            putLong(callLogCursorStartedAtKey(profileKey), cursor.lastSyncedCallStartedAt)
            putLong(callLogCursorIdKey(profileKey), cursor.lastSyncedAndroidCallLogId)
            putLong(callLogCursorSyncAtKey(profileKey), cursor.lastCallLogSyncAt)
        }
    }

    fun resetCallLogCursor(profileKey: String) {
        prefs.edit {
            remove(callLogCursorStartedAtKey(profileKey))
            remove(callLogCursorIdKey(profileKey))
            remove(callLogCursorSyncAtKey(profileKey))
        }
    }

    fun callLogSyncState(profileKey: String): CallLogSyncStateSnapshot =
        CallLogSyncStateSnapshot(
            latestCallStartedAt = prefs.getLong(callLogSyncStateStartedAtKey(profileKey), 0L),
            latestAndroidCallLogId = prefs.getLong(callLogSyncStateIdKey(profileKey), 0L),
            totalLogs = prefs.getInt(callLogSyncStateTotalLogsKey(profileKey), 0)
        )

    fun setCallLogSyncState(profileKey: String, state: CallLogSyncStateSnapshot) {
        prefs.edit {
            putLong(callLogSyncStateStartedAtKey(profileKey), state.latestCallStartedAt)
            putLong(callLogSyncStateIdKey(profileKey), state.latestAndroidCallLogId)
            putInt(callLogSyncStateTotalLogsKey(profileKey), state.totalLogs)
        }
    }

    fun clearCallLogSyncState(profileKey: String) {
        prefs.edit {
            remove(callLogSyncStateStartedAtKey(profileKey))
            remove(callLogSyncStateIdKey(profileKey))
            remove(callLogSyncStateTotalLogsKey(profileKey))
        }
    }

    fun isCallLogResetRequested(profileKey: String): Boolean =
        prefs.getBoolean(callLogResetRequestedKey(profileKey), false)

    fun setCallLogResetRequested(profileKey: String, requested: Boolean) {
        prefs.edit {
            if (requested) {
                putBoolean(callLogResetRequestedKey(profileKey), true)
            } else {
                remove(callLogResetRequestedKey(profileKey))
            }
        }
    }

    fun lastSyncedCallLogId(profileKey: String): Long = callLogCursor(profileKey).lastSyncedAndroidCallLogId

    fun setLastSyncedCallLogId(profileKey: String, value: Long) {
        val current = callLogCursor(profileKey)
        setCallLogCursor(
            profileKey,
            current.copy(lastSyncedAndroidCallLogId = value)
        )
    }

    private fun callLogCursorStartedAtKey(profileKey: String) = "$KEY_LAST_SYNCED_CALL_STARTED_AT_PREFIX$profileKey"
    private fun callLogCursorIdKey(profileKey: String) = "$KEY_LAST_SYNCED_CALL_LOG_ID_PREFIX$profileKey"
    private fun callLogCursorSyncAtKey(profileKey: String) = "$KEY_LAST_CALL_LOG_SYNC_AT_PREFIX$profileKey"
    private fun callLogSyncStateStartedAtKey(profileKey: String) = "$KEY_SERVER_CALL_STARTED_AT_PREFIX$profileKey"
    private fun callLogSyncStateIdKey(profileKey: String) = "$KEY_SERVER_CALL_LOG_ID_PREFIX$profileKey"
    private fun callLogSyncStateTotalLogsKey(profileKey: String) = "$KEY_SERVER_TOTAL_LOGS_PREFIX$profileKey"
    private fun callLogResetRequestedKey(profileKey: String) = "$KEY_CALL_LOG_RESET_REQUESTED_PREFIX$profileKey"

    fun isCrmConfigured(): Boolean =
        !crmServerUrl.isNullOrBlank() && !accessToken.isNullOrBlank()

    fun hasValidSession(): Boolean = !accessToken.isNullOrBlank()

    fun clearAuth() {
        prefs.edit {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_USER_ID)
            remove(KEY_USER_NAME)
            remove(KEY_USER_EMAIL)
        }
    }

    companion object {
        private const val PREFS_FILE_NAME = "secure_prefs"

        private const val KEY_CRM_URL = "crm_server_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_FOLDER_URI = "selected_folder_uri"
        private const val KEY_WIFI_ONLY = "sync_wifi_only"
        private const val KEY_AUTO_SYNC = "auto_sync_enabled"
        private const val KEY_DUMMY_MODE = "dummy_test_mode"

        /** Legacy global cursor, kept only as a one-time seed source for per-profile cursors. */
        private const val KEY_LAST_SYNCED_CALL_LOG_ID = "last_synced_call_log_id"
        private const val KEY_LAST_SYNCED_CALL_STARTED_AT_PREFIX = "last_synced_call_started_at_"
        private const val KEY_LAST_SYNCED_CALL_LOG_ID_PREFIX = "last_synced_call_log_id_"
        private const val KEY_LAST_CALL_LOG_SYNC_AT_PREFIX = "last_call_log_sync_at_"
        private const val KEY_SERVER_CALL_STARTED_AT_PREFIX = "server_call_started_at_"
        private const val KEY_SERVER_CALL_LOG_ID_PREFIX = "server_call_log_id_"
        private const val KEY_SERVER_TOTAL_LOGS_PREFIX = "server_total_logs_"
        private const val KEY_CALL_LOG_RESET_REQUESTED_PREFIX = "call_log_reset_requested_"
    }
}
