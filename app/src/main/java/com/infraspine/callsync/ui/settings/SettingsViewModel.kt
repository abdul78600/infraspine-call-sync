package com.infraspine.callsync.ui.settings

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.infraspine.callsync.AppContainer
import com.infraspine.callsync.data.prefs.SecureSettingsStore
import com.infraspine.callsync.data.repository.AuthRepository
import com.infraspine.callsync.domain.util.DeviceIdProvider
import com.infraspine.callsync.sync.SyncScheduler
import com.infraspine.callsync.ui.common.Event
import com.infraspine.callsync.update.UpdateCheckResult
import com.infraspine.callsync.update.UpdateChecker
import kotlinx.coroutines.launch

data class SettingsUiState(
    val crmServerUrl: String,
    val loggedInUser: String,
    val deviceId: String,
    val syncOnWifiOnly: Boolean,
    val autoSyncEnabled: Boolean,
    val dummyTestMode: Boolean
)

class SettingsViewModel(
    private val context: Context,
    private val settingsStore: SecureSettingsStore,
    private val authRepository: AuthRepository,
    private val updateChecker: UpdateChecker
) : ViewModel() {

    private val _uiState = MutableLiveData(loadCurrentState())
    val uiState: LiveData<SettingsUiState> = _uiState

    private val _saved = MutableLiveData<Boolean>()
    val saved: LiveData<Boolean> = _saved

    private val _loggedOut = MutableLiveData<Event<Unit>>()
    val loggedOut: LiveData<Event<Unit>> = _loggedOut

    private val _isCheckingForUpdate = MutableLiveData(false)
    val isCheckingForUpdate: LiveData<Boolean> = _isCheckingForUpdate

    private val _updateResult = MutableLiveData<Event<UpdateCheckResult>>()
    val updateResult: LiveData<Event<UpdateCheckResult>> = _updateResult

    fun checkForUpdates() {
        if (_isCheckingForUpdate.value == true) return

        viewModelScope.launch {
            _isCheckingForUpdate.value = true
            val result = updateChecker.checkForUpdate()
            _isCheckingForUpdate.value = false
            _updateResult.value = Event(result)
        }
    }

    private fun loadCurrentState(): SettingsUiState = SettingsUiState(
        crmServerUrl = settingsStore.crmServerUrl.orEmpty(),
        loggedInUser = settingsStore.userName
            ?: settingsStore.userEmail
            ?: settingsStore.userId
            ?: "",
        deviceId = DeviceIdProvider.getOrCreate(context, settingsStore),
        syncOnWifiOnly = settingsStore.syncOnWifiOnly,
        autoSyncEnabled = settingsStore.autoSyncEnabled,
        dummyTestMode = settingsStore.dummyTestMode
    )

    fun save(
        crmServerUrl: String,
        syncOnWifiOnly: Boolean,
        autoSyncEnabled: Boolean,
        dummyTestMode: Boolean
    ) {
        settingsStore.crmServerUrl = crmServerUrl.trim().takeIf { it.isNotBlank() }
        settingsStore.syncOnWifiOnly = syncOnWifiOnly
        val shouldAutoSync = autoSyncEnabled && settingsStore.hasValidSession()
        settingsStore.autoSyncEnabled = shouldAutoSync
        settingsStore.dummyTestMode = dummyTestMode

        SyncScheduler.apply(context, shouldAutoSync, syncOnWifiOnly)

        _uiState.value = loadCurrentState()
        _saved.value = true
    }

    fun logout() {
        authRepository.logout()
        settingsStore.autoSyncEnabled = false
        SyncScheduler.apply(context, autoSyncEnabled = false, wifiOnly = settingsStore.syncOnWifiOnly)
        _uiState.value = loadCurrentState()
        _loggedOut.value = Event(Unit)
    }

    class Factory(private val context: Context, private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                context.applicationContext,
                container.settingsStore,
                container.authRepository,
                container.updateChecker
            ) as T
        }
    }
}
