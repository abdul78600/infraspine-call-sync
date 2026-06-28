package com.infraspine.callsync.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.infraspine.callsync.data.prefs.SecureSettingsStore

/**
 * Re-applies the unique periodic sync work after boot/user unlock so background
 * call-log sync resumes even before the user manually reopens the app.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_USER_UNLOCKED) return

        runCatching {
            val appContext = context.applicationContext
            val settingsStore = SecureSettingsStore(appContext)
            SyncScheduler.apply(
                context = appContext,
                autoSyncEnabled = settingsStore.autoSyncEnabled && settingsStore.hasValidSession(),
                wifiOnly = settingsStore.syncOnWifiOnly
            )
            // Content-URI JobScheduler trigger does not survive reboots unless
            // setPersisted(true) is used AND we reschedule it here on boot.
            SyncScheduler.scheduleCallLogChangeJob(appContext)
        }
    }
}
