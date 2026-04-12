package com.podmix.service

import android.util.Log
import com.podmix.data.local.dao.EpisodeDao
import com.podmix.data.local.entity.EpisodeEntity
import com.podmix.data.repository.TrackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpisodeEnrichmentService @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val trackRepository: TrackRepository,
    private val artistPageScraper: ArtistPageScraper,
    private val youTubeStreamResolver: YouTubeStreamResolver
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val TAG = "EnrichmentService"

    /** Lance l'enrichissement audio + tracklist en arrière-plan. Non-bloquant. */
    fun enrich(episodeId: Int, djName: String) {
        scope.launch {
            try {
                val ep = episodeDao.getById(episodeId) ?: return@launch
                if (ep.enrichedAt != null) {
                    Log.d(TAG, "Already enriched: $episodeId, skipping")
                    return@launch
                }

                // Phase 1 : résoudre l'audio si manquant
                resolveAudio(ep, djName)

                // Phase 2 : détecter la tracklist (relit l'épisode pour avoir l'audio résolu)
                val latest = episodeDao.getById(episodeId) ?: return@launch
                trackRepository.detectAndSaveTracks(
                    episodeId = episodeId,
                    description = latest.description,
                    episodeTitle = latest.title,
                    podcastName = djName,
                    episodeDurationSec = latest.durationSeconds,
                    isLiveSet = true,
                    youtubeVideoId = latest.youtubeVideoId,
                    tracklistPageUrl = latest.tracklistPageUrl
                )

                // Marquer comme enrichi
                val final = episodeDao.getById(episodeId) ?: return@launch
                episodeDao.update(final.copy(enrichedAt = System.currentTimeMillis()))
                Log.i(TAG, "Enrichment complete for '${final.title}'")

            } catch (e: Exception) {
                Log.w(TAG, "Enrichment failed for $episodeId: ${e.message}")
            }
        }
    }

    private suspend fun resolveAudio(ep: EpisodeEntity, djName: String) {
        // Déjà une source audio → rien à faire
        if (!ep.soundcloudTrackUrl.isNullOrBlank()
            || !ep.youtubeVideoId.isNullOrBlank()
            || ep.mixcloudKey != null
            || ep.localAudioPath != null) return

        var scUrl: String? = null
        var ytId: String? = null
        var mcKey: String? = null

        // 1. Probe page 1001TL si URL connue
        if (!ep.tracklistPageUrl.isNullOrBlank()) {
            try {
                val src = artistPageScraper.getMediaSourceFromTracklistPage(ep.tracklistPageUrl)
                scUrl = src?.soundcloudTrackUrl
                ytId = src?.youtubeVideoId
                mcKey = src?.mixcloudKey
                Log.d(TAG, "1001TL probe '${ep.title}': SC=$scUrl YT=$ytId MC=$mcKey")
            } catch (e: Exception) {
                Log.w(TAG, "1001TL probe failed: ${e.message}")
            }
        }

        // 2. DDG SoundCloud
        if (scUrl == null && ytId == null && mcKey == null) {
            try {
                scUrl = youTubeStreamResolver.searchFirstSoundCloudUrl("$djName ${ep.title}")
                Log.d(TAG, "DDG SC '${ep.title}': $scUrl")
            } catch (e: Exception) {
                Log.w(TAG, "DDG SC failed: ${e.message}")
            }
        }

        // 3. YouTube en dernier recours
        if (scUrl == null && ytId == null && mcKey == null) {
            try {
                ytId = youTubeStreamResolver.searchFirstVideoId("$djName ${ep.title}")
                Log.d(TAG, "YT fallback '${ep.title}': $ytId")
            } catch (e: Exception) {
                Log.w(TAG, "YT search failed: ${e.message}")
            }
        }

        if (scUrl != null || ytId != null || mcKey != null) {
            episodeDao.update(ep.copy(
                soundcloudTrackUrl = scUrl ?: ep.soundcloudTrackUrl,
                youtubeVideoId = ytId ?: ep.youtubeVideoId,
                mixcloudKey = mcKey ?: ep.mixcloudKey
            ))
            Log.i(TAG, "Audio resolved for '${ep.title}': SC=$scUrl YT=$ytId MC=$mcKey")
        } else {
            Log.w(TAG, "No audio source found for '${ep.title}'")
        }
    }
}
