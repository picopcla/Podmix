package com.podmix.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.dao.PodcastDao
import com.podmix.data.local.dao.TrackDao
import com.podmix.data.local.entity.EpisodeEntity
import com.podmix.data.local.entity.PodcastEntity
import com.podmix.data.local.entity.TrackEntity

@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, TrackEntity::class],
    version = 3,
    exportSchema = false
)
abstract class PodMixDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun trackDao(): TrackDao
}
