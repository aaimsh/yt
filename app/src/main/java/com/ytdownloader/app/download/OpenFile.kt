package com.ytdownloader.app.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Builds an ACTION_VIEW intent for a finished download (content:// uri or file path). */
object OpenFile {
    fun intentFor(context: Context, uriString: String, mimeType: String): Intent {
        val uri: Uri = if (uriString.startsWith("content://")) {
            Uri.parse(uriString)
        } else {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(uriString),
            )
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
