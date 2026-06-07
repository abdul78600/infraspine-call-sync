package com.infraspine.callsync.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * CRM upload contract. Backend integration is pending — this interface defines the
 * agreed shape so the rest of the app can be built and tested against it now
 * (see [com.infraspine.callsync.data.remote.DummyCrmUploader] for the stand-in
 * used while [dummyTestMode][com.infraspine.callsync.data.prefs.SecureSettingsStore.dummyTestMode]
 * is enabled).
 *
 * Endpoint: POST /api/crm/call-recordings/upload
 * Content-Type: multipart/form-data
 * Auth: Bearer token (agent token), attached via an OkHttp interceptor — never logged.
 */
interface CrmApiService {

    @Multipart
    @POST("api/crm/call-recordings/upload")
    suspend fun uploadCallRecording(
        @Part file: MultipartBody.Part,
        @Part("phoneNumber") phoneNumber: RequestBody?,
        @Part("callStartedAt") callStartedAt: RequestBody?,
        @Part("durationSeconds") durationSeconds: RequestBody?,
        @Part("callType") callType: RequestBody?,
        @Part("deviceId") deviceId: RequestBody,
        @Part("originalFileName") originalFileName: RequestBody
    ): Response<UploadResponse>
}

/**
 * Expected JSON shape of a successful upload response. Field names are a best-guess
 * placeholder contract; align with the actual CRM API once it is available.
 */
data class UploadResponse(
    val success: Boolean? = null,
    val recordingId: String? = null,
    val message: String? = null
)
