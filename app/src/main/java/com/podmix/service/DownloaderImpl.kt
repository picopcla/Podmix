package com.podmix.service

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException

class DownloaderImpl private constructor() : Downloader() {

    companion object {
        private val instance = DownloaderImpl()
        fun getInstance(): DownloaderImpl = instance
    }

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val requestBuilder = okhttp3.Request.Builder()
            .url(request.url())
            .method(
                request.httpMethod(),
                request.dataToSend()?.let {
                    okhttp3.RequestBody.create(null, it)
                }
            )

        for ((key, values) in request.headers()) {
            for (value in values) {
                requestBuilder.addHeader(key, value)
            }
        }

        // Add default User-Agent if not set
        if (request.headers()["User-Agent"] == null) {
            requestBuilder.header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; rv:91.0) Gecko/20100101 Firefox/91.0"
            )
        }

        val response = client.newCall(requestBuilder.build()).execute()
        val body = response.body?.string() ?: ""

        val responseHeaders = mutableMapOf<String, List<String>>()
        for (name in response.headers.names()) {
            responseHeaders[name] = response.headers.values(name)
        }

        return Response(
            response.code,
            response.message,
            responseHeaders,
            body,
            response.request.url.toString()
        )
    }
}
