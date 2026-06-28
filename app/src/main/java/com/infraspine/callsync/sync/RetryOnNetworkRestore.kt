package com.infraspine.callsync.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.infraspine.callsync.data.prefs.SecureSettingsStore
import com.infraspine.callsync.domain.util.NetworkDiagnostics

/**
 * Registers a ConnectivityManager.NetworkCallback so that whenever the device
 * regains internet access a one-time AutoSyncWorker is enqueued immediately.
 * This covers the gap between 15-minute periodic syncs: if recordings failed
 * while offline they are retried as soon as connectivity is restored.
 *
 * Uses the same unique work name as the periodic worker so WorkManager
 * deduplicates them — only one AutoSyncWorker runs at a time even if the
 * network comes back while a periodic run is already queued.
 */
class RetryOnNetworkRestore(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val settingsStore = SecureSettingsStore(context.applicationContext)
            if (!settingsStore.autoSyncEnabled || !settingsStore.hasValidSession()) return
            SyncScheduler.enqueueOneTime(
                context = context.applicationContext,
                wifiOnly = settingsStore.syncOnWifiOnly,
                delaySeconds = SyncScheduler.NETWORK_RESTORED_DELAY_SECONDS
            )
        }
    }

    fun register() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, networkCallback) }
            .onFailure { NetworkDiagnostics.logUnexpectedFailure("network callback registration", it) }
    }

    fun unregister() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }
}
