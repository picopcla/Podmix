package com.podmix.domain.model

data class Podcast(
    val id: Int,
    val name: String,
    val logoUrl: String?,
    val description: String?,
    val rssFeedUrl: String?,
    val type: String, // "podcast" or "dj"
    val episodeCount: Int
)
