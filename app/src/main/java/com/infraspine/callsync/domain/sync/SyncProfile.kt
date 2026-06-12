package com.infraspine.callsync.domain.sync

import java.security.MessageDigest

/**
 * Identifies a unique (server, account, device) sync context. Cursors and
 * "reset sync history" actions are scoped to a profile key so that switching
 * CRM servers or logging in as a different user starts a fresh incremental
 * sync history without affecting other profiles.
 */
object SyncProfile {

    /**
     * Builds a stable identifier from the normalized server base URL, the
     * logged-in account identifier, and the device id. The same logical profile always
     * produces the same key.
     */
    fun keyFor(serverIdentity: String?, userId: String?, deviceId: String?): String {
        val raw = "${serverIdentity.orEmpty()}|${userId.orEmpty()}|${deviceId.orEmpty()}"
        return sha256Hex(raw)
    }

    fun serverIdentity(serverBaseUrl: String?, instanceId: String?): String {
        val normalizedInstanceId = instanceId?.trim()?.takeIf { it.isNotBlank() }
        return if (normalizedInstanceId != null) {
            "instance:${normalizedInstanceId.lowercase()}"
        } else {
            "url:${normalizeUrl(serverBaseUrl)}"
        }
    }

    /** Mirrors CrmApiFactory's base-URL normalization (trailing slash) plus lowercasing. */
    fun normalizeUrl(url: String?): String {
        val trimmed = url?.trim()?.takeIf { it.isNotBlank() } ?: return ""
        val withSlash = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        return withSlash.lowercase()
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
