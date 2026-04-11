package com.podmix.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class MixcloudSearchResponse(
    @SerializedName("data") val data: List<MixcloudCloudcast>?
)

data class MixcloudCloudcast(
    @SerializedName("key") val key: String,
    @SerializedName("name") val name: String,
    @SerializedName("slug") val slug: String?,
    @SerializedName("user") val user: MixcloudUser?,
    @SerializedName("audio_length") val audioLength: Int? = null,
    @SerializedName("created_time") val createdTime: String? = null,
    @SerializedName("pictures") val pictures: MixcloudPictures? = null
)

data class MixcloudPictures(
    @SerializedName("extra_large") val extraLarge: String? = null,
    @SerializedName("large") val large: String? = null,
    @SerializedName("640wx640h") val w640: String? = null
)

data class MixcloudUser(
    @SerializedName("username") val username: String
)

data class MixcloudSectionsResponse(
    @SerializedName("data") val data: List<MixcloudSection>?
)

data class MixcloudSection(
    @SerializedName("start_time") val startTime: Int,
    @SerializedName("track") val track: MixcloudTrack?,
    @SerializedName("artist_name") val artistName: String?,
    @SerializedName("song_name") val songName: String?
)

data class MixcloudTrack(
    @SerializedName("name") val name: String?,
    @SerializedName("artist") val artist: MixcloudArtist?
)

data class MixcloudArtist(
    @SerializedName("name") val name: String?
)

interface MixcloudApi {
    @GET("search/")
    suspend fun search(
        @Query("q") query: String,
        @Query("type") type: String = "cloudcast"
    ): MixcloudSearchResponse

    @GET("{key}sections/")
    suspend fun getSections(@Path("key", encoded = true) key: String): MixcloudSectionsResponse
}
