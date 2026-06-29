package com.ytdownloader.app.extractor

import com.ytdownloader.app.domain.StreamKind
import com.ytdownloader.app.domain.StreamOption
import com.ytdownloader.app.domain.StreamSelection
import com.ytdownloader.app.domain.VideoMeta
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Fetches a video's metadata + downloadable streams via NewPipeExtractor.
 *
 * Progressive (already-muxed) video streams are surfaced as-is — YouTube caps these at ~720p.
 * Higher resolutions exist only as silent video-only streams, so for H.264/MP4 video-only
 * streams above the best progressive height we pair the best AAC (M4A) audio track and mark the
 * option for on-device merging (see [com.ytdownloader.app.download.mux.Muxer]). The best
 * audio-only stream is also offered on its own.
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

        // Video options paired with their height so the merged list can be sorted highest-first.
        val videoOptions = mutableListOf<Pair<Int, StreamOption>>()
        val seenHeights = mutableSetOf<Int>()

        // Distinct progressive (already-muxed) resolutions.
        var bestProgressiveHeight = 0
        info.videoStreams
            .asSequence()
            .filter { !it.isVideoOnly }
            .mapNotNull { vs -> heightOf(vs).takeIf { it > 0 }?.let { it to vs } }
            .sortedByDescending { it.first }
            .forEach { (height, vs) ->
                if (seenHeights.add(height)) {
                    bestProgressiveHeight = maxOf(bestProgressiveHeight, height)
                    val container = vs.format?.suffix ?: "mp4"
                    videoOptions += height to StreamOption(
                        id = "v$height",
                        url = vs.content,
                        container = container,
                        label = "${height}p",
                        sublabel = "${container.uppercase()} · with audio",
                        kind = StreamKind.VIDEO,
                    )
                }
            }

        // Higher resolutions: H.264/MP4 video-only streams merged with the best AAC audio.
        // Restricting to MPEG_4 video + M4A audio keeps the merge on MediaMuxer's reliable path.
        val bestAac = info.audioStreams
            .filter { it.format == MediaFormat.M4A }
            .maxByOrNull { it.averageBitrate }
        if (bestAac != null) {
            info.videoOnlyStreams
                .asSequence()
                .filter { it.format == MediaFormat.MPEG_4 }
                .mapNotNull { vs -> heightOf(vs).takeIf { it > bestProgressiveHeight }?.let { it to vs } }
                .sortedByDescending { it.first }
                .forEach { (height, vs) ->
                    if (seenHeights.add(height)) {
                        videoOptions += height to StreamOption(
                            id = "mux$height",
                            url = vs.content,
                            container = "mp4",
                            label = "${height}p",
                            sublabel = "MP4 · video+audio merged",
                            kind = StreamKind.VIDEO,
                            audioUrl = bestAac.content,
                            requiresMux = true,
                        )
                    }
                }
        }

        // Highest resolution first, regardless of progressive vs. merged.
        videoOptions.sortedByDescending { it.first }.forEach { options += it.second }

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
