# YouTube Comments AI Parsing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Gemini 1.5 Flash as a fallback parser in `YouTubeCommentsService` when all regex strategies fail on a comment that structurally looks like a tracklist.

**Architecture:** A new singleton `GeminiTracklistParser` handles the Gemini API call via OkHttp and in-memory caching. `YouTubeCommentsService.analyzeCommentForTracklist()` becomes `suspend` and calls the parser as a last resort after the 3 existing regex strategies. The rest of the pipeline (pagination, early exit, `TrackRepository` order) is unchanged.

**Tech Stack:** Kotlin coroutines, OkHttp (already in project), `org.json.JSONArray`, Hilt `@Singleton`, `BuildConfig` for API key.

---

## File Map

| File | Action |
|------|--------|
| `app/src/main/java/com/podmix/service/GeminiTracklistParser.kt` | CREATE |
| `app/src/main/java/com/podmix/service/YouTubeCommentsService.kt` | MODIFY |
| `app/build.gradle.kts` | MODIFY — add `buildConfigField` for API key |
| `local.properties` | MODIFY — add `gemini.api.key` |

---

## Task 1: Expose API key via BuildConfig

**Files:**
- Modify: `local.properties`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add key to local.properties**

Append to `C:\APP\Podmix\local.properties`:
```
gemini.api.key=AIzaSyAQM2MfATEtOa-tI7rtW0Inw_3HYCjQ_M0
```

- [ ] **Step 2: Read property and expose via BuildConfig**

In `app/build.gradle.kts`, inside the `android { defaultConfig { ... } }` block, add after the `versionName` line:

```kotlin
val localProps = java.util.Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}
buildConfigField(
    "String",
    "GEMINI_API_KEY",
    "\"${localProps.getProperty("gemini.api.key", "")}\""
)
```

- [ ] **Step 3: Verify build succeeds**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`. `BuildConfig.GEMINI_API_KEY` now contains the key.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts local.properties
git commit -m "feat: expose GEMINI_API_KEY via BuildConfig"
```

---

## Task 2: Create GeminiTracklistParser

