package com.infraspine.callsync.ui.synchistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import com.infraspine.callsync.AppContainer
import com.infraspine.callsync.data.local.dao.SyncHistoryDao

class SyncHistoryViewModel(dao: SyncHistoryDao) : ViewModel() {
    val history = dao.observeAll().asLiveData()

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SyncHistoryViewModel(container.syncHistoryDao) as T
    }
}
