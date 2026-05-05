package com.podmix.ui.screens.cast

import android.app.Activity
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.cast.CastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.podmix.service.MixcloudStreamResolver
import com.podmix.service.PlayerController
import com.podmix.service.YouTubeStreamResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class CastViewModel @Inject constructor(
    private val playerController: PlayerController,
    private val youTubeStreamResolver: YouTubeStreamResolver,
    private val mixcloudStreamResolver: MixcloudStreamResolver
) : ViewModel() {

    val playerState = playerController.playerState

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName

    private var castContext: CastContext? = null
    private var castPlayer: CastPlayer? = null

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.i("CastVM", "Session started: ${session.castDevice?.friendlyName}")
            _connectedDeviceName.value = session.castDevice?.friendlyName ?: "Chromecast"
            castCurrentEpisode()
        }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            // Ne PAS auto-caster au resume : l'URL audio peut être expirée (YT ~6h).
            // L'user devra appuyer sur Cast manuellement pour rejouer.
            Log.i("CastVM", "Session resumed — pas d'auto-cast (URL possiblement expirée)")
            _connectedDeviceName.value = session.castDevice?.friendlyName ?: "Chromecast"
            // Terminer la session proprement pour éviter le "Getting your selection" bloquant
            try {
                session.remoteMediaClient?.stop()
            } catch (_: Exception) {}
        }
        override fun onSessionEnded(session: CastSession, error: Int) {
            Log.i("CastVM", "Session ended: $error")
            _connectedDeviceName.value = null
            castPlayer?.release()
            castPlayer = null
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.e("CastVM", "Session start failed: $error")
        }
        override fun onSessionStarting(session: CastSession) {
            Log.i("CastVM", "Session starting...")
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
    }

    fun initCast(activity: Activity) {
        if (castContext != null) return
        try {
            castContext = CastContext.getSharedInstance(activity)
            castContext?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
            Log.i("CastVM", "CastContext initialized from Activity")
        } catch (e: Exception) {
            Log.e("CastVM", "CastContext init failed: ${e.message}")
        }
    }

    fun openCastDialog(activity: Activity) {
        try {
            val cc = castContext ?: CastContext.getSharedInstance(activity).also { castContext = it }
            // This opens the native Cast dialog
            cc.sessionManager?.let { sm ->
                val currentSession = sm.currentCastSession
                if (currentSession != null && currentSession.isConnected) {
                    // Already connected — disconnect
                    sm.endCurrentSession(true)
                    _connectedDeviceName.value = null
                } else {
                    // Open native Cast chooser dialog
                    val selector = androidx.mediarouter.media.MediaRouteSelector.Builder()
                        .addControlCategory(com.google.android.gms.cast.CastMediaControlIntent.categoryForCast("CC1AD845"))
                        .build()
                    val dialog = androidx.mediarouter.app.MediaRouteChooserDialog(activity)
                    dialog.routeSelector = selector
                    dialog.show()
                }
            }
        } catch (e: Exception) {
            Log.e("CastVM", "openCastDialog failed: ${e.message}")
        }
    }

    private fun castCurrentEpisode() {
        val episode = playerController.playerState.value.currentEpisode ?: return
        val podcast = playerController.playerState.value.currentPodcast

        viewModelScope.launch {
            // Timeout 15s : si la résolution URL échoue (expirée, réseau absent), on abandonne
            val audioUrl = withTimeoutOrNull(15_000) {
                when {
                    episode.mixcloudKey != null -> mixcloudStreamResolver.resolve(episode.mixcloudKey)
                    !episode.youtubeVideoId.isNullOrBlank() -> youTubeStreamResolver.resolve(episode.youtubeVideoId)
                    !episode.soundcloudTrackUrl.isNullOrBlank() -> youTubeStreamResolver.resolveSoundCloudTrack(episode.soundcloudTrackUrl)
                    episode.audioUrl.isNotBlank() -> episode.audioUrl
                    else -> null
                }
            }

            if (audioUrl == null) {
                Log.e("CastVM", "Cannot resolve audio for cast (timeout ou aucune source)")
                // Stopper le receiver pour éviter "Getting your selection" bloquant
                try {
                    castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.stop()
                } catch (_: Exception) {}
                return@launch
            }

            try {
                val cc = castContext ?: return@launch
                castPlayer = CastPlayer(cc).apply {
                    val mimeType = when {
                        audioUrl.contains("googlevideo.com", true) -> "audio/mp4"   // YouTube progressive (AAC/m4a)
                        audioUrl.contains("soundcloud", true) && audioUrl.contains(".m3u8", true) -> "application/x-mpegURL"
                        audioUrl.contains(".mp3", true) -> "audio/mpeg"
                        audioUrl.contains(".m4a", true) -> "audio/mp4"
                        audioUrl.contains(".ogg", true) -> "audio/ogg"
                        audioUrl.contains(".opus", true) -> "audio/ogg"
                        audioUrl.contains(".m3u8", true) -> "application/x-mpegURL"
                        else -> "audio/mp4"
                    }
                    val mediaItem = MediaItem.Builder()
                        .setUri(audioUrl)
                        .setMimeType(mimeType)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(episode.title)
                                .setArtist(podcast?.name)
                                .setArtworkUri(episode.artworkUrl?.let { Uri.parse(it) }
                                    ?: podcast?.logoUrl?.let { Uri.parse(it) })
                                .build()
                        )
                        .build()
                    setMediaItem(mediaItem)
                    prepare()
                    play()
                }
                playerController.pause()
                Log.i("CastVM", "Casting: ${episode.title}")
            } catch (e: Exception) {
                Log.e("CastVM", "Cast playback failed: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
            castPlayer?.release()
        } catch (_: Exception) {}
    }
}
