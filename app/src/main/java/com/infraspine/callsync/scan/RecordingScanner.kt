package com.infraspine.callsync.scan

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.infraspine.callsync.domain.model.ScannedAudioFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans a user-selected SAF folder for supported audio recordings without ever
 * writing to, deleting, or otherwise modifying the original files (read-only DocumentFile use).
 */
class RecordingScanner(private val context: Context) {

    suspend fun scan(folder: DocumentFile): List<ScannedAudioFile> = withContext(Dispatchers.IO) {
        collectAudioFiles(folder, depth = 0)
            .map {
                ScannedAudioFile(
                    uri = it.uri.toString(),
                    name = it.name ?: it.uri.lastPathSegment.orEmpty(),
                    size = it.length(),
                    lastModified = it.lastModified(),
                    mimeType = it.type ?: guessMimeFromName(it.name)
                )
            }
            .toList()
    }

    /**
     * Recordings are often nested a level or two below the folder the user picks
     * (e.g. "Recordings/Call"), so walk subfolders up to [MAX_SCAN_DEPTH] deep.
     */
    private fun collectAudioFiles(folder: DocumentFile, depth: Int): List<DocumentFile> {
        val children = folder.listFiles().orEmpty()
        val files = children.filter { it.isFile && it.canRead() && isSupportedAudio(it) }
        if (depth >= MAX_SCAN_DEPTH) return files

        val nested = children
            .filter { it.isDirectory && it.canRead() }
            .flatMap { collectAudioFiles(it, depth + 1) }
        return files + nested
    }

    private fun isSupportedAudio(file: DocumentFile): Boolean {
        val name = file.name?.lowercase().orEmpty()
        val hasSupportedExtension = SUPPORTED_EXTENSIONS.any { name.endsWith(".$it") }
        val mime = file.type
        val hasAudioMime = mime?.startsWith("audio/") == true
        return hasSupportedExtension || hasAudioMime
    }

    private fun guessMimeFromName(name: String?): String? {
        val ext = name?.substringAfterLast('.', "")?.lowercase()
        return EXTENSION_TO_MIME[ext]
    }

    companion object {
        private const val MAX_SCAN_DEPTH = 2

        val SUPPORTED_EXTENSIONS = setOf("mp3", "m4a", "amr", "wav", "aac", "ogg")

        private val EXTENSION_TO_MIME = mapOf(
            "mp3" to "audio/mpeg",
            "m4a" to "audio/mp4",
            "amr" to "audio/amr",
            "wav" to "audio/wav",
            "aac" to "audio/aac",
            "ogg" to "audio/ogg"
        )
    }
}
