package com.infraspine.callsync.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

sealed class UpdateInstallEvent {
    object DownloadStarted : UpdateInstallEvent()
    object InstallPromptLaunched : UpdateInstallEvent()
    object InstallPermissionRequired : UpdateInstallEvent()
    data class Failed(val message: String) : UpdateInstallEvent()
}

object AppUpdater {

    private const val APK_NAME = "infraspine-update.apk"

    fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        onEvent: (UpdateInstallEvent) -> Unit
    ) {
        val appContext = context.applicationContext
        val notify = { event: UpdateInstallEvent ->
            Handler(Looper.getMainLooper()).post { onEvent(event) }
        }

        if (!canInstallPackages(appContext)) {
            openUnknownAppsSettings(appContext)
            notify(UpdateInstallEvent.InstallPermissionRequired)
            return
        }

        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        apkFile(appContext).runCatching { delete() }

        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("InfraSpine Update")
            .setDescription("Downloading update...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
            .setMimeType("application/vnd.android.package-archive")

        val downloadId = runCatching { dm.enqueue(request) }
            .getOrElse {
                notify(UpdateInstallEvent.Failed(it.message ?: "Could not start update download"))
                return
            }

        notify(UpdateInstallEvent.DownloadStarted)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return

                runCatching { appContext.unregisterReceiver(this) }

                val result = queryDownloadResult(dm, downloadId)
                when (result) {
                    is DownloadResult.Success -> {
                        if (!canInstallPackages(appContext)) {
                            openUnknownAppsSettings(appContext)
                            notify(UpdateInstallEvent.InstallPermissionRequired)
                            return
                        }
                        val launched = triggerInstall(appContext, apkFile(appContext))
                        if (launched) {
                            notify(UpdateInstallEvent.InstallPromptLaunched)
                        } else {
                            notify(UpdateInstallEvent.Failed("Downloaded APK was not available for installation"))
                        }
                    }

                    is DownloadResult.Failed -> notify(UpdateInstallEvent.Failed(result.message))
                }
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun openUnknownAppsSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun triggerInstall(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) return false
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun queryDownloadResult(dm: DownloadManager, downloadId: Long): DownloadResult {
        val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
        cursor.use {
            if (!it.moveToFirst()) {
                return DownloadResult.Failed("Update download could not be found")
            }

            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                return DownloadResult.Success
            }

            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return DownloadResult.Failed(downloadFailureMessage(reason))
        }
    }

    private fun downloadFailureMessage(reason: Int): String =
        when (reason) {
            DownloadManager.ERROR_CANNOT_RESUME -> "Update download could not resume"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage for update download was not available"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "Downloaded APK already exists"
            DownloadManager.ERROR_FILE_ERROR -> "Could not write the update APK"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "Server returned invalid update data"
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage space for the update APK"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Update download redirected too many times"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Server rejected the update download"
            DownloadManager.ERROR_UNKNOWN -> "Unknown update download failure"
            else -> "Update download failed (reason $reason)"
        }

    private fun apkFile(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
}

private sealed class DownloadResult {
    object Success : DownloadResult()
    data class Failed(val message: String) : DownloadResult()
}
