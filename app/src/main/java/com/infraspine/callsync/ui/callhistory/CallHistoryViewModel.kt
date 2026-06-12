package com.infraspine.callsync.ui.callhistory

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.infraspine.callsync.AppContainer
import com.infraspine.callsync.data.repository.CallHistoryRepository
import com.infraspine.callsync.domain.model.CallHistoryEntry
import com.infraspine.callsync.scan.CallLogDateRange
import com.infraspine.callsync.ui.common.Event
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed class CallHistoryMessage {
    object PermissionDenied : CallHistoryMessage()
    data class LoadError(val message: String) : CallHistoryMessage()
}

private val CALL_HISTORY_LIMIT_OPTIONS = listOf(
    CallHistoryLimitOption("25", 25),
    CallHistoryLimitOption("50", 50),
    CallHistoryLimitOption("100", 100),
    CallHistoryLimitOption("300", 300),
    CallHistoryLimitOption("500", 500),
    CallHistoryLimitOption("1000", 1000),
    CallHistoryLimitOption("All", null)
)

private val DEFAULT_CALL_HISTORY_LIMIT = CALL_HISTORY_LIMIT_OPTIONS[1]

data class CallHistoryLimitOption(
    val label: String,
    val limit: Int?
)

data class CallHistorySelectedDateRange(
    val startAtInclusive: Long,
    val endAtInclusive: Long
)

data class CallHistoryDisplayState(
    val selectedLimit: CallHistoryLimitOption = DEFAULT_CALL_HISTORY_LIMIT,
    val loadedItems: Int = 0,
    val allLoaded: Boolean = false,
    val selectedDateRange: CallHistorySelectedDateRange? = null
)

class CallHistoryViewModel(
    private val callHistoryRepository: CallHistoryRepository
) : ViewModel() {

    private val _calls = MutableLiveData<List<CallHistoryEntry>>(emptyList())
    val calls: LiveData<List<CallHistoryEntry>> = _calls

    /** True only for the very first page load (drives the full-screen spinner). */
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _displayState = MutableLiveData(CallHistoryDisplayState())
    val displayState: LiveData<CallHistoryDisplayState> = _displayState

    private val _message = MutableLiveData<Event<CallHistoryMessage>>()
    val message: LiveData<Event<CallHistoryMessage>> = _message

    private var loaded = false
    private var loadJob: Job? = null

    val limitOptions: List<CallHistoryLimitOption> = CALL_HISTORY_LIMIT_OPTIONS

    fun loadIfNeeded() {
        if (!loaded) refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loaded = false
        loadCalls()
    }

    fun onPermissionDenied() {
        _message.value = Event(CallHistoryMessage.PermissionDenied)
    }

    fun setDisplayLimit(option: CallHistoryLimitOption) {
        val current = _displayState.value?.selectedLimit
        if (current == option) return
        _displayState.value = (_displayState.value ?: CallHistoryDisplayState()).copy(selectedLimit = option)
        refresh()
    }

    fun setDateRange(range: CallHistorySelectedDateRange) {
        val current = _displayState.value?.selectedDateRange
        if (current == range) return
        _displayState.value = (_displayState.value ?: CallHistoryDisplayState()).copy(selectedDateRange = range)
        refresh()
    }

    fun clearDateRange() {
        if (_displayState.value?.selectedDateRange == null) return
        _displayState.value = (_displayState.value ?: CallHistoryDisplayState()).copy(selectedDateRange = null)
        refresh()
    }

    fun selectedLimitLabel(): String = (_displayState.value?.selectedLimit ?: DEFAULT_CALL_HISTORY_LIMIT).label

    private fun loadCalls() {
        if (_isLoading.value == true) return

        val currentState = _displayState.value ?: CallHistoryDisplayState()
        val selectedLimit = currentState.selectedLimit
        val selectedDateRange = currentState.selectedDateRange
        loadJob = viewModelScope.launch {
            _isLoading.value = true

            runCatching {
                callHistoryRepository.loadRecentCallHistory(
                    limit = selectedLimit.limit,
                    dateRange = selectedDateRange?.let {
                        CallLogDateRange(
                            startAtInclusive = it.startAtInclusive,
                            endAtInclusive = it.endAtInclusive
                        )
                    }
                )
            }
                .onSuccess { calls ->
                    loaded = true
                    _calls.value = calls
                    _displayState.value = CallHistoryDisplayState(
                        selectedLimit = selectedLimit,
                        loadedItems = calls.size,
                        allLoaded = selectedLimit.limit == null,
                        selectedDateRange = selectedDateRange
                    )
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
