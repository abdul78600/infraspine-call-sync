package com.infraspine.callsync.data.remote

import android.content.Context
import android.net.Uri
import com.infraspine.callsync.data.local.entity.RecordingEntity
import com.infraspine.callsync.data.prefs.SecureSettingsStore
import com.infraspine.callsync.domain.util.NetworkDiagnostics
import com.infraspine.callsync.domain.util.UploadErrorParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Real CRM uploader: streams the recording referenced by its SAF URI into a
 * temporary cache file (multipart bodies need a known length / file-backed source),
 * then performs the multipart POST described in [CrmApiService].
 *
 * This class is wired up and ready — it activates automatically once
 * [com.infraspine.callsync.data.prefs.SecureSettingsStore.dummyTestMode] is turned off
 * and a CRM URL + authenticated session are configured.
 */
class RealCrmUploader(
    private val context: Context,
    private val settingsStore: SecureSettingsStore,
    private val apiServiceProvider: () -> CrmApiService?
) : RecordingUploader {

    override suspend fun upload(recording: RecordingEntity, deviceId: String): UploadOutcome =
        withContext(Dispatchers.IO) {
            val api = apiServiceProvider()
                ?: return@withContext UploadOutcome.Failure("CRM server URL is not configured")

            val tempFile = try {
                copyToCache(recording)
            } catch (io: IOException) {
                return@withContext UploadOutcome.Failure("Could not read recording file: ${io.message}")
            } ?: return@withContext UploadOutcome.Failure("Recording file is no longer accessible")

            try {
                val resolvedMediaType = resolveMediaType(recording)
                val mediaType = resolvedMediaType.toMediaTypeOrNull()
                val fileBody = RequestBody.create(mediaType, tempFile)
                val filePart = MultipartBody.Part.createFormData(
                    "file",
                    recording.fileName,
                    fileBody
                )

                NetworkDiagnostics.logUploadRequest(
                    fileExists = tempFile.exists(),
                    actualFileSize = tempFile.length(),
                    filePath = tempFile.absolutePath,
                    fileName = recording.fileName,
                    fileSize = recording.fileSize,
                    mimeType = resolvedMediaType,
                    fileExtension = recording.fileName.substringAfterLast('.', missingDelimiterValue = ""),
                    multipartFieldNames = MULTIPART_FIELD_NAMES,
                    phoneNumber = recording.phoneNumber,
                    callStartedAt = recording.callStartedAt?.toIso8601(),
                    durationSeconds = recording.durationSeconds,
                    callType = recording.callType.apiValue(),
                    deviceId = deviceId,
                    uploadUrl = resolvedUploadUrl()
                )

                val response = api.uploadCallRecording(
                    file = filePart,
                    phoneNumber = recording.phoneNumber?.toPlainTextBody(),
                    callStartedAt = recording.callStartedAt?.toIso8601()?.toPlainTextBody(),
                    durationSeconds = recording.durationSeconds?.toString()?.toPlainTextBody(),
                    callType = recording.callType.apiValue().toPlainTextBody(),
                    deviceId = deviceId.toPlainTextBody(),
                    originalFileName = recording.fileName.toPlainTextBody()
                )

                if (response.isSuccessful) {
                    val body = response.body()
                    UploadOutcome.Success(serverRecordingId = body?.recordingId)
                } else {
                    val rawBody = runCatching { response.errorBody()?.string() }.getOrNull()
                    NetworkDiagnostics.logUploadResponse(response.code(), rawBody)
                    val serverMessage = UploadErrorParser.extractMessage(rawBody)
                    if (response.code() == 401) {
                        UploadOutcome.Unauthorized
                    } else {
                        UploadOutcome.Failure(
                            NetworkDiagnostics.classify(httpCode = response.code(), serverMessage = serverMessage)
                        )
                    }
                }
            } catch (io: IOException) {
                val message = NetworkDiagnostics.classify(throwable = io)
                NetworkDiagnostics.logConnectionFailure(message)
                UploadOutcome.Failure(message)
            } finally {
                tempFile.delete()
            }
        }

    /**
     * Mirrors the base-URL normalization in [CrmApiFactory.getService] (Retrofit
     * requires a trailing slash on the base URL to resolve relative `@POST` paths
     * correctly) so the logged URL matches exactly what Retrofit sends the request to.
     */
    private fun resolvedUploadUrl(): String {
        val configured = settingsStore.crmServerUrl?.takeIf { it.isNotBlank() } ?: return UPLOAD_PATH
        val normalized = if (configured.endsWith("/")) configured else "$configured/"
        return normalized + UPLOAD_PATH
    }

    private fun copyToCache(recording: RecordingEntity): File? {
        val uri = Uri.parse(recording.fileUri)
        val resolver = context.contentResolver
        val tempFile = File(context.cacheDir, "upload_${recording.id}_${System.currentTimeMillis()}")

        resolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null

        return tempFile
    }

    /**
     * Resolves a concrete `Content-Type` for the file part. Prefers the stored
     * [RecordingEntity.mimeType], then maps the file extension to a concrete audio
     * type (so `.aac` -> `audio/aac`), and finally falls back to
     * `application/octet-stream` — never a wildcard audio type, which some
     * multipart parsers reject.
     */
    private fun resolveMediaType(recording: RecordingEntity): String {
        val ext = recording.fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (ext == "aac") return "audio/aac"

        recording.mimeType?.takeIf { it.isNotBlank() && it != "audio/*" }?.let { return it }
        return EXTENSION_MIME_TYPES[ext] ?: "application/octet-stream"
    }

    private fun String.toPlainTextBody(): RequestBody = toRequestBody("text/plain".toMediaTypeOrNull())

    /**
     * Formats an epoch-millis timestamp as an ISO-8601 instant in UTC with fixed
     * millisecond precision (e.g. "2026-06-08T12:30:00.000Z"), which is the shape
     * the CRM upload endpoint expects for `callStartedAt` — not raw epoch millis.
     * Pinned to 3-digit millis so the value is stable regardless of the timestamp.
     */
    private fun Long.toIso8601(): String =
        ISO_MILLIS_UTC.format(Instant.ofEpochMilli(this))

    private companion object {
        /** Must match the path in [CrmApiService.uploadCallRecording]'s `@POST` annotation. */
        const val UPLOAD_PATH = "api/crm/call-recordings/upload"

        val MULTIPART_FIELD_NAMES = listOf(
            "file",
            "phoneNumber",
            "callStartedAt",
            "durationSeconds",
            "callType",
            "deviceId",
            "originalFileName"
        )

        /** ISO-8601 in UTC with always-3-digit millis, e.g. "2026-06-08T12:30:00.000Z". */
        val ISO_MILLIS_UTC: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** Concrete audio MIME types by file extension, used when the stored mimeType is missing/wildcard. */
        val EXTENSION_MIME_TYPES = mapOf(
            "aac" to "audio/aac",
            "m4a" to "audio/mp4",
            "mp3" to "audio/mpeg",
            "amr" to "audio/amr",
            "3gp" to "audio/3gpp",
            "wav" to "audio/wav",
            "ogg" to "audio/ogg",
            "opus" to "audio/opus"
        )
    }
}
