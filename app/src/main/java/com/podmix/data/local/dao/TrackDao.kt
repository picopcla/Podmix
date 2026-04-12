package com.podmix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.podmix.data.local.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

data class FavoriteWithInfo(
    val id: Int,
    val episodeId: Int,
    val title: String,
    val artist: String,
    val startTimeSec: Float,
    val endTimeSec: Float?,
    val podcastId: Int,
    val podcastName: String,
    val podcastLogoUrl: String?,
    val episodeTitle: String,
    val spotifyUrl: String? = null,
    val podcastType: String = "podcast"
)

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks WHERE episodeId = :episodeId ORDER BY position")
    suspend fun getByEpisodeId(episodeId: Int): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE episodeId = :episodeId ORDER BY position")
    fun getByEpisodeIdFlow(episodeId: Int): Flow<List<TrackEntity>>

    @Query("SELECT DISTINCT episodeId FROM tracks")
    fun getEpisodeIdsWithTracks(): Flow<List<Int>>

    @Query("SELECT DISTINCT episodeId FROM tracks")
    suspend fun getEpisodeIdsWithTracksSuspend(): List<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET isFavorite = NOT isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: Int)

    @Query("UPDATE tracks SET spotifyUrl = :url WHERE id = :id")
    suspend fun updateSpotifyUrl(id: Int, url: String?)

    @Query("DELETE FROM tracks WHERE episodeId = :episodeId")
    suspend fun deleteByEpisode(episodeId: Int)

    @Query("""
        SELECT t.id, t.episodeId, t.title, t.artist, t.startTimeSec, t.endTimeSec,
               p.id AS podcastId, p.name AS podcastName, p.logoUrl AS podcastLogoUrl,
               e.title AS episodeTitle, t.spotifyUrl, p.type AS podcastType
        FROM tracks t
        INNER JOIN episodes e ON t.episodeId = e.id
        INNER JOIN podcasts p ON e.podcastId = p.id
        WHERE t.isFavorite = 1
        ORDER BY p.name, e.title, t.position
    """)
    fun getAllFavorites(): Flow<List<FavoriteWithInfo>>

    @Query("""
        SELECT t.id, t.episodeId, t.title, t.artist, t.startTimeSec, t.endTimeSec,
               p.id AS podcastId, p.name AS podcastName, p.logoUrl AS podcastLogoUrl,
               e.title AS episodeTitle, t.spotifyUrl, p.type AS podcastType
        FROM tracks t
        INNER JOIN episodes e ON t.episodeId = e.id
        INNER JOIN podcasts p ON e.podcastId = p.id
        WHERE t.isFavorite = 1
        ORDER BY p.name, e.title, t.position
    """)
    suspend fun getAllFavoritesSuspend(): List<FavoriteWithInfo>
}
