package com.infraspine.callsync.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SyncProfileTest {

    @Test
    fun normalizeUrlIgnoresCaseAndTrailingSlash() {
        assertEquals(
            "https://crm.example.com/",
            SyncProfile.normalizeUrl("HTTPS://CRM.EXAMPLE.COM")
        )
        assertEquals(
            "https://crm.example.com/",
            SyncProfile.normalizeUrl("https://crm.example.com/")
        )
    }

    @Test
    fun keyIsIsolatedPerServerIdentity() {
        val first = SyncProfile.keyFor("url:https://a.example.com/", "user-1", "device-1")
        val second = SyncProfile.keyFor("url:https://b.example.com/", "user-1", "device-1")

        assertNotEquals(first, second)
    }

    @Test
    fun instanceIdTakesPrecedenceOverUrlIdentity() {
        val viaFirstUrl = SyncProfile.serverIdentity("https://a.example.com", "crm-prod")
        val viaSecondUrl = SyncProfile.serverIdentity("https://b.example.com", "crm-prod")

        assertEquals(viaFirstUrl, viaSecondUrl)
    }
}
