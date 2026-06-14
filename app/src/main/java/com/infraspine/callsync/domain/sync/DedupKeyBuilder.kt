package com.infraspine.callsync.domain.sync

/**
 * Shared phone-number normalization used both when building the upload payload
 * and when building check-existing requests, so the same call/recording always
 * produces the same comparable phone number.
 */
object DedupKeyBuilder {

    private const val MIN_PHONE_DIGITS = 7

    /**
     * Returns a normalized phone number, or null if it is blank, a known
     * placeholder (e.g. "unknown", "private"), too short, or contains no digits.
     */
    fun normalizePhoneNumber(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.lowercase() in NON_UPLOADABLE_PHONE_NUMBERS) return null
        val digits = value.filter(Char::isDigit)
        if (digits.length < MIN_PHONE_DIGITS) return null

        return when {
            value.startsWith("+") -> "+$digits"
            value.startsWith("00") -> "+$digits"
            else -> digits
        }
    }

    private val NON_UPLOADABLE_PHONE_NUMBERS = setOf(
        "-1",
        "-2",
        "-3",
        "anonymous",
        "blocked",
        "caller withheld",
        "hidden",
        "private",
        "private number",
        "restricted",
        "unavailable",
        "unknown"
    )
}
