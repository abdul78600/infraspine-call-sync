package com.infraspine.callsync.scan

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.infraspine.callsync.data.prefs.SecureSettingsStore

/**
 * Wraps Storage Access Framework folder selection. Persists the granted URI permission
 * so the user only has to pick the folder once (survives reboots per SAF contract),
 * and exposes the currently selected folder as a [DocumentFile].
 */
class RecordingFolderManager(
    private val context: Context,
    private val settingsStore: SecureSettingsStore
) {

    fun currentFolderUri(): Uri? =
        settingsStore.selectedFolderUri?.let { Uri.parse(it) }

    fun currentFolder(): DocumentFile? {
        val uri = currentFolderUri() ?: return null
        return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.isDirectory && it.canRead() }
    }

    fun currentFolderDisplayPath(): String? {
        val folder = currentFolder() ?: return null
        return folder.uri.toFriendlyPath() ?: folder.name
    }

    /**
     * Persists read + write-less access across reboots, releasing any previously
     * granted folder permission first to avoid leaking stale grants.
     */
    fun persistFolderSelection(treeUri: Uri) {
        releaseCurrentPermission()

        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        settingsStore.selectedFolderUri = treeUri.toString()
    }

    fun hasValidFolderSelection(): Boolean = currentFolder() != null

    private fun releaseCurrentPermission() {
        val previous = currentFolderUri() ?: return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                previous,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun Uri.toFriendlyPath(): String? {
        val docId = lastPathSegment ?: return null
        // Typical docId looks like "primary:Recordings/Call" — show the part after ':'.
        val colonIndex = docId.indexOf(':')
        return if (colonIndex >= 0) docId.substring(colonIndex + 1).ifBlank { "/" } else docId
    }
}
