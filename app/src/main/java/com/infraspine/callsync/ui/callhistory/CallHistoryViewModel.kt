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

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _message = MutableLiveData<Event<CallHistoryMessage>>()
    val message: LiveData<Event<CallHistoryMessage>> = _message

    private var loaded = false

    /** Loads once automatically; pass [force] = true for pull-to-refresh. */
    fun loadIfNeeded() {
        if (!loaded) load()
    }

    fun refresh() = load()

    fun onPermissionDenied() {
        _message.value = Event(CallHistoryMessage.PermissionDenied)
    }

    private fun load() {
        if (_isLoading.value == true) return

        viewModelScope.launch {
            _isLoading.value = true
            runCatching { callHistoryRepository.loadCallHistory() }
                .onSuccess {
                    loaded = true
                    _calls.value = it
                }
                .onFailure { error ->
                    _message.value = Event(
                        CallHistoryMessage.LoadError(error.message ?: "Unknown error")
                    )
                }
            _isLoading.value = false
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CallHistoryViewModel(container.callHistoryRepository) as T
        }
    }
}
