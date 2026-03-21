package com.ytdownloader.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class YTDownloaderApp : Application() {
    companion object {
        const val CHANNEL_DOWNLOAD = "download_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_DOWNLOAD,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
