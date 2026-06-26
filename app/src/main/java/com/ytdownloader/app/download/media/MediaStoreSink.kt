package com.ytdownloader.app.download.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.IOException
import java.io.OutputStream

/** Scoped-storage sink for API 29+. Requires no storage permission. */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreSink(private val context: Context) : MediaSink {

    override fun create(displayName: String, mimeType: String): SinkHandle {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/YTDownloader",
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create download entry")
        val stream = resolver.openOutputStream(uri)
            ?: run {
                resolver.delete(uri, null, null)
                throw IOException("Could not open output stream")
            }

        return object : SinkHandle {
            override val output: OutputStream = stream
            override val displayPath = "Downloads/YTDownloader/$displayName"
            override val openUri = uri.toString()

            override fun finish() {
                runCatching { output.close() }
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }

            override fun delete() {
                runCatching { output.close() }
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }
}
