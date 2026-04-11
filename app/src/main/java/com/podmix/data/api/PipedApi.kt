package com.podmix.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class PipedSearchResponse(
    @SerializedName("items") val items: List<PipedVideoItem>
)

data class PipedVideoItem(
    @SerializedName("url") val url: String, // "/watch?v=xxx"
    @SerializedName("title") val title: String,
    @SerializedName("thumbnail") val thumbnail: String?,
    @SerializedName("duration") val duration: Long, // seconds
    @SerializedName("uploaderName") val uploaderName: String?,
    @SerializedName("uploadedDate") val uploadedDate: String?
) {
    val videoId: String get() = url.substringAfter("v=").substringBefore("&")
}

data class PipedStreamResponse(
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String?,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String?,
    @SerializedName("uploaderName") val uploaderName: String?,
    @SerializedName("duration") val duration: Long,
    @SerializedName("audioStreams") val audioStreams: List<PipedAudioStream>
)

data class PipedAudioStream(
    @SerializedName("url") val url: String,
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("bitrate") val bitrate: Int,
    @SerializedName("quality") val quality: String?
)

interface PipedApi {
    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("filter") filter: String = "videos"
    ): PipedSearchResponse

    @GET("streams/{videoId}")
    suspend fun getStreams(@Path("videoId") videoId: String): PipedStreamResponse
}
