package com.ytdownloader.app.viewmodel

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ytdownloader.app.domain.DownloadProgress
import com.ytdownloader.app.domain.StreamKind
import com.ytdownloader.app.domain.StreamOption
import com.ytdownloader.app.domain.StreamSelection
import com.ytdownloader.app.download.DownloadRepository
import com.ytdownloader.app.extractor.StreamInfoRepository
import com.ytdownloader.app.service.DownloadService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiState(
    val url: String = "",
    val isFetching: Boolean = false,
    val fetchError: String? = null,
    val selection: StreamSelection? = null,
    val selectedOptionId: String? = null,
    val download: DownloadProgress = DownloadProgress(),
)

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val streamRepo = StreamInfoRepository()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // Mirror the process-wide download progress into the UI state.
        viewModelScope.launch {
            DownloadRepository.progress.collect { progress ->
                _uiState.value = _uiState.value.copy(download = progress)
            }
        }
    }

    fun updateUrl(url: String) {
        _uiState.value = _uiState.value.copy(url = url)
    }

    fun selectOption(id: String) {
        _uiState.value = _uiState.value.copy(selectedOptionId = id)
    }

    fun fetch() {
        val url = _uiState.value.url.trim()
        if (url.isEmpty()) return

        DownloadRepository.reset()
        _uiState.value = _uiState.value.copy(
            isFetching = true,
            fetchError = null,
            selection = null,
            selectedOptionId = null,
            download = DownloadProgress(),
        )

        viewModelScope.launch {
            streamRepo.fetch(url)
                .onSuccess { selection ->
                    _uiState.value = _uiState.value.copy(
                        isFetching = false,
                        selection = selection,
                        selectedOptionId = selection.options.firstOrNull()?.id,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isFetching = false,
                        fetchError = friendlyMessage(error),
                    )
                }
        }
    }

    fun startDownload() {
        val state = _uiState.value
        val selection = state.selection ?: return
        val option = selection.options.firstOrNull { it.id == state.selectedOptionId }
            ?: selection.options.firstOrNull()
            ?: return

        val context = getApplication<Application>()
        val intent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_URL, option.url)
            putExtra(DownloadService.EXTRA_NAME, fileNameFor(selection.meta.title, option))
            putExtra(DownloadService.EXTRA_MIME, mimeFor(option))
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun cancelDownload() {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(
            context,
            Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_STOP),
        )
    }

    fun reset() {
        DownloadRepository.reset()
        _uiState.value = UiState(url = _uiState.value.url)
    }

    private fun fileNameFor(title: String, option: StreamOption): String {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(150)
            .ifBlank { "video" }
        return "$safe.${option.container}"
    }

    private fun mimeFor(option: StreamOption): String = when (option.kind) {
        StreamKind.AUDIO -> when (option.container.lowercase()) {
            "webm", "opus" -> "audio/webm"
            "m4a", "mp4" -> "audio/mp4"
            else -> "audio/mpeg"
        }
        StreamKind.VIDEO -> when (option.container.lowercase()) {
            "webm" -> "video/webm"
            else -> "video/mp4"
        }
    }

    private fun friendlyMessage(error: Throwable): String {
        val raw = error.message?.takeIf { it.isNotBlank() }
        // ContentNotAvailableException is the base for region/age/private/removed errors;
        // its message already describes the specific cause.
        if (error is org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException) {
            return raw ?: "This video is not available."
        }
        return raw ?: "Couldn't fetch video info. Check the URL and your connection."
    }
}
