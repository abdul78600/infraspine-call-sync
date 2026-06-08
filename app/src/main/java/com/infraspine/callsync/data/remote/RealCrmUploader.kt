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
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/**
 * Real CRM uploader: streams the recording referenced by its SAF URI into a
 * temporary cache file (multipart bodies need a known length / file-backed source),
 * then performs the multipart POST described in [CrmApiService].
 *
 * This class is wired up and ready — it activates automatically once
 * [com.infraspine.callsync.data.prefs.SecureSettingsStore.dummyTestMode] is turned off
 * and a CRM URL + agent token are configured in Settings.
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
                val mediaType = (recording.mimeType ?: "audio/*").toMediaTypeOrNull()
                val filePart = MultipartBody.Part.createFormData(
                    "file",
                    recording.fileName,
                    tempFile.asRequestBody(mediaType)
                )

                NetworkDiagnostics.logUploadRequest(
                    fileName = recording.fileName,
                    fileSize = recording.fileSize,
                    mimeType = recording.mimeType,
                    fileExtension = recording.fileName.substringAfterLast('.', missingDelimiterValue = ""),
                    phoneNumber = recording.phoneNumber,
                    callStartedAt = recording.callStartedAt,
                    durationSeconds = recording.durationSeconds,
                    callType = recording.callType.name,
                    deviceId = deviceId,
                    uploadUrl = resolvedUploadUrl()
                )

                val response = api.uploadCallRecording(
                    file = filePart,
                    phoneNumber = recording.phoneNumber?.toPlainTextBody(),
                    callStartedAt = recording.callStartedAt?.toString()?.toPlainTextBody(),
                    durationSeconds = recording.durationSeconds?.toString()?.toPlainTextBody(),
                    callType = recording.callType.name.toPlainTextBody(),
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
                    UploadOutcome.Failure(NetworkDiagnostics.classify(httpCode = response.code(), serverMessage = serverMessage))
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

    private fun String.toPlainTextBody(): RequestBody = toRequestBody("text/plain".toMediaTypeOrNull())

    private companion object {
        /** Must match the path in [CrmApiService.uploadCallRecording]'s `@POST` annotation. */
        const val UPLOAD_PATH = "api/crm/call-recordings/upload"
    }
}
