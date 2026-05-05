package com.podmix.domain.model

data class Episode(
    val id: Int,
    val podcastId: Int,
    val title: String,
    val audioUrl: String,
    val datePublished: Long?,
    val durationSeconds: Int,
    val progressSeconds: Int,
    val isListened: Boolean,
    val artworkUrl: String?,
    val episodeType: String,
    val youtubeVideoId: String?,
    val description: String?,
    val mixcloudKey: String? = null,
    val localAudioPath: String? = null,
    val soundcloudTrackUrl: String? = null,
    val trackRefinementStatus: String = "none", // "none" | "pending" | "refining" | "done"
    val isFavorite: Boolean = false,
    /** Titre de la page 1001TL scrappée — ex: "Driftmoon @ Transmission Bangkok 2024 | 1001Tracklists" */
    val tracklistSourceName: String? = null
)
