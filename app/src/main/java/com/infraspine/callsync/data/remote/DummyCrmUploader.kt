package com.infraspine.callsync.data.remote

import com.infraspine.callsync.data.local.entity.RecordingEntity
import kotlinx.coroutines.delay

/**
 * Stand-in uploader used while the CRM backend is not yet available
 * (enabled via the "Use dummy/test upload mode" setting). Simulates network
 * latency and marks every recording as synced with a locally-generated fake
 * server ID, so the full sync pipeline — dedup, status transitions, dashboard
 * counts — can be exercised end to end without a real server.
 */
class DummyCrmUploader : RecordingUploader {

    override suspend fun upload(recording: RecordingEntity, deviceId: String): UploadOutcome {
        delay(SIMULATED_LATENCY_MS)
        return UploadOutcome.Success(serverRecordingId = "dummy-${recording.id}-${System.currentTimeMillis()}")
    }

    companion object {
        private const val SIMULATED_LATENCY_MS = 600L
    }
}
