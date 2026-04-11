package com.podmix.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

data class TracklistResponse(
    @SerializedName("tracks") val tracks: List<TracklistTrack>?,
    @SerializedName("source") val source: String?,
    @SerializedName("url") val url: String?,
    @SerializedName("error") val error: String?
)

data class TracklistTrack(
    @SerializedName("position") val position: Int,
    @SerializedName("artist") val artist: String,
    @SerializedName("title") val title: String,
    @SerializedName("start_time_sec") val startTimeSec: Int
)

interface TracklistApi {
    @GET("tracklist")
    suspend fun getTracklist(@Query("q") query: String): TracklistResponse

    @GET("analyze")
    suspend fun analyzeByUrl(@Query("url") url: String): TracklistResponse
}
