package com.ytdownloader.app.domain

/** Lightweight metadata about a resolved video. */
data class VideoMeta(
    val title: String,
    val uploader: String?,
    val durationSec: Long,
)

enum class StreamKind { VIDEO, AUDIO }

/**
 * A single, ready-to-download option resolved from a YouTube stream.
 *
 * A VIDEO option is either a progressive (muxed) stream that already contains audio, or a
 * video-only stream that must be merged with a separate audio track on the device. When
 * [requiresMux] is true, [url] is the video-only stream and [audioUrl] is the audio track to
 * merge with it; otherwise [url] is a single ready-to-save stream.
 */
data class StreamOption(
    val id: String,
    val url: String,
    val container: String,   // mp4 / webm / m4a
    val label: String,       // "720p" or "Audio only"
    val sublabel: String,    // "MP4 · with audio" / "M4A · 128 kbps"
    val kind: StreamKind,
    val audioUrl: String? = null,   // audio track to merge, set only when requiresMux
    val requiresMux: Boolean = false,
)

/** The result of fetching a video: its metadata plus every downloadable option. */
data class StreamSelection(
    val meta: VideoMeta,
    val options: List<StreamOption>,
)

enum class DownloadState { IDLE, DOWNLOADING, COMPLETED, ERROR }

data class DownloadProgress(
    val state: DownloadState = DownloadState.IDLE,
    val progress: Float = 0f,
    val phaseText: String = "",      // "Downloading video" / "Downloading audio" / "Merging"
    val speedText: String = "",
    val etaText: String = "",
    val errorMessage: String? = null,
    val outputUri: String? = null,   // content:// uri (API 29+) or absolute file path (legacy)
    val outputName: String? = null,
    val mimeType: String? = null,
)
