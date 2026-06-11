package com.infraspine.callsync.domain.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Computes a SHA-256 hex digest of a SAF-referenced file's content. */
object FileHasher {

    /** Returns the SHA-256 hex digest of the file at [uriString], or null if it can't be read. */
    suspend fun sha256(context: Context, uriString: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val resolver = context.contentResolver
            resolver.openInputStream(Uri.parse(uriString))?.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            } ?: return@runCatching null
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
