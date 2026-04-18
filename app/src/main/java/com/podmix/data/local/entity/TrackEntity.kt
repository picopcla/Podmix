package com.podmix.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    foreignKeys = [ForeignKey(
        entity = EpisodeEntity::class,
        parentColumns = ["id"],
        childColumns = ["episodeId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("episodeId")]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val episodeId: Int,
    val position: Int = 0,
    val title: String,
    val artist: String,
    val startTimeSec: Float,
    val endTimeSec: Float? = null,
    val isFavorite: Boolean = false,
    val source: String? = null, // "youtube_description", "1001tracklists", "manual"
    val spotifyUrl: String? = null,
    val deezerUrl: String? = null
)
