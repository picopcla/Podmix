package com.podmix.v2.domain.model

data class TrackReference(
    val artist: String,
    val title: String,
    val positionSeconds: Int? = null
)
