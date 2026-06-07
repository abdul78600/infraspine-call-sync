package com.infraspine.callsync.domain.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Thin wrapper over ConnectivityManager used to honor the "Sync only on Wi-Fi" setting
 * and to short-circuit sync attempts when there is no network at all.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun isConnected(): Boolean {
        val capabilities = activeCapabilities() ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isOnWifi(): Boolean {
        val capabilities = activeCapabilities() ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun activeCapabilities(): NetworkCapabilities? {
        val cm = connectivityManager ?: return null
        val network = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(network)
    }
}
