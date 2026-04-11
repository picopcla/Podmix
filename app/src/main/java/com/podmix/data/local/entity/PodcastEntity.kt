package com.podmix.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val logoUrl: String? = null,
    val description: String? = null,
    val rssFeedUrl: String? = null,
    val type: String = "podcast", // "podcast" or "dj"
    val active: Boolean = true,
    val lastCheckedAt: Long? = null
)
