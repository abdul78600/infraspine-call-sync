package com.infraspine.callsync.domain.util

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private const val TAG = "CrmConnection"

/**
 * Logs the CRM connection setup at process start (URL + scheme only — never the
 * agent token) and turns low-level network exceptions into the user-facing
 * categories from the upload error UI. Centralized here so [com.infraspine.callsync
 * .data.remote.RealCrmUploader] and any future callers classify failures identically.
 */
object NetworkDiagnostics {

    /** Logs the configured CRM URL and whether it is HTTP or HTTPS. Safe to call with a null/blank URL. */
    fun logConfiguredServer(rawUrl: String?) {
        val url = rawUrl?.takeIf { it.isNotBlank() }
        if (url == null) {
            Log.i(TAG, "CRM Server URL is not configured")
            return
        }

        val httpUrl = url.toHttpUrlOrNull()
        val scheme = httpUrl?.scheme?.uppercase() ?: "UNKNOWN"
        val host = httpUrl?.host ?: "unknown-host"
        val isCleartext = httpUrl?.scheme == "http"

        Log.i(TAG, "Configured CRM server: scheme=$scheme host=$host")
        if (isCleartext) {
            Log.w(TAG, "CRM server uses HTTP (cleartext) — suitable for development/LAN servers only")
        }
    }

    /**
     * Maps a failed upload attempt to a short, user-facing category. [throwable] is
     * the exception thrown while performing the request (or null for an HTTP error
     * response, in which case [httpCode] should be supplied instead).
     *
     * The returned message never includes request/response bodies or headers, so the
     * Authorization header can never leak through error reporting.
     */
    fun classify(throwable: Throwable? = null, httpCode: Int? = null): String {
        if (httpCode != null) {
            return when (httpCode) {
                401 -> "Unauthorized — check the agent token in Settings"
                403 -> "Forbidden — this device is not allowed to upload"
                else -> "Server responded with $httpCode"
            }
        }

        if (isCleartextBlocked(throwable)) {
            return "HTTP blocked by Android security policy"
        }

        return when (throwable) {
            is UnknownHostException ->
                "DNS resolution failure — check the server hostname in Settings"
            is ConnectException ->
                "Connection refused — server is unreachable at the configured address"
            is SocketTimeoutException ->
                "Timeout — server did not respond in time"
            is SSLException ->
                "SSL certificate error — the server's certificate could not be verified"
            is IOException ->
                "Network error — ${throwable.message ?: "request failed"}"
            else ->
                "Network error — ${throwable?.message ?: "request failed"}"
        }
    }

    /** Logs a connection failure with its category — never the raw exception, which could echo the request URL. */
    fun logConnectionFailure(category: String) {
        Log.w(TAG, "Upload connection failed: $category")
    }

    /**
     * Android (via [android.security.NetworkSecurityPolicy]) refuses to even open a
     * plaintext socket to a host that cleartext isn't permitted for, surfacing it as
     * a plain `IOException("Cleartext HTTP traffic to <host> not permitted")`.
     */
    private fun isCleartextBlocked(throwable: Throwable?): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current.message?.contains("CLEARTEXT", ignoreCase = true) == true) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
