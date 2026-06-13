package com.podmix.v2.domain.model

data class PlayableMedia(
    val mediaId: String,
    val title: String,
    val streamUrl: String,
    val sourceType: PlaybackSourceType,
    val startPositionMs: Long = 0L
)
