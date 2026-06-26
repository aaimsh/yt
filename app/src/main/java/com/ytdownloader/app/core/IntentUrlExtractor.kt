package com.ytdownloader.app.core

import android.content.Intent

/**
 * Extracts a usable YouTube URL from a launch intent. NewPipeExtractor accepts raw
 * YouTube URLs of any form, so this only needs to *find* the URL, not normalize it.
 */
object IntentUrlExtractor {

    private val URL_REGEX = Regex(
        "(https?://)?(www\\.|m\\.)?(youtube\\.com/(watch\\?\\S*v=|shorts/|live/|embed/)|youtu\\.be/)[\\w-]{11}\\S*"
    )

    fun fromIntent(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { extract(it) }
            else -> null
        }
    }

    fun extract(text: String): String? {
        URL_REGEX.find(text)?.let { return it.value }
        return text.trim().takeIf { it.startsWith("http") }
    }
}
