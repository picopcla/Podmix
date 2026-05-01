package com.podmix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.podmix.data.local.entity.EpisodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY datePublished DESC")
    fun getByPodcastId(podcastId: Int): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY datePublished DESC LIMIT :limit")
    fun getByPodcastIdLimited(podcastId: Int, limit: Int): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY datePublished DESC")
    suspend fun getByPodcastIdSuspend(podcastId: Int): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getById(id: Int): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE guid = :guid AND podcastId = :podcastId LIMIT 1")
    suspend fun getByGuid(guid: String, podcastId: Int): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE youtubeVideoId = :videoId AND podcastId = :podcastId LIMIT 1")
    suspend fun getByYoutubeVideoId(videoId: String, podcastId: Int): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE title = :title AND podcastId = :podcastId LIMIT 1")
    suspend fun getByTitleAndPodcastId(title: String, podcastId: Int): EpisodeEntity?

    @Query("DELETE FROM episodes WHERE id NOT IN (SELECT MIN(id) FROM episodes GROUP BY title, podcastId)")
    suspend fun deleteDuplicatesByTitle()

    @Query("SELECT * FROM episodes WHERE mixcloudKey = :key AND podcastId = :podcastId LIMIT 1")
    suspend fun getByMixcloudKey(key: String, podcastId: Int): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(episode: EpisodeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(episodes: List<EpisodeEntity>)

    @Update
    suspend fun update(episode: EpisodeEntity)

    @Query("DELETE FROM episodes WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("UPDATE episodes SET progressSeconds = :seconds WHERE id = :id")
    suspend fun updateProgress(id: Int, seconds: Int)

    @Query("UPDATE episodes SET isListened = NOT isListened WHERE id = :id")
    suspend fun toggleListened(id: Int)

    @Query("UPDATE episodes SET localAudioPath = :path WHERE id = :id")
    suspend fun updateLocalAudioPath(id: Int, path: String)

    @Query("UPDATE episodes SET localAudioPath = NULL WHERE id = :id")
    suspend fun clearLocalAudioPath(id: Int)

    @Query("UPDATE episodes SET trackRefinementStatus = :status WHERE id = :id")
    suspend fun updateRefinementStatus(id: Int, status: String)

    @Query("SELECT * FROM episodes WHERE trackRefinementStatus = 'pending' AND localAudioPath IS NOT NULL")
    suspend fun getPendingRefinementWithLocalAudio(): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE trackRefinementStatus = 'chroma_pending' AND localAudioPath IS NOT NULL")
    suspend fun getChromaPendingWithLocalAudio(): List<EpisodeEntity>

    @Query("UPDATE episodes SET isFavorite = NOT isFavorite WHERE id = :id")
    suspend fun toggleEpisodeFavorite(id: Int)

    @Query("SELECT * FROM episodes WHERE isFavorite = 1 ORDER BY datePublished DESC")
    fun getFavoriteEpisodes(): Flow<List<EpisodeEntity>>

    @Query("SELECT COUNT(*) FROM episodes WHERE podcastId = :podcastId")
    suspend fun countByPodcast(podcastId: Int): Int

    /**
     * Supprime les épisodes les plus anciens d'un podcast au-delà de [keepCount].
     * Protège : isFavorite=1, isListened=0 (pas encore écouté), localAudioPath non null.
     */
    @Query("""
        DELETE FROM episodes
        WHERE podcastId = :podcastId
          AND isFavorite = 0
          AND isListened = 1
          AND localAudioPath IS NULL
          AND id NOT IN (
            SELECT id FROM episodes
            WHERE podcastId = :podcastId
            ORDER BY datePublished DESC
            LIMIT :keepCount
          )
    """)
    suspend fun deleteOldestListened(podcastId: Int, keepCount: Int)
}
