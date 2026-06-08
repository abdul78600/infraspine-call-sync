package com.infraspine.callsync.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.edit

/**
 * Stores CRM connection config and the agent token using EncryptedSharedPreferences,
 * so the token is encrypted at rest and never lands in plaintext prefs or logs.
 *
 * Read access to [agentToken] should remain narrow — never log it, never include it
 * in crash reports or analytics.
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

    var agentToken: String?
        get() = prefs.getString(KEY_AGENT_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { putString(KEY_AGENT_TOKEN, value?.trim()) }

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

    var lastSyncedCallLogId: Long
        get() = prefs.getLong(KEY_LAST_SYNCED_CALL_LOG_ID, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SYNCED_CALL_LOG_ID, value) }

    fun isCrmConfigured(): Boolean =
        !crmServerUrl.isNullOrBlank() && !agentToken.isNullOrBlank()

    companion object {
        private const val PREFS_FILE_NAME = "secure_prefs"

        private const val KEY_CRM_URL = "crm_server_url"
        private const val KEY_AGENT_TOKEN = "agent_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_FOLDER_URI = "selected_folder_uri"
        private const val KEY_WIFI_ONLY = "sync_wifi_only"
        private const val KEY_AUTO_SYNC = "auto_sync_enabled"
        private const val KEY_DUMMY_MODE = "dummy_test_mode"
        private const val KEY_LAST_SYNCED_CALL_LOG_ID = "last_synced_call_log_id"
    }
}
