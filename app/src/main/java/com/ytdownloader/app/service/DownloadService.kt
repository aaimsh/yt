package com.ytdownloader.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ytdownloader.app.MainActivity
import com.ytdownloader.app.YTDownloaderApp
import com.ytdownloader.app.util.DownloadProgress
import com.ytdownloader.app.util.DownloadState
import com.ytdownloader.app.util.VideoDownloader
import kotlinx.coroutines.*
import java.io.File

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILENAME = "extra_filename"
        const val EXTRA_EXTENSION = "extra_extension"
        const val NOTIFICATION_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val filename = intent.getStringExtra(EXTRA_FILENAME) ?: "video"
        val extension = intent.getStringExtra(EXTRA_EXTENSION) ?: "mp4"

        startForeground(NOTIFICATION_ID, createNotification("Preparing download...", 0))

        serviceScope.launch {
            val outputDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "YTDownloader"
            ).absolutePath

            VideoDownloader.download(
                url = url,
                outputDir = outputDir,
                fileName = filename,
                extension = extension,
                onProgress = { progress ->
                    updateNotification(progress)
                }
            ).onSuccess {
                showCompleteNotification("Download complete")
            }.onFailure { error ->
                showCompleteNotification("Download failed: ${error.message}")
            }

            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun createNotification(text: String, progress: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, YTDownloaderApp.CHANNEL_DOWNLOAD)
            .setContentTitle("YT Downloader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(progress: DownloadProgress) {
        val text = when (progress.state) {
            DownloadState.DOWNLOADING -> {
                val pct = (progress.progress * 100).toInt()
                "Downloading... $pct% ${progress.speedText}"
            }
            else -> "Processing..."
        }
        val pct = (progress.progress * 100).toInt()
        val notification = createNotification(text, pct)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompleteNotification(text: String) {
        val notification = NotificationCompat.Builder(this, YTDownloaderApp.CHANNEL_DOWNLOAD)
            .setContentTitle("YT Downloader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
