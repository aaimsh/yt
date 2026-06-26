package com.ytdownloader.app.download

import android.content.Context
import android.os.Build
import com.ytdownloader.app.domain.DownloadProgress
import com.ytdownloader.app.domain.DownloadState
import com.ytdownloader.app.download.media.LegacyFileSink
import com.ytdownloader.app.download.media.MediaSink
import com.ytdownloader.app.download.media.MediaStoreSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Single source of truth for the (one-at-a-time) download. Exposes [progress] as a
 * process-wide StateFlow so both the foreground service notification and the UI observe
 * the same stream. The media bytes are streamed with raw OkHttp (not the NewPipe
 * downloader, which buffers to memory).
 */
object DownloadRepository {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    private fun sinkFor(context: Context): MediaSink =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStoreSink(context.applicationContext)
        } else {
            LegacyFileSink(context.applicationContext)
        }

    /** Set before the service starts collecting, so a stale COMPLETED isn't re-observed. */
    fun markStarting() {
        _progress.value = DownloadProgress(state = DownloadState.DOWNLOADING)
    }

    fun reset() {
        _progress.value = DownloadProgress()
    }

    suspend fun download(
        context: Context,
        url: String,
        displayName: String,
        mimeType: String,
    ) = withContext(Dispatchers.IO) {
        val handle = try {
            sinkFor(context).create(displayName, mimeType)
        } catch (e: Exception) {
            _progress.value = DownloadProgress(
                state = DownloadState.ERROR,
                errorMessage = e.message ?: "Could not create file",
            )
            return@withContext
        }

        try {
            _progress.value = DownloadProgress(state = DownloadState.DOWNLOADING)

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response body")

            val total = body.contentLength()
            var downloaded = 0L
            val reporter = ProgressReporter()
            var lastTick = System.currentTimeMillis()
            reporter.reset(lastTick)

            body.byteStream().use { input ->
                handle.output.use { out ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        coroutineContext.ensureActive()
                        out.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastTick >= 200) {
                            val sample = reporter.sample(now, downloaded, total)
                            _progress.value = DownloadProgress(
                                state = DownloadState.DOWNLOADING,
                                progress = if (total > 0) downloaded.toFloat() / total else 0f,
                                speedText = sample.speedText,
                                etaText = sample.etaText,
                            )
                            lastTick = now
                        }
                    }
                    out.flush()
                }
            }

            handle.finish()
            _progress.value = DownloadProgress(
                state = DownloadState.COMPLETED,
                progress = 1f,
                outputUri = handle.openUri,
                outputName = displayName,
                mimeType = mimeType,
            )
        } catch (e: CancellationException) {
            handle.delete()
            _progress.value = DownloadProgress(state = DownloadState.IDLE)
            throw e
        } catch (e: Exception) {
            handle.delete()
            _progress.value = DownloadProgress(
                state = DownloadState.ERROR,
                errorMessage = e.message ?: "Download failed",
            )
        }
    }
}
