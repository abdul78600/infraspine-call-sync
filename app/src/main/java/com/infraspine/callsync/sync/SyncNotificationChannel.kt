package com.infraspine.callsync.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object SyncNotificationChannel {
    const val CHANNEL_ID = "infraspine_sync"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sync Progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress while syncing recordings to the CRM server"
            setShowBadge(false)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}
