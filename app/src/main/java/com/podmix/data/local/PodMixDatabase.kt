package com.podmix.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.dao.PodcastDao
import com.podmix.data.local.dao.TrackDao
import com.podmix.data.local.entity.EpisodeEntity
import com.podmix.data.local.entity.PodcastEntity
import com.podmix.data.local.entity.TrackEntity

@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, TrackEntity::class],
    version = 8,
    exportSchema = false
)
abstract class PodMixDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun trackDao(): TrackDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE episodes ADD COLUMN localAudioPath TEXT")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE episodes ADD COLUMN soundcloudTrackUrl TEXT")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE episodes ADD COLUMN tracklistPageUrl TEXT")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE episodes ADD COLUMN enrichedAt INTEGER")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Remove duplicate tracks — keep only the row with the smallest id per (episodeId, position)
                database.execSQL("""
                    DELETE FROM tracks WHERE id NOT IN (
                        SELECT MIN(id) FROM tracks GROUP BY episodeId, position
                    )
                """.trimIndent())
            }
        }
    }
}
