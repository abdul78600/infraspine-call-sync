package com.infraspine.callsync.ui.dashboard

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.infraspine.callsync.AppContainer
import com.infraspine.callsync.data.repository.RecordingRepository
import com.infraspine.callsync.data.repository.ScanResult
import com.infraspine.callsync.data.repository.SyncRepository
import com.infraspine.callsync.data.repository.SyncResult
import com.infraspine.callsync.domain.model.SyncStatus
import com.infraspine.callsync.ui.common.Event
import kotlinx.coroutines.launch

data class DashboardCounts(
    val total: Int = 0,
    val pending: Int = 0,
    val synced: Int = 0,
    val failed: Int = 0,
    val unmatched: Int = 0
)

sealed class DashboardMessage {
    data class ScanFinished(val newCount: Int, val totalScanned: Int) : DashboardMessage()
    object ScanNoFolder : DashboardMessage()
    object ScanNoRecordings : DashboardMessage()
    object ScanCallLogPermissionDenied : DashboardMessage()
    data class ScanError(val message: String) : DashboardMessage()

    data class SyncFinished(
        val uploaded: Int,
        val failed: Int,
        val callLogsUploaded: Int,
        val callLogsSkipped: Int,
        val callLogsFailed: Int,
        val callLogsError: String?
    ) : DashboardMessage()
    object SyncNothingPending : DashboardMessage()
    object SyncNetworkUnavailable : DashboardMessage()
    object SyncWifiRequired : DashboardMessage()
    object SyncApiNotConfigured : DashboardMessage()
}

class DashboardViewModel(
    private val recordingRepository: RecordingRepository,
    private val syncRepository: SyncRepository
) : ViewModel() {

    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _isSyncing = MutableLiveData(false)
    val isSyncing: LiveData<Boolean> = _isSyncing

    private val _message = MutableLiveData<Event<DashboardMessage>>()
    val message: LiveData<Event<DashboardMessage>> = _message

    val counts: LiveData<DashboardCounts> = MediatorLiveData<DashboardCounts>().apply {
        var total = 0
        var pending = 0
        var synced = 0
        var failed = 0
        var unmatched = 0

        fun emit() {
            value = DashboardCounts(total, pending, synced, failed, unmatched)
        }

        addSource(recordingRepository.observeTotalCount().asLiveData()) { total = it; emit() }
        addSource(recordingRepository.observeCountByStatus(SyncStatus.PENDING).asLiveData()) { pending = it; emit() }
        addSource(recordingRepository.observeCountByStatus(SyncStatus.SYNCED).asLiveData()) { synced = it; emit() }
        addSource(recordingRepository.observeCountByStatus(SyncStatus.FAILED).asLiveData()) { failed = it; emit() }
        addSource(recordingRepository.observeCountByStatus(SyncStatus.UNMATCHED).asLiveData()) { unmatched = it; emit() }
    }

    fun scanNow() {
        if (_isScanning.value == true) return
        viewModelScope.launch {
            _isScanning.value = true
            val result = recordingRepository.scanAndMatch()
            _isScanning.value = false

            _message.value = Event(
                when (result) {
                    is ScanResult.Success -> DashboardMessage.ScanFinished(result.newRecordingsFound, result.totalScanned)
                    ScanResult.NoFolderSelected -> DashboardMessage.ScanNoFolder
                    ScanResult.NoRecordingsFound -> DashboardMessage.ScanNoRecordings
                    ScanResult.CallLogPermissionDenied -> DashboardMessage.ScanCallLogPermissionDenied
                    is ScanResult.Error -> DashboardMessage.ScanError(result.message)
                }
            )
        }
    }

    fun syncNow() {
        if (_isSyncing.value == true) return
        viewModelScope.launch {
            _isSyncing.value = true
            val result = syncRepository.syncPending()
            _isSyncing.value = false

            _message.value = Event(
                when (result) {
                    is SyncResult.Completed -> DashboardMessage.SyncFinished(
                        uploaded = result.uploaded,
                        failed = result.failed,
                        callLogsUploaded = result.callLogsUploaded,
                        callLogsSkipped = result.callLogsSkipped,
                        callLogsFailed = result.callLogsFailed,
                        callLogsError = result.callLogsError
                    )
                    SyncResult.NothingToSync -> DashboardMessage.SyncNothingPending
                    SyncResult.NetworkUnavailable -> DashboardMessage.SyncNetworkUnavailable
                    SyncResult.WifiRequired -> DashboardMessage.SyncWifiRequired
                    SyncResult.ApiNotConfigured -> DashboardMessage.SyncApiNotConfigured
                }
            )
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(
                container.recordingRepository,
                container.syncRepository
            ) as T
        }
    }
}
