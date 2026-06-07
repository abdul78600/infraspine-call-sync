package com.infraspine.callsync.ui.settings

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.infraspine.callsync.AppContainer
import com.infraspine.callsync.data.prefs.SecureSettingsStore
import com.infraspine.callsync.domain.util.DeviceIdProvider
import com.infraspine.callsync.sync.SyncScheduler

data class SettingsUiState(
    val crmServerUrl: String,
    val agentToken: String,
    val deviceId: String,
    val syncOnWifiOnly: Boolean,
    val autoSyncEnabled: Boolean,
    val dummyTestMode: Boolean
)

class SettingsViewModel(
    private val context: Context,
    private val settingsStore: SecureSettingsStore
) : ViewModel() {

    private val _uiState = MutableLiveData(loadCurrentState())
    val uiState: LiveData<SettingsUiState> = _uiState

    private val _saved = MutableLiveData<Boolean>()
    val saved: LiveData<Boolean> = _saved

    private fun loadCurrentState(): SettingsUiState = SettingsUiState(
        crmServerUrl = settingsStore.crmServerUrl.orEmpty(),
        agentToken = settingsStore.agentToken.orEmpty(),
        deviceId = DeviceIdProvider.getOrCreate(context, settingsStore),
        syncOnWifiOnly = settingsStore.syncOnWifiOnly,
        autoSyncEnabled = settingsStore.autoSyncEnabled,
        dummyTestMode = settingsStore.dummyTestMode
    )

    /**
     * Persists all settings at once. The agent token is written straight into
     * EncryptedSharedPreferences — never logged, never echoed back in any message.
     */
    fun save(
        crmServerUrl: String,
        agentToken: String,
        syncOnWifiOnly: Boolean,
        autoSyncEnabled: Boolean,
        dummyTestMode: Boolean
    ) {
        settingsStore.crmServerUrl = crmServerUrl.trim().takeIf { it.isNotBlank() }
        settingsStore.agentToken = agentToken.trim().takeIf { it.isNotBlank() }
        settingsStore.syncOnWifiOnly = syncOnWifiOnly
        settingsStore.autoSyncEnabled = autoSyncEnabled
        settingsStore.dummyTestMode = dummyTestMode

        SyncScheduler.apply(context, autoSyncEnabled, syncOnWifiOnly)

        _uiState.value = loadCurrentState()
        _saved.value = true
    }

    class Factory(private val context: Context, private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(context.applicationContext, container.settingsStore) as T
        }
    }
}
