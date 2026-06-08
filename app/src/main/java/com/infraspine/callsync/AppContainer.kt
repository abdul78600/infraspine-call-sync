package com.infraspine.callsync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.infraspine.callsync.data.local.CallSyncDatabase
import com.infraspine.callsync.data.prefs.SecureSettingsStore
import com.infraspine.callsync.data.remote.CrmApiFactory
import com.infraspine.callsync.data.repository.CallHistoryRepository
import com.infraspine.callsync.data.repository.RecordingRepository
import com.infraspine.callsync.data.repository.SyncRepository
import com.infraspine.callsync.domain.util.NetworkMonitor
import com.infraspine.callsync.scan.CallLogMatcher
import com.infraspine.callsync.scan.RecordingFolderManager
import com.infraspine.callsync.scan.RecordingScanner
import com.infraspine.callsync.update.UpdateChecker

/**
 * Minimal hand-rolled DI container — the app is small enough that a framework
 * (Hilt/Koin) would add more ceremony than value. Everything is constructed once
 * and reused for the process lifetime via [CallSyncApplication.container].
 */
class AppContainer(private val context: Context) {

    val settingsStore: SecureSettingsStore by lazy { SecureSettingsStore(context) }

    val database: CallSyncDatabase by lazy { CallSyncDatabase.getInstance(context) }

    val folderManager: RecordingFolderManager by lazy { RecordingFolderManager(context, settingsStore) }

    private val scanner: RecordingScanner by lazy { RecordingScanner(context) }

    private val callLogMatcher: CallLogMatcher by lazy { CallLogMatcher(context) }

    private val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(context) }

    private val apiFactory: CrmApiFactory by lazy { CrmApiFactory(settingsStore) }

    val recordingRepository: RecordingRepository by lazy {
        RecordingRepository(
            dao = database.recordingDao(),
            folderManager = folderManager,
            scanner = scanner,
            callLogMatcher = callLogMatcher,
            hasCallLogPermission = { hasCallLogPermission() }
        )
    }

    val syncRepository: SyncRepository by lazy {
        SyncRepository(
            context = context,
            dao = database.recordingDao(),
            settingsStore = settingsStore,
            networkMonitor = networkMonitor,
            apiFactory = apiFactory
        )
    }

    val callHistoryRepository: CallHistoryRepository by lazy {
        CallHistoryRepository(
            dao = database.recordingDao(),
            callLogMatcher = callLogMatcher
        )
    }

    val updateChecker: UpdateChecker by lazy { UpdateChecker(context) }

    fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
}
