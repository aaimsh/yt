package com.ytdownloader.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads video/audio files from direct URLs with progress reporting.
 */
object VideoDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    data class PresetFormat(
        val id: String,
        val label: String,
        val description: String,
        val icon: String,
    )

    fun getPresetFormats(): List<PresetFormat> = listOf(
        PresetFormat("best_video", "Best Quality", "Highest available resolution", "high_quality"),
        PresetFormat("1080p", "1080p Full HD", "1920x1080 resolution", "hd"),
        PresetFormat("720p", "720p HD", "1280x720 resolution", "hd"),
        PresetFormat("480p", "480p SD", "Standard definition, smaller file", "sd"),
        PresetFormat("audio_only", "Audio Only", "Audio stream only", "music_note"),
    )

    /**
     * Download a video/audio file from a direct URL.
     *
     * @param url Direct stream URL
     * @param outputDir Directory to save the file
     * @param fileName Desired file name (without extension)
     * @param extension File extension (mp4, webm, etc.)
     * @param onProgress Called with progress updates
     * @return Path to the downloaded file
     */
    suspend fun download(
        url: String,
        outputDir: String,
        fileName: String,
        extension: String,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val dir = File(outputDir)
            dir.mkdirs()

            // Sanitize filename
            val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(200)
            val outFile = File(dir, "$safeName.$extension")

            // Avoid overwriting - add number suffix if needed
            val finalFile = if (outFile.exists()) {
                var counter = 1
                var candidate: File
                do {
                    candidate = File(dir, "$safeName ($counter).$extension")
                    counter++
                } while (candidate.exists())
                candidate
            } else {
                outFile
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Download failed: HTTP ${response.code()}")
                )
            }

            val body = response.body()
                ?: return@withContext Result.failure(Exception("Empty response body"))

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L
            var lastProgressUpdate = System.currentTimeMillis()
            var lastBytes = 0L
            val startTime = System.currentTimeMillis()

            onProgress(
                DownloadProgress(
                    state = DownloadState.DOWNLOADING,
                    progress = 0f,
                )
            )

            FileOutputStream(finalFile).use { fos ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        // Check if coroutine is cancelled
                        coroutineContext.ensureActive()

                        fos.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        // Update progress every 200ms
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate >= 200) {
                            val elapsed = (now - lastProgressUpdate) / 1000.0
                            val speed = if (elapsed > 0) {
                                (downloadedBytes - lastBytes) / elapsed
                            } else 0.0

                            val progress = if (totalBytes > 0) {
                                downloadedBytes.toFloat() / totalBytes
                            } else 0f

                            val speedText = formatSpeed(speed)
                            val etaText = if (totalBytes > 0 && speed > 0) {
                                val remaining = totalBytes - downloadedBytes
                                formatEta((remaining / speed).toLong())
                            } else ""

                            onProgress(
                                DownloadProgress(
                                    state = DownloadState.DOWNLOADING,
                                    progress = progress,
                                    speedText = speedText,
                                    etaText = etaText,
                                )
                            )

                            lastProgressUpdate = now
                            lastBytes = downloadedBytes
                        }
                    }
                }
            }

            onProgress(
                DownloadProgress(
                    state = DownloadState.COMPLETED,
                    progress = 1f,
                    outputPath = finalFile.absolutePath,
                )
            )

            Result.success(finalFile.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec > 1024 * 1024 -> "${(bytesPerSec / 1024 / 1024).format(1)} MB/s"
            bytesPerSec > 1024 -> "${(bytesPerSec / 1024).format(1)} KB/s"
            bytesPerSec > 0 -> "${bytesPerSec.toInt()} B/s"
            else -> ""
        }
    }

    private fun formatEta(seconds: Long): String {
        if (seconds <= 0) return ""
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins > 0) "${mins}:${secs.toString().padStart(2, '0')}"
        else "${secs}s"
    }

    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
}
