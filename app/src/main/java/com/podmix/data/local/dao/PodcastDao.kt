package com.podmix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.podmix.data.local.entity.PodcastEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {

    @Query("SELECT * FROM podcasts WHERE type = :type ORDER BY name")
    fun getByType(type: String): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcasts WHERE type = :type ORDER BY name")
    suspend fun getByTypeSuspend(type: String): List<PodcastEntity>

    @Query("SELECT * FROM podcasts ORDER BY name")
    fun getAll(): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun getById(id: Int): PodcastEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(podcast: PodcastEntity): Long

    @Update
    suspend fun update(podcast: PodcastEntity)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("SELECT COUNT(*) FROM episodes WHERE podcastId = :podcastId")
    suspend fun episodeCount(podcastId: Int): Int
}
