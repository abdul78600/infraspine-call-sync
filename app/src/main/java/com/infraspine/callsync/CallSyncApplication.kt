package com.infraspine.callsync

import android.app.Application
import com.infraspine.callsync.domain.util.NetworkDiagnostics
import com.infraspine.callsync.sync.SyncScheduler

class CallSyncApplication : Application() {

    val container: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onCreate() {
        super.onCreate()

        NetworkDiagnostics.logConfiguredServer(container.settingsStore.crmServerUrl)

        // Re-apply the auto-sync schedule on every process start so a reboot or
        // app update doesn't silently drop a previously enabled background sync.
        SyncScheduler.apply(
            context = this,
            autoSyncEnabled = container.settingsStore.autoSyncEnabled,
            wifiOnly = container.settingsStore.syncOnWifiOnly
        )
    }
}
