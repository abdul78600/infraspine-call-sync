package com.infraspine.callsync.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.provider.CallLog
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

/**
 * Enables/disables the periodic [AutoSyncWorker] in response to the "Auto sync" toggle
 * and keeps its network constraint aligned with the "Sync only on Wi-Fi" setting.
 * Call [apply] whenever either setting changes (including on app start).
 */
object SyncScheduler {

    private const val PERIODIC_INTERVAL_MINUTES = 15L
    const val CALL_ENDED_DELAY_SECONDS = 20L
    const val NETWORK_RESTORED_DELAY_SECONDS = 5L

    fun apply(context: Context, autoSyncEnabled: Boolean, wifiOnly: Boolean) {
        val workManager = WorkManager.getInstance(context.applicationContext)

        if (!autoSyncEnabled) {
            workManager.cancelUniqueWork(AutoSyncWorker.UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(AutoSyncWorker.ON_DEMAND_UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(CallEndedSyncWorker.UNIQUE_WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(networkConstraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            AutoSyncWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun enqueueOneTime(
        context: Context,
        wifiOnly: Boolean,
        delaySeconds: Long,
        replaceExisting: Boolean = false
    ) {
        val request = OneTimeWorkRequestBuilder<AutoSyncWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(networkConstraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                AutoSyncWorker.ON_DEMAND_UNIQUE_WORK_NAME,
                if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
    }

    fun enqueueCallEndedCheck(context: Context, delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<CallEndedSyncWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()

        // KEEP: if a verification worker is already queued/running for a previous
        // IDLE broadcast, don't replace it. Rapid duplicate IDLE broadcasts (common
        // on OEM ROMs) would otherwise keep resetting the 20-second delay and the
        // sync would never fire.
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                CallEndedSyncWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
    }

    /**
     * Schedules a [JobScheduler] content-URI trigger on [CallLog.Calls.CONTENT_URI].
     * The OS fires [CallLogChangeJobService] whenever a call-log row is inserted or
     * updated — even when the app process is completely dead — which is far more
     * reliable than the [android.intent.action.PHONE_STATE] broadcast on OEM ROMs
     * that suppress the non-privileged IDLE delivery for some call types.
     *
     * Call this from [CallSyncApplication.onCreate] and [BootCompletedReceiver] so
     * the trigger survives reboots.
     */
    fun scheduleCallLogChangeJob(context: Context) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE)
            as? JobScheduler ?: return

        val jobInfo = JobInfo.Builder(
            CALL_LOG_CHANGE_JOB_ID,
            ComponentName(context.applicationContext, CallLogChangeJobService::class.java)
        )
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    CallLog.Calls.CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                )
            )
            // Batch rapid inserts (e.g. a call that writes multiple rows) within 5 s.
            .setTriggerContentUpdateDelay(5_000L)
            // Never delay more than 30 s from the first change, even if rows keep updating.
            .setTriggerContentMaxDelay(30_000L)
            // NOTE: setPersisted(true) is incompatible with addTriggerContentUri().
            // The job is rescheduled on every app start (Application.onCreate) and on
            // boot (BootCompletedReceiver), so it survives process death reliably.
            .build()

        jobScheduler.schedule(jobInfo)
    }

    private const val CALL_LOG_CHANGE_JOB_ID = 1001

    private fun networkConstraints(wifiOnly: Boolean): Constraints {
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        return Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()
    }
}
