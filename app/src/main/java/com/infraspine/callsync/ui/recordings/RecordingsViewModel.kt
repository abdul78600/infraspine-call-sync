package com.infraspine.callsync.ui.recordings

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import com.infraspine.callsync.AppContainer
import com.infraspine.callsync.data.local.entity.RecordingEntity
import com.infraspine.callsync.data.repository.RecordingRepository

private data class RecordingsQuery(val filter: RecordingFilter, val searchQuery: String)

class RecordingsViewModel(
    private val recordingRepository: RecordingRepository
) : ViewModel() {

    private val _filter = MutableLiveData(RecordingFilter.ALL)
    val filter: LiveData<RecordingFilter> = _filter

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    /**
     * Merges the status filter and search text into one key so a single
     * [RecordingRepository.observeFiltered] flow drives the list — avoids running
     * separate "search" and "filter" queries that would race and overwrite each other.
     */
    private val query = MediatorLiveData<RecordingsQuery>().apply {
        fun emit() {
            val filter = _filter.value ?: RecordingFilter.ALL
            val search = _searchQuery.value ?: ""
            value = RecordingsQuery(filter, search)
        }
        addSource(_filter) { emit() }
        addSource(_searchQuery) { emit() }
    }

    val recordings: LiveData<List<RecordingEntity>> = query.switchMap { (filter, search) ->
        recordingRepository.observeFiltered(filter.status, search).asLiveData()
    }

    fun setFilter(filter: RecordingFilter) {
        if (_filter.value != filter) {
            _filter.value = filter
        }
    }

    fun setSearchQuery(query: String) {
        if (_searchQuery.value != query) {
            _searchQuery.value = query
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RecordingsViewModel(container.recordingRepository) as T
        }
    }
}
