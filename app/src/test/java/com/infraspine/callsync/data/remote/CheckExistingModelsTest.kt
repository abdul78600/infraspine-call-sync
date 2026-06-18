package com.infraspine.callsync.data.remote

import com.google.gson.Gson
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckExistingModelsTest {

    @Test
    fun callLogCheckExistingRequestMatchesBackendContract() {
        val request = CallLogExistingCheckRequest(
            deviceId = "device-123",
            records = listOf(
                CallLogCheckItem(
                    clientRef = "10482",
                    phoneNumber = "+15551234567",
                    callType = "outgoing",
                    callStartedAt = "2026-06-08T12:30:00.000Z",
                    durationSeconds = 42
                )
            )
        )

        val json = Gson().toJsonTree(request).asJsonObject
        val firstRecord = json.getAsJsonArray("records").first().asJsonObject

        assertEquals("device-123", json.get("deviceId").asString)
        assertTrue(json.has("records"))
        assertFalse(json.has("logs"))
        assertFalse(json.has("items"))
        assertEquals("10482", firstRecord.get("clientRef").asString)
        assertFalse(firstRecord.has("externalCallId"))
        assertFalse(firstRecord.has("deviceId"))
    }

    @Test
    fun callLogCheckExistingResponseParsesObjectShapedMissingEntries() {
        val json = """
            {
              "existing": ["10480"],
              "missing": [
                { "clientRef": "10482" },
                { "externalCallId": "10483" }
              ]
            }
        """.trimIndent()

        val response = Gson().fromJson(json, CallLogExistingCheckResponse::class.java)

        assertNotNull(response.existing)
        assertNotNull(response.missing)
        assertTrue(response.missing!!.isJsonArray)
        assertTrue(response.missing!!.asJsonArray[0].isJsonObject)
    }
}
