package com.ytdownloader.app.viewmodel

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ytdownloader.app.util.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class UiState(
    val url: String = "",
    val videoInfo: VideoInfo? = null,
    val downloadProgress: DownloadProgress = DownloadProgress(),
    val selectedPreset: String = "best_video",
    val presets: List<YtDlpWrapper.PresetFormat> = emptyList(),
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
            presets = YtDlpWrapper.getPresetFormats()
        )
    }

    fun updateUrl(url: String) {
        _uiState.value = _uiState.value.copy(url = url)
    }

    fun selectPreset(presetId: String) {
        _uiState.value = _uiState.value.copy(selectedPreset = presetId)
    }

    fun fetchInfo() {
        val url = _uiState.value.url.trim()
        if (url.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            downloadProgress = DownloadProgress(state = DownloadState.FETCHING_INFO)
        )

        viewModelScope.launch {
            val result = YtDlpWrapper.fetchVideoInfo(url)
            result.onSuccess { info ->
                _uiState.value = _uiState.value.copy(
                    videoInfo = info,
                    downloadProgress = DownloadProgress(state = DownloadState.READY),
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    downloadProgress = DownloadProgress(
                        state = DownloadState.ERROR,
                        errorMessage = error.message ?: "Failed to fetch video info",
                    )
                )
            }
        }
    }

    fun startDownload() {
        val url = _uiState.value.url.trim()
        if (url.isEmpty()) return

        _uiState.value = _uiState.value.copy(
            downloadProgress = DownloadProgress(state = DownloadState.DOWNLOADING)
        )

        viewModelScope.launch {
            val result = YtDlpWrapper.downloadWithPreset(
                url = url,
                outputDir = downloadsDir.absolutePath,
                presetId = _uiState.value.selectedPreset,
                onProgress = { progress ->
                    _uiState.value = _uiState.value.copy(downloadProgress = progress)
                }
            )

            result.onSuccess { filename ->
                val title = _uiState.value.videoInfo?.title ?: "Downloaded video"
                val record = DownloadRecord(
                    title = title,
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
