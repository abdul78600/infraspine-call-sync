package com.infraspine.callsync.ui.recordings

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import com.infraspine.callsync.AppContainer
import com.infraspine.callsync.data.local.entity.RecordingEntity
import com.infraspine.callsync.data.repository.RecordingRepository

class RecordingsViewModel(
    private val recordingRepository: RecordingRepository
) : ViewModel() {

    private val _filter = MutableLiveData(RecordingFilter.ALL)
    val filter: LiveData<RecordingFilter> = _filter

    val recordings: LiveData<List<RecordingEntity>> = _filter.switchMap { filter ->
        val flow = if (filter.status == null) {
            recordingRepository.observeAll()
        } else {
            recordingRepository.observeByStatus(filter.status)
        }
        flow.asLiveData()
    }

    fun setFilter(filter: RecordingFilter) {
        if (_filter.value != filter) {
            _filter.value = filter
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RecordingsViewModel(container.recordingRepository) as T
        }
    }
}
