package com.podmix.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

data class DuckDuckGoResponse(
    @SerializedName("Image") val image: String?
)

interface DuckDuckGoApi {
    @GET(".")
    suspend fun search(
        @Query("q") query: String,
        @Query("format") format: String = "json",
        @Query("skip_disambig") skipDisambig: Int = 1,
        @Query("no_html") noHtml: Int = 1
    ): DuckDuckGoResponse
}
