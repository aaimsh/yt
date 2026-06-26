package com.ytdownloader.app.download.media

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.io.OutputStream

/** Public-directory sink for API 26-28 (requires WRITE_EXTERNAL_STORAGE). */
class LegacyFileSink(private val context: Context) : MediaSink {

    override fun create(displayName: String, mimeType: String): SinkHandle {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "YTDownloader",
        )
        dir.mkdirs()

        var file = File(dir, displayName)
        if (file.exists()) {
            val dot = displayName.lastIndexOf('.')
            val base = if (dot > 0) displayName.substring(0, dot) else displayName
            val ext = if (dot > 0) displayName.substring(dot) else ""
            var counter = 1
            do {
                file = File(dir, "$base ($counter)$ext")
                counter++
            } while (file.exists())
        }

        val target = file
        val stream = target.outputStream()

        return object : SinkHandle {
            override val output: OutputStream = stream
            override val displayPath = "Downloads/YTDownloader/${target.name}"
            override val openUri = target.absolutePath

            override fun finish() {
                runCatching { output.close() }
                MediaScannerConnection.scanFile(
                    context, arrayOf(target.absolutePath), arrayOf(mimeType), null,
                )
            }

            override fun delete() {
                runCatching { output.close() }
                runCatching { target.delete() }
            }
        }
    }
}
