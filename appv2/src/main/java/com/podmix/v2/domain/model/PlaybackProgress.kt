package com.podmix.v2.domain.model

data class PlaybackProgress(
    val mediaId: String,
    val positionMs: Long,
    val durationMs: Long
)
