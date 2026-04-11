package com.podmix.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

data class ItunesResponse(
    @SerializedName("results") val results: List<ItunesPodcast>
)

data class ItunesPodcast(
    @SerializedName("collectionName") val name: String,
    @SerializedName("artworkUrl600") val artworkUrl: String?,
    @SerializedName("feedUrl") val feedUrl: String?,
    @SerializedName("artistName") val artistName: String?
)

data class ItunesMusicResponse(
    @SerializedName("results") val results: List<ItunesMusicTrack>
)

data class ItunesMusicTrack(
    @SerializedName("trackName") val trackName: String?,
    @SerializedName("artistName") val artistName: String?,
    @SerializedName("trackTimeMillis") val trackTimeMillis: Long?
)

interface ItunesApi {
    @GET("search")
    suspend fun searchPodcasts(
        @Query("term") term: String,
        @Query("media") media: String = "podcast",
        @Query("limit") limit: Int = 20
    ): ItunesResponse

    @GET("search")
    suspend fun searchMusic(
        @Query("term") term: String,
        @Query("media") media: String = "music",
        @Query("limit") limit: Int = 1
    ): ItunesMusicResponse
}
