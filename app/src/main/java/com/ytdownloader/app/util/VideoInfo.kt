package com.ytdownloader.app.util

data class VideoFormat(
    val formatId: String,
    val extension: String,
    val resolution: String,
    val fileSize: Long?,
    val description: String,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
)

data class VideoInfo(
    val title: String,
    val thumbnail: String?,
    val duration: Long?,
    val uploader: String?,
    val formats: List<VideoFormat>,
)

enum class DownloadState {
    IDLE,
    FETCHING_INFO,
    READY,
    DOWNLOADING,
    MERGING,
    COMPLETED,
    ERROR,
}

data class DownloadProgress(
    val state: DownloadState = DownloadState.IDLE,
    val progress: Float = 0f,
    val speedText: String = "",
    val etaText: String = "",
    val errorMessage: String? = null,
    val outputPath: String? = null,
)
