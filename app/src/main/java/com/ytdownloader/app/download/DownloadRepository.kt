package com.ytdownloader.app.download

import android.content.Context
import android.os.Build
import com.ytdownloader.app.domain.DownloadProgress
import com.ytdownloader.app.domain.DownloadState
import com.ytdownloader.app.download.media.LegacyFileSink
import com.ytdownloader.app.download.media.MediaSink
import com.ytdownloader.app.download.media.MediaStoreSink
import com.ytdownloader.app.download.media.SinkHandle
import com.ytdownloader.app.download.mux.Muxer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
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

    /** Single progressive (already-muxed) stream or audio-only — saved straight to the sink. */
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
            val reporter = ProgressReporter().apply { reset(System.currentTimeMillis()) }

            handle.output.use { out ->
                streamTo(url, out) { downloaded, total, now ->
                    val sample = reporter.sample(now, downloaded, total)
                    _progress.value = DownloadProgress(
                        state = DownloadState.DOWNLOADING,
                        progress = fraction(downloaded, total),
                        speedText = sample.speedText,
                        etaText = sample.etaText,
                    )
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

    /**
     * Higher-resolution path: download a video-only stream and an audio stream to the cache,
     * remux them into a single MP4 with [Muxer], then copy the result into the sink. Progress
     * is split across three phases: video 0–70%, audio 70–85%, merge 85–100%.
     */
    suspend fun downloadMuxed(
        context: Context,
        videoUrl: String,
        audioUrl: String,
        displayName: String,
        mimeType: String,
    ) = withContext(Dispatchers.IO) {
        val tmpDir = File(context.cacheDir, "ytmux").apply {
            deleteRecursively() // clear any stale temp files from a previous run
            mkdirs()
        }
        val videoFile = File(tmpDir, "video.tmp")
        val audioFile = File(tmpDir, "audio.tmp")
        val muxedFile = File(tmpDir, "muxed.mp4")
        val job = coroutineContext[Job]
        var handle: SinkHandle? = null

        try {
            // Phase 1: video-only stream → 0..70%
            val videoReporter = ProgressReporter().apply { reset(System.currentTimeMillis()) }
            videoFile.outputStream().use { out ->
                streamTo(videoUrl, out) { downloaded, total, now ->
                    val sample = videoReporter.sample(now, downloaded, total)
                    emitPhase("Downloading video", band(0f, 0.70f, fraction(downloaded, total)), sample)
                }
            }

            // Phase 2: audio stream → 70..85%
            val audioReporter = ProgressReporter().apply { reset(System.currentTimeMillis()) }
            audioFile.outputStream().use { out ->
                streamTo(audioUrl, out) { downloaded, total, now ->
                    val sample = audioReporter.sample(now, downloaded, total)
                    emitPhase("Downloading audio", band(0.70f, 0.85f, fraction(downloaded, total)), sample)
                }
            }

            // Phase 3: remux → 85..100%
            try {
                Muxer.muxToMp4(
                    videoFile = videoFile,
                    audioFile = audioFile,
                    outputFile = muxedFile,
                    onProgress = { f ->
                        _progress.value = DownloadProgress(
                            state = DownloadState.DOWNLOADING,
                            progress = band(0.85f, 1f, f),
                            phaseText = "Merging",
                        )
                    },
                    isActive = { job?.isActive ?: true },
                )
            } catch (e: InterruptedException) {
                // Muxer aborts with InterruptedException when the coroutine is cancelled.
                throw CancellationException("Cancelled during merge")
            }

            // Phase 4: copy the merged file into the sink (only after a successful merge, so a
            // failed merge never leaves an empty entry in Downloads).
            handle = sinkFor(context).create(displayName, mimeType)
            muxedFile.inputStream().use { input ->
                handle.output.use { out -> input.copyTo(out) }
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
            handle?.delete()
            _progress.value = DownloadProgress(state = DownloadState.IDLE)
            throw e
        } catch (e: Exception) {
            handle?.delete()
            _progress.value = DownloadProgress(
                state = DownloadState.ERROR,
                errorMessage = e.message ?: "Download failed",
            )
        } finally {
            runCatching { tmpDir.deleteRecursively() }
        }
    }

    /**
     * Stream [url] into [out] via OkHttp. Calls [onTick] (downloaded, total, nowMs) at most
     * every 200 ms. Honors coroutine cancellation. Does not close [out].
     */
    private suspend fun streamTo(
        url: String,
        out: OutputStream,
        onTick: (downloaded: Long, total: Long, nowMs: Long) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response body")

            val total = body.contentLength()
            var downloaded = 0L
            var lastTick = System.currentTimeMillis()

            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    coroutineContext.ensureActive()
                    out.write(buffer, 0, read)
                    downloaded += read

                    val now = System.currentTimeMillis()
                    if (now - lastTick >= 200) {
                        onTick(downloaded, total, now)
                        lastTick = now
                    }
                }
                out.flush()
            }
        }
    }

    private fun emitPhase(phase: String, progress: Float, sample: ProgressReporter.Sample) {
        _progress.value = DownloadProgress(
            state = DownloadState.DOWNLOADING,
            progress = progress,
            phaseText = phase,
            speedText = sample.speedText,
            etaText = sample.etaText,
        )
    }

    private fun fraction(downloaded: Long, total: Long): Float =
        if (total > 0) downloaded.toFloat() / total else 0f

    /** Map a 0..1 [local] fraction into the [start]..[end] band. */
    private fun band(start: Float, end: Float, local: Float): Float =
        start + local.coerceIn(0f, 1f) * (end - start)
}
