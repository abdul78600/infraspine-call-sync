package com.infraspine.callsync.domain.sync

/**
 * Shared phone-number normalization used both when building the upload payload
 * and when building check-existing requests, so the same call/recording always
 * produces the same comparable phone number.
 */
object DedupKeyBuilder {

    /**
     * Returns a trimmed phone number, or null if it is blank, a known
     * placeholder (e.g. "unknown", "private"), or contains no digits.
     */
    fun normalizePhoneNumber(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.lowercase() in NON_UPLOADABLE_PHONE_NUMBERS) return null
        return value.takeIf { candidate -> candidate.any { it.isDigit() } }
    }

    private val NON_UPLOADABLE_PHONE_NUMBERS = setOf(
        "-1",
        "-2",
        "anonymous",
        "private",
        "private number",
        "restricted",
        "unavailable",
        "unknown"
    )
}
