# Podcast Tracklist — Pipeline 1001TL Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Étendre le pipeline tracklist des podcasts pour sonder 1001Tracklists via DDG quand la description RSS ne contient pas de timestamps, et corriger les callbacks `onSourceResult` manquants dans la branche podcast.

**Architecture:** Un seul fichier à modifier — `TrackRepository.kt`. La branche `else` (podcasts, lignes ~299-357) remplace le pipeline `Description → Shazam` par `Description → 1001TL (DDG) → Shazam`. Les callbacks `onSourceResult` sont ajoutés à chaque étape pour que le banner de diagnostic de l'UI reflète l'état réel. Aucun nouveau service ni aucune nouvelle injection n'est nécessaire : `try1001TL()` existe déjà et est utilisé par la branche liveset.

**Tech Stack:** Kotlin, Room, Coroutines, OkHttp, `TracklistWebScraper` (WebView), DuckDuckGo HTML search

---

## File Map

| Fichier | Action |
|---|---|
| `data/repository/TrackRepository.kt` | Remplacer la branche `else` (podcasts) lignes ~299-357 |

---

### Task 1 : Remplacer la branche podcast dans `TrackRepository.detectAndSaveTracks()`

**Files:**
- Modify: `app/src/main/java/com/podmix/data/repository/TrackRepository.kt`

**Contexte :** La branche `else` commence autour de la ligne 299 par le commentaire `// ── PODCASTS DJ`. Elle se termine avec la fermeture du bloc `if (isLiveSet) { ... } else { ... }` autour de la ligne 357.

**Pipeline actuel (podcasts) :**
```
Description → [timestamps?] → done | uniform + Shazam
             → [rien] → Shazam direct | uniform fallback
```

**Nouveau pipeline (podcasts) :**
```
Description → [timestamps] → done (SKIPPED: 1001TL, Shazam)
            → [tracks sans timestamps] → continue
            → [rien] → continue
1001TL DDG  → [trouvé] → done (SKIPPED: Shazam)
            → [rien] → continue
Description uniforme (si ≥3 tracks) + Shazam pour timestamps
Shazam direct si pas de description
Fallback uniform si description partielle
```

- [ ] **Step 1 : Lire le fichier**

Lire `app/src/main/java/com/podmix/data/repository/TrackRepository.kt` pour localiser exactement la ligne de début et de fin du bloc `else { // PODCASTS }`.

- [ ] **Step 2 : Remplacer la branche podcast**

Remplacer tout le bloc `else { ... }` (podcasts) par le code suivant — **conserver intacte la branche `if (isLiveSet)` au-dessus** :

