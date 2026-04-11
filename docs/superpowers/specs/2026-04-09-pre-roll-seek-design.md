# Pre-Roll Seek — Design Spec
**Date :** 2026-04-09  
**Statut :** Approuvé  
**Scope :** PlayerController.kt + EpisodeDetailViewModel.kt

---

## Problème

Dans un DJ mix, les morceaux se superposent pendant 10 à 60 secondes (transition).
Quand l'utilisateur clique sur "Track 5", un `seekTo(track5.startTimeSec)` exact
atterrit au milieu d'une transition — l'entrée est musicalement brutale.

## Solution

Reculer automatiquement de `preRollMs` avant le timestamp marqué, selon le type
de contenu. L'utilisateur ne voit rien — la progression et le surlignage dans la
liste fonctionnent déjà par position (`syncTrackByTime`), pas par clic.

## Règle

```
seekMs = max(0, track.startTimeSec × 1000 - preRollMs)

preRollMs :
  "liveset"  → 30 000 ms  (transitions DJ longues, 30-60s)
  "podcast"  → 10 000 ms  (transitions courtes, 10-15s)
  seekbar    →      0 ms  (seek exact, pas de pre-roll)
```

## Changements de code

### 1. PlayerController.kt

Ajouter un paramètre `preRollMs` à la fonction publique `seekToTrack` :

```kotlin
fun seekToTrack(track: Track, episodeType: String) {
    val preRollMs = when (episodeType) {
        "liveset" -> 30_000L
        else      -> 10_000L   // podcast, emission, radio
    }
    val episode = _playerState.value.currentEpisode
    val durationMs = exoPlayer.duration
    val rawMs = (track.startTimeSec * 1000).toLong()
    val seekMs = (rawMs - preRollMs).coerceAtLeast(0L).coerceAtMost(durationMs)
    seekTo(seekMs)
}
```

`seekTo(ms)` existant (seekbar, loop) reste inchangé — pas de pre-roll.

### 2. EpisodeDetailViewModel.kt

Remplacer l'appel `seekTo` dans `seekToTrack()` par le nouveau `seekToTrack(track, episodeType)` :

```kotlin
fun seekToTrack(track: Track) {
    val ep = _episode.value ?: return
    val pod = _podcast.value ?: return
    val episodeType = pod.type ?: ep.episodeType

    if (playerController.playerState.value.currentEpisode?.id == ep.id) {
        playerController.seekToTrack(track, episodeType)
    } else {
        // Premier lancement : seekTo exact (pas encore en lecture)
        val rawMs = (track.startTimeSec * 1000).toLong()
        playerController.playEpisode(ep, pod, seekToMs = rawMs)
        playerController.updateTracks(tracks.value)
    }
}
```

> Note : le pre-roll ne s'applique que si l'épisode est déjà en lecture.
> Au premier lancement d'un épisode depuis un track, on démarre exact
> (l'utilisateur n'a pas encore entendu le début — le contexte manque).

### 3. UI — aucun changement

`syncTrackByTime()` dans `PlayerController` met à jour `currentTrackIndex`
en fonction de `exoPlayer.currentPosition`, pas du clic. Le surlignage
passe naturellement de Track 4 à Track 5 quand la position franchit
`track5.startTimeSec`. Transparent pour l'utilisateur.

## Cas limites

| Cas | Comportement |
|-----|-------------|
| Track 1 (pas de précédent) | `coerceAtLeast(0L)` → seek à 0s |
| Seekbar manuelle | `seekTo(ms)` direct, 0 pre-roll |
| Loop activé | `seekTo(loopMs)` direct, 0 pre-roll |
| Premier lancement depuis un track | seek exact (voir EpisodeDetailViewModel) |
| Épisode plus court que preRollMs | `coerceAtMost(durationMs)` protège |

## Ce qui n'est pas dans ce scope

- Configuration du pre-roll par l'utilisateur (YAGNI)
- Pre-roll sur les favoris en mode playlist (extension future possible)
- Indication visuelle du pre-roll (rejeté — transparent voulu)

## Fichiers modifiés

- `app/src/main/java/com/podmix/service/PlayerController.kt`
- `app/src/main/java/com/podmix/ui/screens/episode/EpisodeDetailViewModel.kt`
