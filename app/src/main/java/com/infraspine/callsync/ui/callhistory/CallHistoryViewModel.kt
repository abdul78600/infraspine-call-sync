package com.infraspine.callsync.ui.callhistory

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.infraspine.callsync.AppContainer
import com.infraspine.callsync.data.repository.CallHistoryRepository
import com.infraspine.callsync.domain.model.CallHistoryEntry
import com.infraspine.callsync.ui.common.Event
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed class CallHistoryMessage {
    object PermissionDenied : CallHistoryMessage()
    data class LoadError(val message: String) : CallHistoryMessage()
}

class CallHistoryViewModel(
    private val callHistoryRepository: CallHistoryRepository
) : ViewModel() {

    private val _calls = MutableLiveData<List<CallHistoryEntry>>(emptyList())
    val calls: LiveData<List<CallHistoryEntry>> = _calls

    /** True only for the very first page load (drives the full-screen spinner). */
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    /** True while fetching a subsequent page (drives the small list-footer spinner). */
    private val _isLoadingMore = MutableLiveData(false)
    val isLoadingMore: LiveData<Boolean> = _isLoadingMore

    private val _message = MutableLiveData<Event<CallHistoryMessage>>()
    val message: LiveData<Event<CallHistoryMessage>> = _message

    private var loaded = false
    private var endReached = false
    private var loadJob: Job? = null

    /** Loads the first page once automatically; call [refresh] for pull-to-refresh. */
    fun loadIfNeeded() {
        if (!loaded) refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        endReached = false
        loadPage(offset = 0, isFirstPage = true)
    }

    fun onPermissionDenied() {
        _message.value = Event(CallHistoryMessage.PermissionDenied)
    }

    /** Call when the list is scrolled near the bottom to fetch the next page. */
    fun loadNextPage() {
        if (!loaded || endReached) return
        if (_isLoading.value == true || _isLoadingMore.value == true) return

        loadPage(offset = _calls.value?.size ?: 0, isFirstPage = false)
    }

    private fun loadPage(offset: Int, isFirstPage: Boolean) {
        loadJob = viewModelScope.launch {
            if (isFirstPage) _isLoading.value = true else _isLoadingMore.value = true

            runCatching { callHistoryRepository.loadCallHistoryPage(offset, CallHistoryRepository.PAGE_SIZE) }
                .onSuccess { page ->
                    loaded = true
                    if (page.size < CallHistoryRepository.PAGE_SIZE) endReached = true

                    _calls.value = if (isFirstPage) page else (_calls.value.orEmpty() + page)
                }
                .onFailure { error ->
                    _message.value = Event(
                        CallHistoryMessage.LoadError(error.message ?: "Unknown error")
                    )
                }

            if (isFirstPage) _isLoading.value = false else _isLoadingMore.value = false
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CallHistoryViewModel(container.callHistoryRepository) as T
        }
    }
}
