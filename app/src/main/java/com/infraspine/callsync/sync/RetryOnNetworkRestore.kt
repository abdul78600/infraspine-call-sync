package com.infraspine.callsync.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.infraspine.callsync.domain.util.NetworkDiagnostics
import java.util.concurrent.TimeUnit

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
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    AutoSyncWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<AutoSyncWorker>()
                        .setInitialDelay(5, TimeUnit.SECONDS)
                        .build()
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
