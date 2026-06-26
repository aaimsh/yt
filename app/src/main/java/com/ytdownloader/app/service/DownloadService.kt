package com.ytdownloader.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ytdownloader.app.MainActivity
import com.ytdownloader.app.YTDownloaderApp
import com.ytdownloader.app.domain.DownloadProgress
import com.ytdownloader.app.domain.DownloadState
import com.ytdownloader.app.download.DownloadRepository
import com.ytdownloader.app.download.OpenFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the download coroutine (so it survives the Activity) and
 * mirrors [DownloadRepository.progress] into a notification.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null
    private var collectorJob: Job? = null

    companion object {
        const val ACTION_START = "com.ytdownloader.app.action.START"
        const val ACTION_STOP = "com.ytdownloader.app.action.STOP"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_MIME = "extra_mime"

        private const val NOTIF_ID = 1001
        private const val NOTIF_DONE_ID = 1002
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopDownload()
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val name = intent.getStringExtra(EXTRA_NAME) ?: "video.mp4"
        val mime = intent.getStringExtra(EXTRA_MIME) ?: "video/mp4"

        // Seed a fresh DOWNLOADING state before collecting so we never re-observe a
        // stale COMPLETED/ERROR from a previous run.
        DownloadRepository.markStarting()
        startInForeground(NOTIF_ID, buildProgressNotification("Starting…", 0, indeterminate = true))

        collectorJob?.cancel()
        collectorJob = scope.launch {
            DownloadRepository.progress.collectLatest { p -> onProgress(p) }
        }

        downloadJob?.cancel()
        downloadJob = scope.launch {
            DownloadRepository.download(applicationContext, url, name, mime)
        }

        return START_NOT_STICKY
    }

    private fun onProgress(p: DownloadProgress) {
        when (p.state) {
            DownloadState.DOWNLOADING -> {
                val pct = (p.progress * 100).toInt()
                val parts = buildList {
                    add("$pct%")
                    if (p.speedText.isNotEmpty()) add(p.speedText)
                    if (p.etaText.isNotEmpty()) add("ETA ${p.etaText}")
                }
                notify(NOTIF_ID, buildProgressNotification(parts.joinToString(" · "), pct, p.progress <= 0f))
            }
            DownloadState.COMPLETED -> {
                showDoneNotification("Download complete", p)
                finishService()
            }
            DownloadState.ERROR -> {
                showDoneNotification("Download failed: ${p.errorMessage ?: ""}", null)
                finishService()
            }
            DownloadState.IDLE -> finishService() // cancelled
        }
    }

    private fun stopDownload() {
        downloadJob?.cancel() // triggers partial cleanup in the repository
        finishService()
    }

    private fun finishService() {
        collectorJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun buildProgressNotification(text: String, progress: Int, indeterminate: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, YTDownloaderApp.CHANNEL_DOWNLOAD)
            .setContentTitle("YT Downloader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", stopIntent)
            .build()
    }

    private fun showDoneNotification(text: String, completed: DownloadProgress?) {
        val builder = NotificationCompat.Builder(this, YTDownloaderApp.CHANNEL_DOWNLOAD)
            .setContentTitle("YT Downloader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)

        if (completed?.outputUri != null) {
            val open = OpenFile.intentFor(this, completed.outputUri, completed.mimeType ?: "*/*")
            builder.setContentIntent(
                PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE),
            )
        }
        notify(NOTIF_DONE_ID, builder.build())
    }

    private fun notify(id: Int, notification: Notification) {
        NotificationManagerCompat.from(this).also { manager ->
            if (manager.areNotificationsEnabled()) {
                try {
                    manager.notify(id, notification)
                } catch (_: SecurityException) {
                    // POST_NOTIFICATIONS not granted — download still proceeds.
                }
            }
        }
    }

    private fun startInForeground(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
