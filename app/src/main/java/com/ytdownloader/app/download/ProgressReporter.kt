package com.ytdownloader.app.download

/** Computes a windowed download speed and ETA, and formats them for display. */
class ProgressReporter {

    private var windowStartMs = 0L
    private var windowStartBytes = 0L

    fun reset(nowMs: Long) {
        windowStartMs = nowMs
        windowStartBytes = 0L
    }

    data class Sample(val speedText: String, val etaText: String)

    fun sample(nowMs: Long, downloadedBytes: Long, totalBytes: Long): Sample {
        val elapsed = (nowMs - windowStartMs) / 1000.0
        val speed = if (elapsed > 0) (downloadedBytes - windowStartBytes) / elapsed else 0.0
        windowStartMs = nowMs
        windowStartBytes = downloadedBytes

        val etaSeconds = if (totalBytes > 0 && speed > 0) {
            ((totalBytes - downloadedBytes) / speed).toLong()
        } else {
            -1L
        }
        return Sample(formatSpeed(speed), formatEta(etaSeconds))
    }

    private fun formatSpeed(bytesPerSec: Double): String = when {
        bytesPerSec >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSec / 1024 / 1024)
        bytesPerSec >= 1024 -> "%.1f KB/s".format(bytesPerSec / 1024)
        bytesPerSec > 0 -> "${bytesPerSec.toInt()} B/s"
        else -> ""
    }

    private fun formatEta(seconds: Long): String {
        if (seconds < 0) return ""
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins > 0) "$mins:${secs.toString().padStart(2, '0')}" else "${secs}s"
    }
}
