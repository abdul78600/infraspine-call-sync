package com.infraspine.callsync.domain.model

/**
 * Raw metadata read from a single audio document found in the selected SAF folder,
 * before it is matched against the call log and persisted.
 */
data class ScannedAudioFile(
    val uri: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?
)
