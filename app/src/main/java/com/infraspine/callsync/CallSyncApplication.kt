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
    }

    override fun onTerminate() {
        super.onTerminate()
        retryOnNetworkRestore.unregister()
    }
}