```kotlin
        } else {
            // ── PODCASTS ────────────────────────────────────────────────────────
            // Pipeline : Description RSS → 1001Tracklists (DDG) → Shazam
            // Sources non applicables aux podcasts → SKIPPED immédiatement

            listOf("Commentaires YouTube", "Mixcloud", "MixesDB", "yt-dlp").forEach {
                onSourceResult?.invoke(SourceResult(it, SourceStatus.SKIPPED, reason = "non applicable aux podcasts"))
            }

            // ── 1. Description RSS ──
            val tD = System.currentTimeMillis()
            onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.RUNNING))
            status(10, "📄 Analyse description... ${elapsed()}")
            val fromDesc = withContext(Dispatchers.IO) {
                tracklistService.detect(description, podcastName, episodeTitle)
            }
            val descElapsed = System.currentTimeMillis() - tD
            status(30, "Description: ${fromDesc.size} tracks trouvés ${elapsed()}")

            val descHasTimestamps = fromDesc.any { it.startTimeSec > 0f }
            if (fromDesc.size >= 3 && descHasTimestamps) {
                // Description avec timestamps → parfait, terminé
                saveTracks(episodeId, fromDesc, true, episodeDurationSec, "timestamped")
                onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.SUCCESS,
                    trackCount = fromDesc.size, elapsedMs = descElapsed))
                onSourceResult?.invoke(SourceResult("1001Tracklists", SourceStatus.SKIPPED,
                    reason = "description RSS suffisante"))
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED,
                    reason = "description RSS suffisante"))
                status(100, "✅ ${fromDesc.size} tracks — timestamps description ${elapsed()}")
                return true
            }

            // Marquer la description (sans timestamps ou insuffisante)
            if (fromDesc.size >= 3) {
                onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.SUCCESS,
                    trackCount = fromDesc.size, elapsedMs = descElapsed, reason = "sans timestamps"))
            } else {
                onSourceResult?.invoke(SourceResult("Description YouTube", SourceStatus.FAILED,
                    elapsedMs = descElapsed,
                    reason = if (fromDesc.isEmpty()) "aucun track détecté" else "trop peu de tracks (${fromDesc.size})"))
            }

            // ── 2. 1001Tracklists via DDG ──
            val t1001 = System.currentTimeMillis()
            onSourceResult?.invoke(SourceResult("1001Tracklists", SourceStatus.RUNNING))
            status(35, "🌐 1001Tracklists... ${elapsed()}")
            val from1001 = try1001TL(query, knownUrl = null)
            val elapsed1001 = System.currentTimeMillis() - t1001
            if (from1001 != null && from1001.size >= 3) {
                val has1001Timestamps = from1001.any { it.startTimeSec > 0f }
                trackDao.deleteByEpisode(episodeId)
                saveTracks(episodeId, from1001, has1001Timestamps, episodeDurationSec, "1001tl")
                onSourceResult?.invoke(SourceResult("1001Tracklists", SourceStatus.SUCCESS,
                    trackCount = from1001.size, elapsedMs = elapsed1001))
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED,
                    reason = "1001TL suffisant"))
                status(100, "✅ ${from1001.size} tracks — 1001Tracklists ${elapsed()}")
                return true
            }
            onSourceResult?.invoke(SourceResult("1001Tracklists", SourceStatus.FAILED,
                elapsedMs = elapsed1001, reason = "aucun résultat"))
            status(50, "1001TL: rien trouvé ${elapsed()}")

            // ── 3. Description sans timestamps + Shazam pour améliorer ──
            if (fromDesc.size >= 3) {
                // Afficher d'abord la version uniforme (feedback immédiat)
                saveTracks(episodeId, fromDesc, false, episodeDurationSec, "uniform")
                val shazamUrl = if (youtubeVideoId != null)
                    "https://www.youtube.com/watch?v=$youtubeVideoId" else audioUrl
                if (shazamUrl != null && shazamServerUp) {
                    val tS = System.currentTimeMillis()
                    onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.RUNNING))
                    status(60, "🎵 Shazam pour timestamps précis... ${elapsed()}")
                    val fromShazam = tryShazamAnalysisByUrl(shazamUrl)
                    val shazamElapsed = System.currentTimeMillis() - tS
                    if (fromShazam != null && fromShazam.size >= 3) {
                        trackDao.deleteByEpisode(episodeId)
                        saveTracks(episodeId, fromShazam, true, 0, "shazam")
                        onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SUCCESS,
                            trackCount = fromShazam.size, elapsedMs = shazamElapsed))
                        status(100, "✅ ${fromShazam.size} tracks — Shazam ${elapsed()}")
                        return true
                    }
                    onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.FAILED,
                        elapsedMs = shazamElapsed, reason = "pas de résultat"))
                    status(90, "Shazam: pas de résultat — timestamps estimés ${elapsed()}")
                } else {
                    val reason = if (shazamUrl == null) "pas de source audio" else "serveur hors ligne"
                    onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED, reason = reason))
                }
                status(100, "✅ ${fromDesc.size} tracks — estimé ${elapsed()}")
                return true
            }

            // ── 4. Pas de description — Shazam direct ──
            val shazamUrl2 = if (youtubeVideoId != null)
                "https://www.youtube.com/watch?v=$youtubeVideoId" else audioUrl
            if (shazamUrl2 != null && shazamServerUp) {
                val tS2 = System.currentTimeMillis()
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.RUNNING))
                status(50, "🎵 Shazam direct (pas de description)... ${elapsed()}")
                val fromShazam2 = tryShazamAnalysisByUrl(shazamUrl2)
                val shazamElapsed2 = System.currentTimeMillis() - tS2
                if (fromShazam2 != null && fromShazam2.size >= 3) {
                    trackDao.deleteByEpisode(episodeId)
                    saveTracks(episodeId, fromShazam2, true, 0, "shazam")
                    onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SUCCESS,
                        trackCount = fromShazam2.size, elapsedMs = shazamElapsed2))
                    status(100, "✅ ${fromShazam2.size} tracks — Shazam ${elapsed()}")
                    return true
                }
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.FAILED,
                    elapsedMs = shazamElapsed2, reason = "pas de résultat"))
            } else if (shazamUrl2 != null) {
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED,
                    reason = "serveur hors ligne"))
                status(50, "⚠️ Serveur hors ligne ${elapsed()}")
            } else {
                onSourceResult?.invoke(SourceResult("Shazam/IA", SourceStatus.SKIPPED,
                    reason = "pas de source audio"))
            }

            // Fallback : description uniforme si quelques tracks partiels
            if (fromDesc.isNotEmpty()) {
                saveTracks(episodeId, fromDesc, false, episodeDurationSec, "uniform")
                status(100, "✅ ${fromDesc.size} tracks — estimé ${elapsed()}")
                return true
            }
        }
```

