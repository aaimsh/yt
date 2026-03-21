package com.ytdownloader.app.util

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin wrapper around the Python yt-dlp bridge module.
 */
object YtDlpWrapper {

    private fun getBridge(): PyObject {
        val py = Python.getInstance()
        return py.getModule("ytdl_bridge")
    }

    suspend fun fetchVideoInfo(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            val bridge = getBridge()
            val resultJson = bridge.callAttr("get_video_info", url).toString()
            val json = JSONObject(resultJson)

            if (json.has("error")) {
                return@withContext Result.failure(Exception(json.getString("error")))
            }

            val formatsArray = json.getJSONArray("formats")
            val formats = mutableListOf<VideoFormat>()
            for (i in 0 until formatsArray.length()) {
                val f = formatsArray.getJSONObject(i)
                formats.add(
                    VideoFormat(
                        formatId = f.getString("format_id"),
                        extension = f.getString("extension"),
                        resolution = f.getString("resolution"),
                        fileSize = if (f.isNull("file_size")) null else f.getLong("file_size"),
                        description = f.getString("description"),
                        hasVideo = f.getBoolean("has_video"),
                        hasAudio = f.getBoolean("has_audio"),
                    )
                )
            }

            val info = VideoInfo(
                title = json.getString("title"),
                thumbnail = json.optString("thumbnail", null),
                duration = if (json.isNull("duration")) null else json.getLong("duration"),
                uploader = json.optString("uploader", null),
                formats = formats,
            )
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class PresetFormat(
        val id: String,
        val label: String,
        val description: String,
        val icon: String,
    )

    fun getPresetFormats(): List<PresetFormat> {
        return listOf(
            PresetFormat("best_video", "Best Quality", "Highest available resolution", "high_quality"),
            PresetFormat("1080p", "1080p Full HD", "1920x1080 resolution", "hd"),
            PresetFormat("720p", "720p HD", "1280x720 resolution", "hd"),
            PresetFormat("480p", "480p SD", "Standard definition, smaller file", "sd"),
            PresetFormat("audio_only", "Audio Only (MP3)", "Extract audio as MP3", "music_note"),
        )
    }

    suspend fun downloadWithPreset(
        url: String,
        outputDir: String,
        presetId: String,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bridge = getBridge()

            // Create a Chaquopy-compatible callback using PyProxy
            val callback = ProgressCallback(onProgress)

            val resultJson = bridge.callAttr(
                "download_with_preset",
                url,
                outputDir,
                presetId,
                PyObject.fromJava(callback),
            ).toString()

            val json = JSONObject(resultJson)
            if (json.getBoolean("success")) {
                Result.success(json.getString("filename"))
            } else {
                Result.failure(Exception(json.getString("error")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Progress callback that can be called from Python.
 * Chaquopy allows Python to call methods on Java/Kotlin objects passed to it.
 */
class ProgressCallback(
    private val onProgress: (DownloadProgress) -> Unit
) {
    /**
     * Called from Python with a JSON string containing progress info.
     * Python calls this as: callback(json_string)
     * Chaquopy maps __call__ to invoke().
     */
    fun __call__(progressJson: String) {
        try {
            val json = JSONObject(progressJson)
            val status = json.getString("status")
            val percent = json.getDouble("percent").toFloat()
            val speed = json.optString("speed", "")
            val eta = json.optString("eta", "")

            val state = when (status) {
                "downloading" -> DownloadState.DOWNLOADING
                "merging" -> DownloadState.MERGING
                else -> DownloadState.DOWNLOADING
            }

            onProgress(
                DownloadProgress(
                    state = state,
                    progress = percent / 100f,
                    speedText = speed,
                    etaText = eta,
                )
            )
        } catch (_: Exception) { }
    }
}
