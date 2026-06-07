package com.infraspine.callsync.data.remote

import com.infraspine.callsync.data.local.entity.RecordingEntity

/**
 * Outcome of a single upload attempt against the CRM (or the dummy stand-in).
 */
sealed class UploadOutcome {
    data class Success(val serverRecordingId: String?) : UploadOutcome()
    data class Failure(val message: String) : UploadOutcome()
}

/**
 * Abstraction over "send this recording to the CRM". Two implementations exist:
 * - [RealCrmUploader] performs the actual multipart POST once the CRM API is ready.
 * - [DummyCrmUploader] simulates success locally so the rest of the sync pipeline
 *   (dedup, retry, status tracking, UI) can be exercised before backend integration.
 *
 * The active implementation is chosen at call time based on
 * [com.infraspine.callsync.data.prefs.SecureSettingsStore.dummyTestMode].
 */
interface RecordingUploader {
    suspend fun upload(recording: RecordingEntity, deviceId: String): UploadOutcome
}
