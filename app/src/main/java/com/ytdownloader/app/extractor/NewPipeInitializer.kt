package com.ytdownloader.app.extractor

import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.concurrent.TimeUnit

/**
 * Idempotent NewPipe bootstrap. [init] only stores statics + builds an OkHttp client,
 * so it is cheap and safe to call on the main thread; the expensive network/JS-decipher
 * work happens later in [org.schabi.newpipe.extractor.stream.StreamInfo.getInfo].
 */
object NewPipeInitializer {

    @Volatile
    private var initialized = false

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    @Synchronized
    fun init() {
        if (initialized) return
        NewPipe.init(
            OkHttpDownloader(httpClient),
            Localization("en", "US"),
            ContentCountry("US"),
        )
        initialized = true
    }
}