**Files:**
- Create: `app/src/main/java/com/podmix/service/GeminiTracklistParser.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.podmix.service

import android.util.Log
import com.podmix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiTracklistParser @Inject constructor() {

    private val TAG = "GeminiParser"

    private val cache = HashMap<Int, List<ParsedTrack>>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val PROMPT_PREFIX = """
Extract the music tracklist from this YouTube comment.
Return ONLY a JSON array, no other text.
Each object: {"artist": "...", "title": "...", "startTimeSec": 0}
Rules:
- Convert MM:SS or HH:MM:SS timestamps to seconds. 0 if none.
- Empty string for artist if unknown.
- Ignore non-track lines (descriptions, links, etc.).

Comment:
""".trimIndent()

    /**
     * Returns null on any error (network, rate limit, malformed JSON, empty result).
     * Results are cached by comment hash to avoid duplicate API calls.
     */
    suspend fun parseComment(text: String): List<ParsedTrack>? = withContext(Dispatchers.IO) {
        val key = text.hashCode()
        cache[key]?.let { return@withContext it }

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            Log.w(TAG, "GEMINI_API_KEY not set — skipping AI parse")
            return@withContext null
        }

        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", PROMPT_PREFIX + text)
                            })
                        })
                    })
                })
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val responseText = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Gemini API error: ${resp.code}")
                    return@withContext null
                }
                resp.body?.string()
            } ?: return@withContext null

            val tracks = parseGeminiResponse(responseText)
            if (tracks != null) cache[key] = tracks
            tracks
        } catch (e: Exception) {
            Log.w(TAG, "Gemini parse failed: ${e.message}")
            null
        }
    }

    private fun parseGeminiResponse(responseText: String): List<ParsedTrack>? {
        return try {
            // Extract the text field from Gemini response envelope
            val root = JSONObject(responseText)
            val text = root
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            // Strip markdown code fences if Gemini adds them
            val json = text
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```")
                .trim()

            val arr = JSONArray(json)
            val tracks = mutableListOf<ParsedTrack>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val artist = obj.optString("artist", "").trim()
                val title = obj.optString("title", "").trim()
                val timeSec = obj.optDouble("startTimeSec", 0.0).toFloat()
                if (title.isNotBlank()) {
                    tracks.add(ParsedTrack(artist, title, timeSec))
                }
            }
            if (tracks.size >= 3) tracks else null
        } catch (e: Exception) {
            Log.w(TAG, "Response parse error: ${e.message}")
            null
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/podmix/service/GeminiTracklistParser.kt
git commit -m "feat: add GeminiTracklistParser for AI-powered comment parsing"
```

---

## Task 3: Wire Gemini fallback into YouTubeCommentsService

**Files:**
- Modify: `app/src/main/java/com/podmix/service/YouTubeCommentsService.kt`

- [ ] **Step 1: Inject GeminiTracklistParser**

Change the constructor from:
```kotlin
class YouTubeCommentsService @Inject constructor(
    private val youTubeStreamResolver: YouTubeStreamResolver,
    private val tracklistService: TracklistService
)
```
to:
```kotlin
class YouTubeCommentsService @Inject constructor(
    private val youTubeStreamResolver: YouTubeStreamResolver,
    private val tracklistService: TracklistService,
    private val geminiParser: GeminiTracklistParser
)
```

- [ ] **Step 2: Add the "looks like a tracklist" heuristic**

Add this private function to the class (before `analyzeCommentForTracklist`):

```kotlin
private fun looksLikeTracklist(text: String): Boolean {
    if (text.length < 100) return false
    val lines = text.lines()
    val timestampLines = lines.count { Regex("""\d{1,2}:\d{2}""").containsMatchIn(it) }
    if (timestampLines >= 2) return true
    val numberedLines = lines.count { Regex("""^\d{1,3}[.)]\s""").containsMatchIn(it.trim()) }
    if (numberedLines >= 4) return true
    val dashLines = lines.count { it.contains(" - ") }
    if (dashLines >= 6) return true
    return false
}
```

- [ ] **Step 3: Make analyzeCommentForTracklist suspend and add Gemini fallback**

Replace the existing `analyzeCommentForTracklist` function:

```kotlin
private suspend fun analyzeCommentForTracklist(commentText: String): List<ParsedTrack> {
    val clean = commentText
        .replace("&nbsp;", " ")
        .replace("\u00A0", " ")
        .replace(Regex("""https?://\S+"""), "")
        .trim()

    // Priority 1: timestamped lines
    val timestamped = tracklistService.parseTimestamped(clean)
    if (timestamped.size >= 3) return timestamped

    // Priority 2: numbered list
    val numbered = tracklistService.parseNumberedList(clean)
    if (numbered.size >= 3) return numbered

    // Priority 3: plain "Artist - Title" lines
    val plain = tracklistService.parsePlainLines(clean)
    if (plain.size >= 5) return plain

    // Priority 4: Gemini AI fallback — only if comment looks like a tracklist
    if (looksLikeTracklist(clean)) {
        Log.d(TAG, "Regex failed, trying Gemini AI fallback...")
        val fromGemini = geminiParser.parseComment(clean)
        if (fromGemini != null) {
            Log.i(TAG, "Gemini extracted ${fromGemini.size} tracks")
            return fromGemini
        }
    }

    return emptyList()
}
```

- [ ] **Step 4: Fix the callers — processPage must be suspend**

In `extractTracklistFromComments`, the inner `processPage` function calls `analyzeCommentForTracklist` which is now suspend. Change `processPage` to a suspend lambda:

Replace:
```kotlin
fun processPage(items: List<*>) {
    for (item in items) {
        if (item !is CommentsInfoItem) continue
        val text = item.commentText?.content ?: continue
        scanned++
        val tracks = analyzeCommentForTracklist(text)
        if (tracks.size > bestTracks.size) {
            bestTracks = tracks
            Log.d(TAG, "Nouveau meilleur commentaire: ${tracks.size} tracks (commentaire #$scanned)")
            if (tracks.size >= 5) return // early exit — tracklist complète
        }
    }
}

processPage(commentsInfo.relatedItems)

var nextPage = commentsInfo.nextPage
while (nextPage != null && scanned < maxComments && bestTracks.size < 5) {
    val page = CommentsInfo.getMoreItems(commentsInfo, nextPage)
    processPage(page.items)
    nextPage = page.nextPage
}
```

with:
```kotlin
suspend fun processPage(items: List<*>) {
    for (item in items) {
        if (item !is CommentsInfoItem) continue
        val text = item.commentText?.content ?: continue
        scanned++
        val tracks = analyzeCommentForTracklist(text)
        if (tracks.size > bestTracks.size) {
            bestTracks = tracks
            Log.d(TAG, "Nouveau meilleur commentaire: ${tracks.size} tracks (commentaire #$scanned)")
            if (tracks.size >= 5) return // early exit — tracklist complète
        }
    }
}

processPage(commentsInfo.relatedItems)

var nextPage = commentsInfo.nextPage
while (nextPage != null && scanned < maxComments && bestTracks.size < 5) {
    val page = CommentsInfo.getMoreItems(commentsInfo, nextPage)
    processPage(page.items)
    nextPage = page.nextPage
}
```

- [ ] **Step 5: Build**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/podmix/service/YouTubeCommentsService.kt
git commit -m "feat: add Gemini AI fallback to YouTube comment tracklist parser"
```

---

## Task 4: Install and verify on device

- [ ] **Step 1: Install**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Clear logcat and open an episode with a YouTube video**

```bash
adb logcat -c
```
Open an episode on device, let the tracklist detection run.

- [ ] **Step 3: Check logs**

```bash
adb logcat -d -s GeminiParser:D TrackRepo:I
```

Expected log flow for a comment that hits Gemini:
```
D GeminiParser: Regex failed, trying Gemini AI fallback...
I GeminiParser: Gemini extracted 18 tracks
```

Expected log for a comment resolved by regex (no API call):
```
I TrackRepo: [45%] Commentaires on-device: 20 tracks — commentaires YouTube
```

- [ ] **Step 4: Bump version**

In `app/build.gradle.kts`:
```kotlin
versionCode = 17
versionName = "1.5.0"
```

- [ ] **Step 5: Final commit**

```bash
git add app/build.gradle.kts
git commit -m "chore: bump version to 1.5.0 (Gemini AI comment parsing)"
```
