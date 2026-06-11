package com.infraspine.callsync.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.edit
import com.infraspine.callsync.domain.sync.SyncProfile

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
        get() = prefs.getBoolean(KEY_AUTO_SYNC, false)
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
        SyncProfile.keyFor(crmServerUrl, userId, deviceId)

    /**
     * Per-profile call-log cursor: the highest device CallLog `_ID` successfully
     * synced for this profile. Falls back to (and one-time-seeds from) the legacy
     * global cursor for users upgrading from before per-profile cursors existed.
     */
    fun lastSyncedCallLogId(profileKey: String): Long {
        val key = callLogCursorKey(profileKey)
        if (prefs.contains(key)) return prefs.getLong(key, 0L)

        // First read for this profile: seed from the legacy global cursor (if any
        // profile-scoped cursor exists already, a different profile has already
        // claimed the legacy value, so this profile starts at 0).
        val legacyValue = prefs.getLong(KEY_LAST_SYNCED_CALL_LOG_ID, 0L)
        val anyProfileCursorExists = prefs.all.keys.any { it.startsWith(KEY_LAST_SYNCED_CALL_LOG_ID_PREFIX) }
        val seed = if (legacyValue > 0L && !anyProfileCursorExists) legacyValue else 0L
        prefs.edit { putLong(key, seed) }
        return seed
    }

    fun setLastSyncedCallLogId(profileKey: String, value: Long) {
        prefs.edit { putLong(callLogCursorKey(profileKey), value) }
    }

    /** Clears only this profile's call-log cursor (used by "Reset sync history"). */
    fun resetCallLogCursor(profileKey: String) {
        prefs.edit { remove(callLogCursorKey(profileKey)) }
    }

    private fun callLogCursorKey(profileKey: String) = "$KEY_LAST_SYNCED_CALL_LOG_ID_PREFIX$profileKey"

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
        private const val KEY_LAST_SYNCED_CALL_LOG_ID_PREFIX = "last_synced_call_log_id_"
    }
}
