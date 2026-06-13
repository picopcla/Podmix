package com.podmix.v2.domain.model

data class Podcast(
    val id: String,
    val title: String,
    val podcastName: String,
    val playableMedia: PlayableMedia?,
    val analysis: AnalysisSummary? = null
)

data class Liveset(
    val id: String,
    val title: String,
    val artistName: String,
    val playableMedia: PlayableMedia?,
    val analysis: AnalysisSummary? = null
)

data class Emission(
    val id: String,
    val title: String,
    val sourceName: String,
    val playableMedia: PlayableMedia?
)

data class Radio(
    val id: String,
    val title: String,
    val streamUrl: String
)

data class FavoriteContent(
    val contentId: String,
    val contentType: ContentType,
    val title: String,
    val subtitle: String
)

data class SavedTrack(
    val id: String,
    val sourceContentId: String,
    val sourceContentType: ContentType,
    val track: TrackReference,
    val spotifyUrl: String? = null,
    val deezerUrl: String? = null
)