- [ ] **Step 3 : Vérifier les invariants**

S'assurer que :
- La ligne `if (existing.isNotEmpty()) return true` et `onStatus?.invoke("Aucune tracklist trouvée")` en bas du fichier (après le if/else) sont **toujours présentes et intactes**
- La branche `if (isLiveSet)` est **inchangée**
- Aucun import supplémentaire nécessaire — tous les symboles (`SourceResult`, `SourceStatus`, `trackDao`, `tracklistService`, `tryShazamAnalysisByUrl`, `try1001TL`, `saveTracks`, `elapsed`, `status`) existent déjà dans la classe

- [ ] **Step 4 : Build**

```bash
cd /C/APP/Podmix && ./gradlew compileDebugKotlin 2>&1 | tail -20
```

Attendu : `BUILD SUCCESSFUL` — aucune erreur de compilation.

- [ ] **Step 5 : Commit**

```bash
git add app/src/main/java/com/podmix/data/repository/TrackRepository.kt
git commit -m "feat: add 1001TL DDG probe to podcast tracklist pipeline + fix onSourceResult callbacks"
```

---

## Self-Review

**Spec coverage :**
- ✅ 1001TL sondé pour les podcasts quand pas de timestamps RSS (Task 1, step 2 — bloc 2)
- ✅ Callbacks `onSourceResult` corrigés pour tous les steps du pipeline podcast (manquants avant)
- ✅ Sources non applicables marquées SKIPPED immédiatement (Comments, Mixcloud, MixesDB, yt-dlp)
- ✅ Branche liveset intacte — aucun changement
- ✅ `try1001TL(query, knownUrl = null)` — DDG utilisé car les podcasts n'ont pas de `tracklistPageUrl`

**Priorité des sources :**
1. Description avec timestamps → immédiatement terminé, 1001TL et Shazam SKIPPED
2. 1001TL (si description sans timestamps) → override la description, Shazam SKIPPED
3. Description sans timestamps → uniforme affiché + Shazam tente d'améliorer
4. Shazam direct → si pas de description du tout
5. Fallback uniform → si description partielle et Shazam offline

**Cohérence des types :**
- `SourceResult(source, status, trackCount, elapsedMs, reason)` — tous les champs nommés, cohérent avec `SourceResult.kt`
- `tryShazamAnalysisByUrl(url: String): List<ParsedTrack>?` — signature existante, utilisée identiquement
- `try1001TL(query: String, knownUrl: String?): List<ParsedTrack>?` — signature existante

**Placeholders :** Aucun.
