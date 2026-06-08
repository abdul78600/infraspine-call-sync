package com.infraspine.callsync

import android.app.Application
import com.infraspine.callsync.domain.util.NetworkDiagnostics
import com.infraspine.callsync.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CallSyncApplication : Application() {

    val container: AppContainer by lazy { AppContainer(applicationContext) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        NetworkDiagnostics.logConfiguredServer(container.settingsStore.crmServerUrl)

        // Re-apply the auto-sync schedule on every process start so a reboot or
        // app update doesn't silently drop a previously enabled background sync.
        SyncScheduler.apply(
            context = this,
            autoSyncEnabled = container.settingsStore.autoSyncEnabled && container.settingsStore.hasValidSession(),
            wifiOnly = container.settingsStore.syncOnWifiOnly
        )

        if (container.settingsStore.hasValidSession()) {
            applicationScope.launch {
                container.syncRepository.syncCallLogsOnly()
            }
        }
    }
}
