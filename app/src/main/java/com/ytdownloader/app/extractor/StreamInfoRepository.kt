package com.ytdownloader.app.extractor

import com.ytdownloader.app.domain.StreamKind
import com.ytdownloader.app.domain.StreamOption
import com.ytdownloader.app.domain.StreamSelection
import com.ytdownloader.app.domain.VideoMeta
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Fetches a video's metadata + downloadable streams via NewPipeExtractor.
 *
 * Only progressive (muxed) video streams are surfaced as video options, because there is
 * no ffmpeg muxing step — so the highest combined-with-audio resolution YouTube serves
 * (typically 720p) is the ceiling. 1080p+ exist only as silent video-only streams and are
 * deliberately excluded. The best audio-only stream is offered separately.
 */
class StreamInfoRepository(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun fetch(url: String): Result<StreamSelection> = withContext(io) {
        runCatching {
            NewPipeInitializer.init()
            // Blocking network I/O + Rhino JS deciphering — must stay on IO.
            val info = StreamInfo.getInfo(ServiceList.YouTube, url)
            map(info)
        }
    }

    private fun map(info: StreamInfo): StreamSelection {
        val meta = VideoMeta(
            title = info.name?.takeIf { it.isNotBlank() } ?: "Untitled",
            uploader = info.uploaderName?.takeIf { it.isNotBlank() },
            durationSec = info.duration.coerceAtLeast(0),
        )

        val options = mutableListOf<StreamOption>()

        // Distinct progressive resolutions, highest first.
        val seenHeights = mutableSetOf<Int>()
        info.videoStreams
            .asSequence()
            .filter { !it.isVideoOnly }
            .mapNotNull { vs -> heightOf(vs).takeIf { it > 0 }?.let { it to vs } }
            .sortedByDescending { it.first }
            .forEach { (height, vs) ->
                if (seenHeights.add(height)) {
                    val container = vs.format?.suffix ?: "mp4"
                    options += StreamOption(
                        id = "v$height",
                        url = vs.content,
                        container = container,
                        label = "${height}p",
                        sublabel = "${container.uppercase()} · with audio",
                        kind = StreamKind.VIDEO,
                    )
                }
            }

        // Best audio-only stream.
        info.audioStreams
            .maxByOrNull { it.averageBitrate }
            ?.let { audio ->
                val container = audio.format?.suffix ?: "m4a"
                val bitrate = audio.averageBitrate
                options += StreamOption(
                    id = "audio",
                    url = audio.content,
                    container = container,
                    label = "Audio only",
                    sublabel = buildString {
                        append(container.uppercase())
                        if (bitrate > 0) append(" · $bitrate kbps")
                    },
                    kind = StreamKind.AUDIO,
                )
            }

        if (options.isEmpty()) {
            throw IllegalStateException("No downloadable streams available for this video")
        }

        return StreamSelection(meta, options)
    }

    /** Parse leading digits of a resolution string like "1080p60" -> 1080. */
    private fun heightOf(stream: VideoStream): Int {
        val res = stream.resolution ?: return 0
        return res.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
}
