package com.infraspine.callsync.domain.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Extracts a human-readable validation message from a CRM error response body,
 * so the UI can show the server's actual reason ("file is required", "phoneNumber
 * invalid", "file type not allowed") instead of a bare "Server responded with 400".
 *
 * The backend's exact error shape isn't fixed in the contract (see [com.infraspine
 * .callsync.data.remote.CrmApiService]), so this tries the JSON shapes validation
 * middlewares commonly use, then falls back to the raw body text.
 */
object UploadErrorParser {

    /**
     * Returns the most specific message it can find in [rawBody], or null if the
     * body is empty/unparseable (callers should fall back to a generic HTTP-code message).
     */
    fun extractMessage(rawBody: String?): String? {
        val body = rawBody?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json != null) {
            return fromJsonObject(json) ?: body.takeIf { looksLikePlainText(it) }
        }

        val array = runCatching { JSONArray(body) }.getOrNull()
        if (array != null) {
            return fromJsonArray(array) ?: body.takeIf { looksLikePlainText(it) }
        }

        return body.takeIf { looksLikePlainText(it) }
    }

    private fun fromJsonObject(json: JSONObject): String? {
        // Single-message shapes: {"error": "..."} / {"message": "..."} / {"detail": "..."}
        for (key in SINGLE_MESSAGE_KEYS) {
            val value = json.optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }

        // Field-level validation shapes: {"errors": [...]} or {"errors": {"field": "msg", ...}}
        json.opt("errors")?.let { errors ->
            when (errors) {
                is JSONArray -> fromJsonArray(errors)?.let { return it }
                is JSONObject -> fromFieldErrorMap(errors)?.let { return it }
                is String -> errors.trim().takeIf { it.isNotEmpty() }?.let { return it }
                else -> Unit
            }
        }

        return null
    }

    private fun fromJsonArray(array: JSONArray): String? {
        val messages = (0 until array.length()).mapNotNull { index ->
            when (val element = array.opt(index)) {
                is String -> element.trim().takeIf { it.isNotEmpty() }
                is JSONObject -> fromFieldError(element)
                else -> null
            }
        }
        return messages.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun fromFieldErrorMap(map: JSONObject): String? {
        // JSONObject.keys() returns a raw java.util.Iterator on Android (the org.json
        // fork predates generics), so each element surfaces as a platform type — cast
        // explicitly to String before using it as a JSONObject key.
        val messages = map.keys().asSequence().mapNotNull { it as? String }.mapNotNull { field ->
            val value = map.opt(field)
            val message = when (value) {
                is String -> value
                is JSONArray -> (0 until value.length()).joinToString(", ") { value.optString(it) }
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }

            message?.let { "$field: $it" }
        }.toList()

        return messages.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /** Handles {"field": "phoneNumber", "message": "invalid"} / {"path": "...", "msg": "..."} entries. */
    private fun fromFieldError(entry: JSONObject): String? {
        val message = FIELD_ERROR_MESSAGE_KEYS.firstNotNullOfOrNull { key ->
            entry.optString(key, "").trim().takeIf { it.isNotEmpty() }
        } ?: return null

        val field = FIELD_ERROR_FIELD_KEYS.firstNotNullOfOrNull { key ->
            entry.optString(key, "").trim().takeIf { it.isNotEmpty() }
        }

        return if (field != null) "$field: $message" else message
    }

    /** Guards against echoing back HTML error pages or huge bodies as if they were messages. */
    private fun looksLikePlainText(text: String): Boolean =
        text.length <= MAX_PLAIN_TEXT_LENGTH && !text.startsWith("<")

    private const val MAX_PLAIN_TEXT_LENGTH = 200

    private val SINGLE_MESSAGE_KEYS = listOf("error", "message", "detail", "reason")
    private val FIELD_ERROR_MESSAGE_KEYS = listOf("message", "msg", "error", "reason")
    private val FIELD_ERROR_FIELD_KEYS = listOf("field", "path", "param", "name")
}
