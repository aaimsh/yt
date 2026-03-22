package com.ytdownloader.app.viewmodel

import android.app.Application
import android.os.Environment
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ytdownloader.app.util.CrashLog
import com.ytdownloader.app.util.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

data class UiState(
    val url: String = "",
    val videoInfo: VideoInfo? = null,
    val downloadProgress: DownloadProgress = DownloadProgress(),
    val selectedPreset: String = "best_video",
    val presets: List<VideoDownloader.PresetFormat> = emptyList(),
    val downloads: List<DownloadRecord> = emptyList(),
)

data class DownloadRecord(
    val title: String,
    val filePath: String,
    val timestamp: Long,
)

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val downloadsDir: File
        get() {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "YTDownloader"
            )
            dir.mkdirs()
            return dir
        }

    init {
        _uiState.value = _uiState.value.copy(
            presets = VideoDownloader.getPresetFormats()
        )
    }

    fun updateUrl(url: String) {
        _uiState.value = _uiState.value.copy(url = url)
    }

    fun selectPreset(presetId: String) {
        _uiState.value = _uiState.value.copy(selectedPreset = presetId)
    }

    private fun showErrorToast(error: Throwable) {
        CrashLog.save(getApplication(), error)
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val trace = sw.toString().take(500)
        val msg = "${error.javaClass.simpleName}: ${error.message}\n$trace"
        Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
    }

    fun fetchInfo() {
        val url = _uiState.value.url.trim()
        if (url.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            downloadProgress = DownloadProgress(state = DownloadState.FETCHING_INFO)
        )

        viewModelScope.launch {
            try {
                val result = YouTubeExtractor.fetchVideoInfo(url)
                result.onSuccess { info ->
                    _uiState.value = _uiState.value.copy(
                        videoInfo = info,
                        downloadProgress = DownloadProgress(state = DownloadState.READY),
                    )
                }.onFailure { error ->
                    showErrorToast(error)
                    _uiState.value = _uiState.value.copy(
                        downloadProgress = DownloadProgress(
                            state = DownloadState.ERROR,
                            errorMessage = error.message ?: "Failed to fetch video info",
                        )
                    )
                }
            } catch (e: Exception) {
                showErrorToast(e)
                _uiState.value = _uiState.value.copy(
                    downloadProgress = DownloadProgress(
                        state = DownloadState.ERROR,
                        errorMessage = e.message ?: "Unexpected error",
                    )
                )
            }
        }
    }

    fun startDownload() {
        val url = _uiState.value.url.trim()
        val videoInfo = _uiState.value.videoInfo ?: return
        val presetId = _uiState.value.selectedPreset

        // Find the best format for the selected preset
        val format = YouTubeExtractor.selectFormat(videoInfo.formats, presetId)
        if (format == null || format.url.isBlank()) {
            _uiState.value = _uiState.value.copy(
                downloadProgress = DownloadProgress(
                    state = DownloadState.ERROR,
                    errorMessage = "No suitable format found for this quality",
                )
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            downloadProgress = DownloadProgress(state = DownloadState.DOWNLOADING)
        )

        viewModelScope.launch {
            val result = VideoDownloader.download(
                url = format.url,
                outputDir = downloadsDir.absolutePath,
                fileName = videoInfo.title,
                extension = format.extension,
                onProgress = { progress ->
                    _uiState.value = _uiState.value.copy(downloadProgress = progress)
                }
            )

            result.onSuccess { filename ->
                val record = DownloadRecord(
                    title = videoInfo.title,
                    filePath = filename,
                    timestamp = System.currentTimeMillis(),
                )
                _uiState.value = _uiState.value.copy(
                    downloadProgress = DownloadProgress(
                        state = DownloadState.COMPLETED,
                        progress = 1f,
                        outputPath = filename,
                    ),
                    downloads = listOf(record) + _uiState.value.downloads,
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    downloadProgress = DownloadProgress(
                        state = DownloadState.ERROR,
                        errorMessage = error.message ?: "Download failed",
                    )
                )
            }
        }
    }

    fun reset() {
        _uiState.value = _uiState.value.copy(
            url = "",
            videoInfo = null,
            downloadProgress = DownloadProgress(),
        )
    }
}
