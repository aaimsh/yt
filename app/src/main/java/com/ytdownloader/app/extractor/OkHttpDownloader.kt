package com.ytdownloader.app.extractor

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

/**
 * NewPipeExtractor [Downloader] backed by OkHttp. Used by the extractor for metadata
 * requests only — NewPipe buffers the whole response body into a String here, so the
 * multi-megabyte media download takes a separate raw-streaming path
 * (see [com.ytdownloader.app.download.DownloadRepository]).
 */
class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val url = request.url()
        val builder = okhttp3.Request.Builder().url(url)

        // NewPipe hands us a header map; flatten it into OkHttp.
        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (value in values) builder.addHeader(name, value)
        }

        val data: ByteArray? = request.dataToSend()
        val body = data?.toRequestBody(null, 0, data.size)
        builder.method(request.httpMethod(), body)

        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            val responseBody = resp.body?.string().orEmpty()
            return Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                responseBody,
                resp.request.url.toString(),
            )
        }
    }
}
