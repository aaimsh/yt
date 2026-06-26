package com.ytdownloader.app.download.media

import java.io.OutputStream

/** Abstracts where a downloaded file is written (MediaStore vs. legacy public dir). */
interface MediaSink {
    /** Allocate a destination and open it for writing. */
    fun create(displayName: String, mimeType: String): SinkHandle
}

interface SinkHandle {
    val output: OutputStream
    /** Human-readable location, e.g. "Downloads/YTDownloader/clip.mp4". */
    val displayPath: String
    /** content:// uri (API 29+) or absolute file path (legacy) used to open the file. */
    val openUri: String

    /** Commit the file (clear pending flag / trigger media scan). */
    fun finish()

    /** Discard a partial/failed download. */
    fun delete()
}
