package com.infraspine.callsync.ui.player

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.infraspine.callsync.AppContainer
import com.infraspine.callsync.data.local.entity.RecordingEntity
import com.infraspine.callsync.data.repository.RecordingRepository
import com.infraspine.callsync.ui.common.Event
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class PlaybackError {
    object FailedToLoad : PlaybackError()
    object PlaybackFailed : PlaybackError()
}

/**
 * Plays a single recording's audio via [MediaPlayer], reading directly from its
 * SAF content URI (read-only — never copies or modifies the original file).
 */
class PlayerViewModel(
    context: Context,
    recordingId: Long,
    private val recordingRepository: RecordingRepository
) : ViewModel() {

    private val appContext = context.applicationContext

    val recording: LiveData<RecordingEntity?> =
        recordingRepository.observeById(recordingId).asLiveData()

    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _isPrepared = MutableLiveData(false)
    val isPrepared: LiveData<Boolean> = _isPrepared

    private val _positionMs = MutableLiveData(0)
    val positionMs: LiveData<Int> = _positionMs

    private val _durationMs = MutableLiveData(0)
    val durationMs: LiveData<Int> = _durationMs

    private val _error = MutableLiveData<Event<PlaybackError>>()
    val error: LiveData<Event<PlaybackError>> = _error

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var preparedUri: String? = null

    fun togglePlayback(fileUri: String) {
        val player = mediaPlayer
        if (player != null && preparedUri == fileUri) {
            if (player.isPlaying) {
                pause()
            } else {
                resume()
            }
            return
        }

        prepareAndPlay(fileUri)
    }

    private fun prepareAndPlay(fileUri: String) {
        releasePlayer()

        val player = MediaPlayer()
        runCatching {
            player.setDataSource(appContext, Uri.parse(fileUri))
            player.setOnPreparedListener {
                preparedUri = fileUri
                _isPrepared.value = true
                _durationMs.value = it.duration
                it.start()
                _isPlaying.value = true
                startProgressUpdates()
            }
            player.setOnCompletionListener {
                _isPlaying.value = false
                _positionMs.value = it.duration
                stopProgressUpdates()
            }
            player.setOnErrorListener { _, _, _ ->
                _error.value = Event(PlaybackError.PlaybackFailed)
                _isPlaying.value = false
                stopProgressUpdates()
                true
            }
            player.prepareAsync()
        }.onFailure {
            _error.value = Event(PlaybackError.FailedToLoad)
            player.release()
            return
        }

        mediaPlayer = player
    }

    private fun pause() {
        runCatching { mediaPlayer?.pause() }
        _isPlaying.value = false
        stopProgressUpdates()
    }

    private fun resume() {
        runCatching { mediaPlayer?.start() }
        _isPlaying.value = true
        startProgressUpdates()
    }

    fun seekTo(positionMs: Int) {
        runCatching { mediaPlayer?.seekTo(positionMs) }
        _positionMs.value = positionMs
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = viewModelScope.launch {
            while (isActive) {
                val player = mediaPlayer
                if (player != null && player.isPlaying) {
                    _positionMs.value = player.currentPosition
                }
                delay(PROGRESS_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun releasePlayer() {
        stopProgressUpdates()
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            player.release()
        }
        mediaPlayer = null
        preparedUri = null
        _isPrepared.value = false
        _isPlaying.value = false
        _positionMs.value = 0
        _durationMs.value = 0
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
    }

    class Factory(
        private val context: Context,
        private val recordingId: Long,
        private val container: AppContainer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlayerViewModel(context.applicationContext, recordingId, container.recordingRepository) as T
        }
    }

    private companion object {
        const val PROGRESS_UPDATE_INTERVAL_MS = 250L
    }
}
