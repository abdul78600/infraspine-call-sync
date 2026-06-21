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

private data class RecordingsQuery(
    val filter: RecordingFilter,
    val searchQuery: String,
    val sortOrder: RecordingSortOrder
)

class RecordingsViewModel(
    private val recordingRepository: RecordingRepository
) : ViewModel() {

    private val _filter = MutableLiveData(RecordingFilter.ALL)
    val filter: LiveData<RecordingFilter> = _filter

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    private val _sortOrder = MutableLiveData(RecordingSortOrder.DATE_DESC)
    val sortOrder: LiveData<RecordingSortOrder> = _sortOrder

    private val query = MediatorLiveData<RecordingsQuery>().apply {
        fun emit() {
            value = RecordingsQuery(
                filter = _filter.value ?: RecordingFilter.ALL,
                searchQuery = _searchQuery.value ?: "",
                sortOrder = _sortOrder.value ?: RecordingSortOrder.DATE_DESC
            )
        }
        addSource(_filter) { emit() }
        addSource(_searchQuery) { emit() }
        addSource(_sortOrder) { emit() }
    }

    val recordings: LiveData<List<RecordingEntity>> = query.switchMap { (filter, search, sort) ->
        recordingRepository.observeFiltered(filter.status, search, sort).asLiveData()
    }

    fun setFilter(filter: RecordingFilter) {
        if (_filter.value != filter) _filter.value = filter
    }

    fun setSearchQuery(query: String) {
        if (_searchQuery.value != query) _searchQuery.value = query
    }

    fun setSortOrder(order: RecordingSortOrder) {
        if (_sortOrder.value != order) _sortOrder.value = order
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RecordingsViewModel(container.recordingRepository) as T
    }
}
