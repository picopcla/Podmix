# Pre-Roll Seek Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Quand l'utilisateur clique sur un track, reculer de 30s (liveset) ou 10s (podcast) avant le timestamp pour entendre la transition naturellement.

**Architecture:** Ajouter `seekToTrack(track, episodeType)` dans `PlayerController` qui calcule `seekMs = max(0, rawMs - preRollMs)`. `EpisodeDetailViewModel.seekToTrack()` appelle cette nouvelle fonction. Aucun changement UI — `syncTrackByTime()` met déjà à jour le surlignage par position.

**Tech Stack:** Kotlin, ExoPlayer media3, Hilt

---

### Task 1 : Ajouter `seekToTrack` dans PlayerController

**Files:**
- Modify: `app/src/main/java/com/podmix/service/PlayerController.kt`
- Test: `app/src/test/java/com/podmix/ExampleUnitTest.kt`

- [ ] **Step 1 : Écrire le test unitaire**

Dans `app/src/test/java/com/podmix/ExampleUnitTest.kt`, ajouter :

```kotlin
import org.junit.Test
import org.junit.Assert.*

class PreRollSeekTest {

    private fun computeSeekMs(startTimeSec: Float, episodeType: String, durationMs: Long = Long.MAX_VALUE): Long {
        val preRollMs = when (episodeType) {
            "liveset" -> 30_000L
            else      -> 10_000L
        }
        val rawMs = (startTimeSec * 1000).toLong()
        return (rawMs - preRollMs).coerceAtLeast(0L).coerceAtMost(durationMs)
    }

    @Test fun liveset_preroll_is_30s() {
        assertEquals(0L, computeSeekMs(10f, "liveset"))        // 10s - 30s = 0 (coerce)
        assertEquals(30_000L, computeSeekMs(60f, "liveset"))   // 60s - 30s = 30s
    }

    @Test fun podcast_preroll_is_10s() {
        assertEquals(0L, computeSeekMs(5f, "podcast"))         // 5s - 10s = 0 (coerce)
        assertEquals(50_000L, computeSeekMs(60f, "podcast"))   // 60s - 10s = 50s
    }

    @Test fun duration_cap() {
        assertEquals(100L, computeSeekMs(60f, "podcast", durationMs = 100L))
    }
}
```

- [ ] **Step 2 : Lancer le test (doit passer — logique pure)**

```bash
cd C:/APP/Podmix
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew test --tests "com.podmix.PreRollSeekTest" 2>&1 | tail -20
```
Attendu : `BUILD SUCCESSFUL`, 3 tests PASSED.

- [ ] **Step 3 : Ajouter `seekToTrack` dans PlayerController**

Dans `PlayerController.kt`, après la fonction `seekTo` (ligne ~222), ajouter :

```kotlin
fun seekToTrack(track: Track, episodeType: String) {
    val preRollMs = when (episodeType) {
        "liveset" -> 30_000L
        else      -> 10_000L
    }
    val rawMs = (track.startTimeSec * 1000).toLong()
    val durationMs = exoPlayer.duration.coerceAtLeast(0L)
    val seekMs = (rawMs - preRollMs).coerceAtLeast(0L)
        .let { if (durationMs > 0L) it.coerceAtMost(durationMs) else it }
    seekTo(seekMs)
}
```

- [ ] **Step 4 : Vérifier que le projet compile**

```bash
cd C:/APP/Podmix
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug 2>&1 | tail -10
```
Attendu : `BUILD SUCCESSFUL`.

- [ ] **Step 5 : Commit**

```bash
git add app/src/main/java/com/podmix/service/PlayerController.kt \
        app/src/test/java/com/podmix/ExampleUnitTest.kt
git commit -m "feat: add seekToTrack with pre-roll (30s liveset, 10s podcast)"
```

---

### Task 2 : Brancher `EpisodeDetailViewModel` sur `seekToTrack`

**Files:**
- Modify: `app/src/main/java/com/podmix/ui/screens/episode/EpisodeDetailViewModel.kt`

- [ ] **Step 1 : Remplacer `seekToTrack()` dans EpisodeDetailViewModel**

Localiser la fonction `seekToTrack` (autour de la ligne 136) et remplacer son corps :

```kotlin
fun seekToTrack(track: Track) {
    val ep = _episode.value ?: return
    val pod = _podcast.value ?: return
    val episodeType = pod.type.takeIf { !it.isNullOrBlank() } ?: ep.episodeType

    if (playerController.playerState.value.currentEpisode?.id == ep.id) {
        // Épisode déjà en lecture → pre-roll
        playerController.seekToTrack(track, episodeType)
    } else {
        // Premier lancement → seek exact (l'utilisateur n'a pas encore entendu le début)
        val rawMs = (track.startTimeSec * 1000).toLong()
        playerController.playEpisode(ep, pod, seekToMs = rawMs)
        playerController.updateTracks(tracks.value)
    }
}
```

- [ ] **Step 2 : Compiler**

```bash
cd C:/APP/Podmix
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug 2>&1 | tail -10
```
Attendu : `BUILD SUCCESSFUL`.

- [ ] **Step 3 : Installer sur le téléphone et tester manuellement**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew installDebug
```

Scénario 1 — Liveset en cours de lecture :
1. Lancer un liveset YouTube, attendre que la lecture démarre
2. Cliquer sur "Track 3" dans la liste
3. Vérifier que la position recule de ~30s avant le timestamp de Track 3
4. Vérifier que le surlignage reste sur Track 2 pendant ~30s, puis passe à Track 3

Scénario 2 — Podcast en cours de lecture :
1. Lancer un podcast RSS, attendre que la lecture démarre
2. Cliquer sur un track
3. Vérifier que la position recule de ~10s avant le timestamp

Scénario 3 — Track 1 :
1. Cliquer sur le premier track
2. Vérifier que le seek va à 0s (pas de valeur négative)

Scénario 4 — Premier lancement depuis un track :
1. Ne pas lancer l'épisode
2. Cliquer directement sur "Track 4"
3. Vérifier que la lecture démarre exactement au timestamp de Track 4 (pas de pre-roll)

- [ ] **Step 4 : Commit final**

```bash
git add app/src/main/java/com/podmix/ui/screens/episode/EpisodeDetailViewModel.kt
git commit -m "feat: wire EpisodeDetailViewModel to seekToTrack with pre-roll"
```
