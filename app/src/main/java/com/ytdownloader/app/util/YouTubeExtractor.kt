package com.ytdownloader.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Pure Kotlin YouTube video extractor.
 * Parses YouTube pages to extract video metadata and streaming URLs.
 */
object YouTubeExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * Extract the video ID from various YouTube URL formats.
     */
    fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Pattern.compile("(?:v=|/v/|youtu\\.be/|/embed/|/shorts/)([a-zA-Z0-9_-]{11})"),
            Pattern.compile("^([a-zA-Z0-9_-]{11})$"),
        )
        for (p in patterns) {
            val m = p.matcher(url)
            if (m.find()) return m.group(1)
        }
        return null
    }

    /**
     * Fetch video info using YouTube's innertube API (android client).
     */
    suspend fun fetchVideoInfo(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            val videoId = extractVideoId(url)
                ?: return@withContext Result.failure(Exception("Invalid YouTube URL"))

            val playerResponse = fetchPlayerResponse(videoId)
            parsePlayerResponse(playerResponse, videoId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Call YouTube's innertube /player endpoint with the android client.
     * The android client often returns direct URLs without needing signature deciphering.
     */
    private fun fetchPlayerResponse(videoId: String): JSONObject {
        // Try android client first (usually gives direct URLs)
        val body = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID")
                    put("clientVersion", "19.09.37")
                    put("androidSdkVersion", 33)
                    put("hl", "en")
                    put("gl", "US")
                    put("userAgent", USER_AGENT)
                })
            })
        }

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", "3")
            .header("X-Youtube-Client-Version", "19.09.37")
            .post(okhttp3.RequestBody.create(
                okhttp3.MediaType.parse("application/json"),
                body.toString()
            ))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body()?.string()
            ?: throw Exception("Empty response from YouTube")

        if (!response.isSuccessful) {
            throw Exception("YouTube API error: ${response.code()}")
        }

        val json = JSONObject(responseBody)

        // Check playability
        val playability = json.optJSONObject("playabilityStatus")
        val status = playability?.optString("status", "")
        if (status == "ERROR" || status == "LOGIN_REQUIRED" || status == "UNPLAYABLE") {
            val reason = playability?.optString("reason", "Video unavailable")
            throw Exception(reason)
        }

        return json
    }

    /**
     * Parse the innertube player response into our VideoInfo model.
     */
    private fun parsePlayerResponse(json: JSONObject, videoId: String): Result<VideoInfo> {
        val videoDetails = json.optJSONObject("videoDetails")
            ?: return Result.failure(Exception("No video details found"))

        val title = videoDetails.optString("title", "Unknown")
        val uploader = videoDetails.optString("author", null)
        val duration = videoDetails.optString("lengthSeconds", "0").toLongOrNull()
        val thumbnail = videoDetails.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { thumbs ->
                if (thumbs.length() > 0) {
                    thumbs.getJSONObject(thumbs.length() - 1).optString("url")
                } else null
            }

        val streamingData = json.optJSONObject("streamingData")
            ?: return Result.failure(Exception("No streaming data - video may be restricted"))

        val formats = mutableListOf<VideoFormat>()

        // Parse regular formats (have both video + audio)
        streamingData.optJSONArray("formats")?.let { arr ->
            parseFormats(arr, formats)
        }

        // Parse adaptive formats (video-only or audio-only)
        streamingData.optJSONArray("adaptiveFormats")?.let { arr ->
            parseFormats(arr, formats)
        }

        if (formats.isEmpty()) {
            return Result.failure(Exception("No downloadable formats found"))
        }

        // Sort: combined first, then by resolution descending
        formats.sortWith(compareBy<VideoFormat> {
            if (it.hasVideo && it.hasAudio) 0 else 1
        }.thenByDescending {
            it.resolution.replace("p", "").replace("audio.*".toRegex(), "0").toIntOrNull() ?: 0
        })

        return Result.success(
            VideoInfo(
                title = title,
                thumbnail = thumbnail,
                duration = duration,
                uploader = uploader,
                formats = formats,
            )
        )
    }

    /**
     * Parse a JSON array of format objects into VideoFormat list.
     */
    private fun parseFormats(arr: JSONArray, out: MutableList<VideoFormat>) {
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)

            val url = f.optString("url", "")
            // Skip formats without direct URL (would need signature deciphering)
            if (url.isBlank()) continue

            val mimeType = f.optString("mimeType", "")
            val hasVideo = mimeType.startsWith("video/")
            val hasAudio = mimeType.startsWith("audio/") ||
                    mimeType.contains("audio")

            val height = f.optInt("height", 0)
            val width = f.optInt("width", 0)
            val bitrate = f.optInt("bitrate", 0)
            val contentLength = f.optString("contentLength", "").toLongOrNull()
            val qualityLabel = f.optString("qualityLabel", "")
            val audioQuality = f.optString("audioQuality", "")

            val resolution = when {
                hasVideo && height > 0 -> "${height}p"
                !hasVideo && hasAudio -> {
                    val abr = bitrate / 1000
                    "audio ${abr}kbps"
                }
                qualityLabel.isNotBlank() -> qualityLabel
                else -> "unknown"
            }

            val ext = when {
                mimeType.contains("mp4") -> "mp4"
                mimeType.contains("webm") -> "webm"
                mimeType.contains("3gpp") -> "3gp"
                mimeType.contains("opus") -> "webm"
                mimeType.contains("mp4a") -> "m4a"
                else -> "mp4"
            }

            val descParts = mutableListOf(resolution, ext)
            if (hasVideo && hasAudio) descParts.add("(video+audio)")
            else if (hasVideo) descParts.add("(video only)")
            else if (hasAudio) descParts.add("(audio only)")

            out.add(
                VideoFormat(
                    formatId = f.optString("itag", i.toString()),
                    extension = ext,
                    resolution = resolution,
                    fileSize = contentLength,
                    description = descParts.joinToString(" | "),
                    hasVideo = hasVideo && height > 0,
                    hasAudio = hasAudio || audioQuality.isNotBlank(),
                    url = url,
                )
            )
        }
    }

    /**
     * Find the best format matching a preset.
     */
    fun selectFormat(formats: List<VideoFormat>, presetId: String): VideoFormat? {
        return when (presetId) {
            "best_video" -> {
                // Prefer combined video+audio, highest resolution
                formats.filter { it.hasVideo && it.hasAudio }
                    .maxByOrNull { it.heightValue }
                    ?: formats.filter { it.hasVideo }.maxByOrNull { it.heightValue }
            }
            "1080p" -> findVideoAtOrBelow(formats, 1080)
            "720p" -> findVideoAtOrBelow(formats, 720)
            "480p" -> findVideoAtOrBelow(formats, 480)
            "audio_only" -> {
                formats.filter { it.hasAudio && !it.hasVideo }
                    .maxByOrNull { it.fileSize ?: 0 }
                    ?: formats.filter { it.hasAudio }.firstOrNull()
            }
            else -> formats.firstOrNull()
        }
    }

    private fun findVideoAtOrBelow(formats: List<VideoFormat>, maxHeight: Int): VideoFormat? {
        // Prefer combined formats at or below the target height
        return formats.filter { it.hasVideo && it.hasAudio && it.heightValue <= maxHeight }
            .maxByOrNull { it.heightValue }
            ?: formats.filter { it.hasVideo && it.heightValue <= maxHeight }
                .maxByOrNull { it.heightValue }
            ?: formats.filter { it.hasVideo && it.hasAudio }
                .minByOrNull { it.heightValue }
    }
}

/** Parse the numeric height from a resolution string like "720p" */
val VideoFormat.heightValue: Int
    get() = resolution.replace("p", "").toIntOrNull() ?: 0
