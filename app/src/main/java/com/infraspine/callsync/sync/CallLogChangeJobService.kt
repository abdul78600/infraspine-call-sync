package com.infraspine.callsync.sync

import android.app.job.JobParameters
import android.app.job.JobService
import com.infraspine.callsync.data.prefs.SecureSettingsStore
import com.infraspine.callsync.domain.util.NetworkDiagnostics

/**
 * Triggered by [JobScheduler] whenever a row is inserted or updated in
 * [android.provider.CallLog.Calls.CONTENT_URI]. This is more reliable than the
 * [android.intent.action.PHONE_STATE] broadcast on OEM ROMs (e.g. Transsion /
 * MTK) that suppress the non-privileged IDLE broadcast for some call types.
 *
 * The job fires while the app is dead or alive, requires no special permissions
 * beyond READ_CALL_LOG, and is rescheduled automatically across reboots via
 * [BootCompletedReceiver]. [SyncScheduler.scheduleCallLogChangeJob] keeps the
 * job active.
 *
 * A 5-second [JobInfo.Builder.setTriggerContentUpdateDelay] batches rapid
 * consecutive inserts (e.g. a call that writes multiple rows) into a single
 * trigger. A 30-second [JobInfo.Builder.setTriggerContentMaxDelay] caps the
 * maximum latency so the sync fires within 30 s of the first change even if the
 * call log keeps updating.
 */
class CallLogChangeJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val appContext = applicationContext
        val settingsStore = SecureSettingsStore(appContext)

        if (!settingsStore.autoSyncEnabled || !settingsStore.hasValidSession()) {
            NetworkDiagnostics.logCallLogSyncSkipped(
                "call-log content change ignored: auto-sync off or no session"
            )
            jobFinished(params, false)
            return false
        }

        NetworkDiagnostics.logCallLogSyncSkipped(
            "call-log content change detected: queuing on-demand sync"
        )
        SyncScheduler.enqueueOneTime(
            context = appContext,
            wifiOnly = settingsStore.syncOnWifiOnly,
            delaySeconds = 0L,
            replaceExisting = false  // KEEP: don't interrupt a sync already in flight
        )

        // Content-URI jobs are one-shot — reschedule immediately so the next
        // call log change is also detected without requiring an app restart.
        SyncScheduler.scheduleCallLogChangeJob(appContext)

        jobFinished(params, false)
        return false  // work is done synchronously
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
