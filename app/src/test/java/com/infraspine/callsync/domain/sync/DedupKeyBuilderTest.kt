package com.infraspine.callsync.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DedupKeyBuilderTest {

    @Test
    fun normalizePhoneNumberStripsFormatting() {
        assertEquals("+923001112233", DedupKeyBuilder.normalizePhoneNumber("+92 300-1112233"))
        assertEquals("03001234567", DedupKeyBuilder.normalizePhoneNumber("(0300) 123-4567"))
    }

    @Test
    fun normalizePhoneNumberRejectsKnownPlaceholders() {
        assertNull(DedupKeyBuilder.normalizePhoneNumber(null))
        assertNull(DedupKeyBuilder.normalizePhoneNumber(""))
        assertNull(DedupKeyBuilder.normalizePhoneNumber("unknown"))
        assertNull(DedupKeyBuilder.normalizePhoneNumber("private"))
        assertNull(DedupKeyBuilder.normalizePhoneNumber("anonymous"))
        assertNull(DedupKeyBuilder.normalizePhoneNumber("restricted"))
    }

    @Test
    fun normalizePhoneNumberRejectsNoDigitsAndShortCodes() {
        assertNull(DedupKeyBuilder.normalizePhoneNumber("abc-def"))
        assertNull(DedupKeyBuilder.normalizePhoneNumber("112"))
        assertNull(DedupKeyBuilder.normalizePhoneNumber("-1"))
        assertNull(DedupKeyBuilder.normalizePhoneNumber("021"))
    }
}
