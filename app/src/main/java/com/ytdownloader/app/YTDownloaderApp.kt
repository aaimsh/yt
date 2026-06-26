package com.ytdownloader.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.ytdownloader.app.extractor.NewPipeInitializer
import com.ytdownloader.app.util.CrashLog

class YTDownloaderApp : Application() {
    companion object {
        const val CHANNEL_DOWNLOAD = "download_channel"
    }

    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
        createNotificationChannels()
        // Cheap: only stores statics + lazily builds an OkHttp client. No network here.
        NewPipeInitializer.init()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashLog.save(this, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
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
