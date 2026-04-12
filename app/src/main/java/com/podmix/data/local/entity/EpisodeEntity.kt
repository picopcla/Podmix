package com.podmix.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "episodes",
    foreignKeys = [ForeignKey(
        entity = PodcastEntity::class,
        parentColumns = ["id"],
        childColumns = ["podcastId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("podcastId")]
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val podcastId: Int,
    val title: String,
    val audioUrl: String,
    val datePublished: Long? = null,
    val durationSeconds: Int = 0,
    val progressSeconds: Int = 0,
    val isListened: Boolean = false,
    val artworkUrl: String? = null,
    val episodeType: String = "podcast", // "podcast" or "liveset"
    val youtubeVideoId: String? = null,
    val guid: String? = null,
    val description: String? = null,
    val mixcloudKey: String? = null,
    val localAudioPath: String? = null,
    val soundcloudTrackUrl: String? = null,
    val tracklistPageUrl: String? = null,
    val enrichedAt: Long? = null
)
