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
 * Logs the CRM connection setup at process start (URL + scheme only, never the
 * access token) and turns low-level network exceptions into the user-facing
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
     * [serverMessage] — when present (extracted from the response body by
     * [UploadErrorParser]) — is shown instead of the generic per-code text, so
     * validation errors like "file is required" or "phoneNumber invalid" reach
     * the user verbatim rather than as "Server responded with 400".
     *
     * The returned message never includes request/response bodies or headers, so the
     * Authorization header can never leak through error reporting.
     */
    fun classify(throwable: Throwable? = null, httpCode: Int? = null, serverMessage: String? = null): String {
        if (httpCode != null) {
            val message = serverMessage?.takeIf { it.isNotBlank() }
            return when (httpCode) {
                401 -> message ?: "Unauthorized — please log in to CRM again"
                403 -> message ?: "Forbidden — this device is not allowed to upload"
                400 -> message ?: "Server rejected the upload (400) — no validation details returned"
                else -> if (message != null) "Server responded with $httpCode: $message" else "Server responded with $httpCode"
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
     * Logs every field that goes into the multipart upload request, plus the final
     * resolved URL, but never the Authorization header/access token. Intended to make
     * "why did the server reject this with a 400" diagnosable from logcat alone.
     */
    fun logUploadRequest(
        fileExists: Boolean,
        actualFileSize: Long,
        filePath: String,
        fileName: String,
        fileSize: Long,
        mimeType: String?,
        fileExtension: String,
        multipartFieldNames: List<String>,
        phoneNumber: String?,
        callStartedAt: String?,
        durationSeconds: Long?,
        callType: String,
        deviceId: String,
        uploadUrl: String
    ) {
        Log.d(
            TAG,
            "Upload request: fileExists=$fileExists actualFileSize=$actualFileSize filePath=$filePath " +
                "fileName=$fileName scannedFileSize=$fileSize mimeType=$mimeType extension=$fileExtension " +
                "multipartFields=${multipartFieldNames.joinToString(",")} phoneNumber=$phoneNumber callStartedAt=$callStartedAt " +
                "durationSeconds=$durationSeconds callType=$callType deviceId=$deviceId url=$uploadUrl"
        )
    }

    /** Logs the raw HTTP status and response body for a completed (non-exceptional) upload response. */
    fun logUploadResponse(httpCode: Int, rawBody: String?) {
        Log.d(TAG, "Upload response: status=$httpCode serverErrorBody=${rawBody?.takeIf { it.isNotBlank() } ?: "<empty>"}")
    }

    fun logCallLogSync(
        totalFetched: Int,
        uploaded: Int,
        skipped: Int,
        failed: Int,
        lastSyncedCallLogId: Long
    ) {
        Log.d(
            TAG,
            "Call log sync: totalFetched=$totalFetched uploaded=$uploaded skipped=$skipped " +
                "failed=$failed lastSyncedCallLogId=$lastSyncedCallLogId"
        )
    }

    fun logCallLogSyncSkipped(reason: String) {
        Log.i(TAG, "Call log sync skipped: $reason")
    }

    fun logCallLogSyncRequest(totalFetched: Int, uploadable: Int, skipped: Int, sample: String) {
        Log.d(
            TAG,
            "Call log sync request: totalFetched=$totalFetched uploadable=$uploadable " +
                "skipped=$skipped sample=$sample"
        )
    }

    fun logCallLogCheckExistingRequest(
        totalRecords: Int,
        chunkIndex: Int,
        chunkSize: Int,
        wrapperKey: String,
        deviceIdPresent: Boolean,
        firstItemJson: String
    ) {
        Log.d(
            TAG,
            "Call log check-existing request: totalRecords=$totalRecords chunkIndex=$chunkIndex " +
                "chunkSize=$chunkSize wrapperKey=$wrapperKey deviceIdPresent=$deviceIdPresent " +
                "firstItem=$firstItemJson"
        )
    }

    fun logCallLogCheckExistingResponse(httpCode: Int, rawBody: String?) {
        Log.e(
            TAG,
            "Call log check-existing response: status=$httpCode " +
                "serverErrorBody=${rawBody?.takeIf { it.isNotBlank() } ?: "<empty>"}"
        )
    }

    fun logCallLogSyncCursor(
        localStartedAt: Long,
        localAndroidId: Long,
        remoteStartedAt: Long,
        remoteAndroidId: Long,
        effectiveStartedAt: Long,
        effectiveAndroidId: Long
    ) {
        Log.d(
            TAG,
            "Call log cursor: localStartedAt=$localStartedAt localAndroidId=$localAndroidId " +
                "remoteStartedAt=$remoteStartedAt remoteAndroidId=$remoteAndroidId " +
                "effectiveStartedAt=$effectiveStartedAt effectiveAndroidId=$effectiveAndroidId"
        )
    }

    fun logCallLogSyncResponse(httpCode: Int, rawBody: String?) {
        Log.d(TAG, "Call log sync response: status=$httpCode serverErrorBody=${rawBody?.takeIf { it.isNotBlank() } ?: "<empty>"}")
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
