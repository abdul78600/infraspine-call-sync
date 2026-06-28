package com.infraspine.callsync.sync

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.infraspine.callsync.data.prefs.SecureSettingsStore
import com.infraspine.callsync.domain.util.NetworkDiagnostics

/**
 * Receives phone-state changes from the OS and schedules a near-term sync after
 * calls return to IDLE. The short delay gives Android's CallLog provider time to
 * persist the finished call before the worker reads it.
 */
class CallEndedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        if (state != TelephonyManager.EXTRA_STATE_IDLE) return

        val appContext = context.applicationContext
        if (!hasPermission(appContext, Manifest.permission.READ_CALL_LOG) ||
            !hasPermission(appContext, Manifest.permission.READ_PHONE_STATE)
        ) {
            NetworkDiagnostics.logCallLogSyncSkipped("call-ended trigger ignored because phone/call-log permission is missing")
            return
        }

        val settingsStore = SecureSettingsStore(appContext)
        if (!settingsStore.autoSyncEnabled || !settingsStore.hasValidSession()) {
            NetworkDiagnostics.logCallLogSyncSkipped("call-ended trigger ignored because auto sync/session is not active")
            return
        }

        SyncScheduler.enqueueCallEndedCheck(
            context = appContext,
            delaySeconds = SyncScheduler.CALL_ENDED_DELAY_SECONDS
        )
        NetworkDiagnostics.logCallLogSyncSkipped("call-ended trigger queued call-log verification")
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
