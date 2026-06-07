package com.infraspine.callsync.data.remote

import android.content.Context
import android.net.Uri
import com.infraspine.callsync.data.local.entity.RecordingEntity
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
                    UploadOutcome.Failure("Server responded with ${response.code()}")
                }
            } catch (io: IOException) {
                UploadOutcome.Failure("Network error: ${io.message ?: "request failed"}")
            } finally {
                tempFile.delete()
            }
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
}
