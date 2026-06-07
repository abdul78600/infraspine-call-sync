package com.infraspine.callsync.domain.util

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.infraspine.callsync.data.prefs.SecureSettingsStore

/**
 * Resolves a stable per-install device identifier sent with each upload so the
 * CRM can attribute recordings to the originating device. Falls back to a random
 * UUID (persisted) if ANDROID_ID is unavailable, and caches the result in
 * [SecureSettingsStore] so it never changes across app runs.
 */
object DeviceIdProvider {

    @SuppressLint("HardwareIds")
    fun getOrCreate(context: Context, settingsStore: SecureSettingsStore): String {
        settingsStore.deviceId?.let { return it }

        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()

        val resolved = androidId?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
            ?: java.util.UUID.randomUUID().toString()

        settingsStore.deviceId = resolved
        return resolved
    }
}
