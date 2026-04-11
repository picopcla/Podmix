package com.podmix.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class RadioStation(
    @SerializedName("stationuuid") val uuid: String,
    @SerializedName("name") val name: String,
    @SerializedName("url_resolved") val urlResolved: String?,
    @SerializedName("url") val url: String,
    @SerializedName("favicon") val favicon: String?,
    @SerializedName("country") val country: String?,
    @SerializedName("tags") val tags: String?,
    @SerializedName("codec") val codec: String?,
    @SerializedName("bitrate") val bitrate: Int?
)

interface RadioBrowserApi {
    @GET("json/stations/byname/{name}")
    suspend fun searchByName(
        @Path("name") name: String,
        @Query("limit") limit: Int = 20,
        @Query("order") order: String = "clickcount",
        @Query("reverse") reverse: Boolean = true
    ): List<RadioStation>
}
