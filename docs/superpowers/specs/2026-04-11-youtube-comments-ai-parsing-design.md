# Design: AI-Powered YouTube Comment Tracklist Parsing

**Date:** 2026-04-11  
**Status:** Approved

## Problem

YouTube comment tracklists appear in dozens of formats that rigid regex parsers cannot cover. The current `YouTubeCommentsService.analyzeCommentForTracklist()` uses 3 fixed strategies (timestamped lines, numbered lists, plain "Artist - Title" lines) with hard thresholds. Any deviation — unusual separators, mixed formats, free-form prose with embedded tracks — causes a miss.

## Solution

Keep regex as the fast path. Add Gemini 1.5 Flash API as a fallback when regex fails but the comment structurally resembles a tracklist. The app already needs internet to fetch comments, so one extra API call adds no new dependency.

## Architecture

### New: `GeminiTracklistParser`

Singleton Hilt service. Single public method:

```kotlin
suspend fun parseComment(text: String): List<ParsedTrack>?
```

- Makes a single `POST` to `generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent`
- API key injected via `BuildConfig.GEMINI_API_KEY` (from `local.properties`, never committed)
- Prompt instructs the model to return a JSON array of `{artist, title, startTimeSec}` and nothing else
- Parses response JSON → `List<ParsedTrack>`
- Timeout: 5 seconds
- On any error (network, 429, malformed JSON): returns `null` silently
- In-memory cache: `HashMap<Int, List<ParsedTrack>>` keyed by `commentText.hashCode()` — avoids re-calling the API for the same comment

### Modified: `YouTubeCommentsService.analyzeCommentForTracklist()`

Becomes `suspend`. New flow per comment:

1. Clean text (strip `&nbsp;`, URLs, etc.) — unchanged
2. `parseTimestamped()` → if ≥3 tracks, return
3. `parseNumberedList()` → if ≥3 tracks, return
4. `parsePlainLines()` → if ≥5 tracks, return
5. **Gemini fallback** — only if comment passes the "looks like a tracklist" heuristic:
   - ≥2 lines matching a timestamp pattern (`\d{1,2}:\d{2}`) **or**
   - ≥4 lines matching a numbered pattern (`^\d+[.)]`) **or**
   - ≥6 lines containing a ` - ` separator
   - AND `text.length > 100`
6. Call `geminiParser.parseComment(text)` → if ≥3 tracks, return
7. Return empty list

The caller (`extractTracklistFromComments`) is already a suspend function — no further changes needed there.

## Files Impacted

| File | Change |
|------|--------|
| `service/GeminiTracklistParser.kt` | NEW — Gemini API client + in-memory cache |
| `service/YouTubeCommentsService.kt` | MODIFY — `analyzeCommentForTracklist()` becomes `suspend`, adds Gemini fallback |
| `app/build.gradle.kts` | MODIFY — expose `BuildConfig.GEMINI_API_KEY` from `local.properties` |
| `local.properties` | MODIFY — add `gemini.api.key=<value>` (never committed) |
| `di/NetworkModule.kt` | no change — `GeminiTracklistParser` uses `@Inject constructor` directly |

## Gemini Prompt

```
Extract the music tracklist from this YouTube comment.
Return ONLY a JSON array, no other text.
Each object: {"artist": "...", "title": "...", "startTimeSec": 0}
Rules:
- Convert MM:SS or HH:MM:SS timestamps to seconds. 0 if none.
- Empty string for artist if unknown.
- Ignore non-track lines (descriptions, links, etc.).

Comment:
[COMMENT TEXT]
```

## API Key Management

- Stored in `local.properties`: `gemini.api.key=AIza...`
- `local.properties` is in `.gitignore` — never committed
- Exposed via `build.gradle.kts` `buildConfigField`
- Accessed at runtime via `BuildConfig.GEMINI_API_KEY`
- Regenerate the key after any accidental exposure

## Free Tier Constraints

Gemini 1.5 Flash free tier: 15 RPM, 1M tokens/day. A YouTube comment is ~200-500 tokens. The fallback only fires when all 3 regex parsers fail, so API calls are rare. Well within free limits for personal use.

## What Does NOT Change

- `TracklistService` and its 3 regex parsers — untouched
- Pagination logic in `extractTracklistFromComments()` — untouched
- Early exit at ≥5 tracks — untouched
- `TrackRepository` pipeline order — untouched
