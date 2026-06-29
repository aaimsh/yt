package com.ytdownloader.app.download.mux

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Merges a video-only file and an audio-only file into a single MP4 using Android's built-in
 * [MediaMuxer] + [MediaExtractor]. This is a *remux* — sample data is copied verbatim with its
 * original presentation timestamps, so there is no re-encode and no quality loss.
 *
 * Only codecs MP4 can carry are supported: H.264/H.265 video + AAC audio. The caller is
 * responsible for selecting compatible streams (YouTube's H.264 video-only + M4A/AAC audio).
 */
object Muxer {

    private const val DEFAULT_BUFFER_SIZE = 1 shl 20 // 1 MB fallback when KEY_MAX_INPUT_SIZE absent

    /**
     * Remux [videoFile] (video track) and [audioFile] (audio track) into [outputFile] as MP4.
     *
     * @param onProgress invoked with a 0..1 fraction of copied sample time.
     * @param isActive polled between samples; return false to abort (throws [InterruptedException]).
     * @throws IOException on track-selection / muxing failure.
     */
    fun muxToMp4(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        onProgress: (Float) -> Unit = {},
        isActive: () -> Boolean = { true },
    ) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            videoExtractor.setDataSource(videoFile.path)
            audioExtractor.setDataSource(audioFile.path)

            val videoTrack = firstTrackOfType(videoExtractor, "video/")
                ?: throw IOException("No video track found in downloaded stream")
            val audioTrack = firstTrackOfType(audioExtractor, "audio/")
                ?: throw IOException("No audio track found in downloaded stream")

            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)

            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)

            muxer = MediaMuxer(outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxVideoIndex = muxer.addTrack(videoFormat)
            val muxAudioIndex = muxer.addTrack(audioFormat)
            muxer.start()
            muxerStarted = true

            // Total time used only for progress; guard against missing/zero durations.
            val totalUs = (durationUs(videoFormat) + durationUs(audioFormat)).coerceAtLeast(1L)
            var copiedUs = 0L

            copiedUs += copyTrack(
                extractor = videoExtractor,
                muxer = muxer,
                muxTrackIndex = muxVideoIndex,
                bufferSize = inputBufferSize(videoFormat),
                totalUs = totalUs,
                baseUs = copiedUs,
                onProgress = onProgress,
                isActive = isActive,
            )
            copiedUs += copyTrack(
                extractor = audioExtractor,
                muxer = muxer,
                muxTrackIndex = muxAudioIndex,
                bufferSize = inputBufferSize(audioFormat),
                totalUs = totalUs,
                baseUs = copiedUs,
                onProgress = onProgress,
                isActive = isActive,
            )
            onProgress(1f)
        } finally {
            if (muxer != null) {
                if (muxerStarted) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    /** Copy every sample of the (already-selected) track; returns the track's last timestamp. */
    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxTrackIndex: Int,
        bufferSize: Int,
        totalUs: Long,
        baseUs: Long,
        onProgress: (Float) -> Unit,
        isActive: () -> Boolean,
    ): Long {
        val buffer = ByteBuffer.allocate(bufferSize)
        val info = MediaCodec.BufferInfo()
        var lastSampleUs = 0L
        while (true) {
            if (!isActive()) throw InterruptedException("Merge cancelled")
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break

            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(muxTrackIndex, buffer, info)

            lastSampleUs = info.presentationTimeUs
            onProgress(((baseUs + lastSampleUs).toFloat() / totalUs).coerceIn(0f, 1f))
            extractor.advance()
        }
        return lastSampleUs
    }

    private fun firstTrackOfType(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith(mimePrefix)) return i
        }
        return null
    }

    private fun durationUs(format: MediaFormat): Long =
        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

    private fun inputBufferSize(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(DEFAULT_BUFFER_SIZE)
        } else {
            DEFAULT_BUFFER_SIZE
        }
}
