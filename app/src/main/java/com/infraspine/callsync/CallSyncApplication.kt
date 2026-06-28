package com.infraspine.callsync

import android.app.Application
import com.infraspine.callsync.domain.util.NetworkDiagnostics
import com.infraspine.callsync.sync.RetryOnNetworkRestore
import com.infraspine.callsync.sync.SyncNotificationChannel
import com.infraspine.callsync.sync.SyncScheduler

class CallSyncApplication : Application() {

    val container: AppContainer by lazy { AppContainer(applicationContext) }

    private val retryOnNetworkRestore by lazy { RetryOnNetworkRestore(applicationContext) }

    override fun onCreate() {
        super.onCreate()

        SyncNotificationChannel.create(this)

        NetworkDiagnostics.logConfiguredServer(container.settingsStore.crmServerUrl)

        SyncScheduler.apply(
            context = this,
            autoSyncEnabled = container.settingsStore.autoSyncEnabled && container.settingsStore.hasValidSession(),
            wifiOnly = container.settingsStore.syncOnWifiOnly
        )

        retryOnNetworkRestore.register()

        // Schedule a JobScheduler content-URI trigger on CallLog.Calls so a sync
        // fires whenever a new call-log row is written — even when the app is dead.
        // This is the primary, reliable call-detection path; PHONE_STATE broadcast
        // is kept as a secondary path for OEM ROMs that suppress content-provider
        // change events for certain call types.
        SyncScheduler.scheduleCallLogChangeJob(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        retryOnNetworkRestore.unregister()
    }
}
