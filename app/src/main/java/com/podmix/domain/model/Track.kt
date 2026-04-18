package com.podmix.domain.model

data class Track(
    val id: Int,
    val episodeId: Int,
    val position: Int,
    val title: String,
    val artist: String,
    val startTimeSec: Float,
    val endTimeSec: Float?,
    val isFavorite: Boolean,
    val spotifyUrl: String? = null,
    val deezerUrl: String? = null,
    val source: String = ""
)
