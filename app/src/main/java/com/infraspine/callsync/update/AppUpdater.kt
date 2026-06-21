package com.infraspine.callsync.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File

object AppUpdater {

    private const val APK_NAME = "infraspine-update.apk"

    fun downloadAndInstall(context: Context, downloadUrl: String) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // Delete any leftover APK from a previous attempt
        apkFile(context).runCatching { delete() }

        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("InfraSpine Update")
            .setDescription("Downloading new version…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
            .setMimeType("application/vnd.android.package-archive")

        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return

                runCatching { ctx.unregisterReceiver(this) }

                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                if (!cursor.moveToFirst()) { cursor.close(); return }

                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                cursor.close()

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    triggerInstall(ctx.applicationContext, apkFile(ctx))
                }
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun triggerInstall(context: Context, apkFile: File) {
        if (!apkFile.exists()) return
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
        context.startActivity(intent)
    }

    private fun apkFile(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
}
