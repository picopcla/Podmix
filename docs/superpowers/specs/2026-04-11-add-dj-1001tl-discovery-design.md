# Design: 1001TL-Based DJ Set Discovery in AddDjScreen

**Date:** 2026-04-11  
**Status:** Approved

## Problem

The current `AddDjScreen` searches YouTube for DJ sets and adds them one by one. YouTube search results are unreliable for finding specific sets, don't include tracklists, and require manual title matching. 1001TL already has the authoritative list of sets for any DJ, with tracklists included.

## Solution

Replace the YouTube discovery flow in `AddDjScreen` / `AddDjViewModel` with a 1001TL artist page scraper. Everything else (navigation, `DjDetailScreen`, `EpisodeDetailScreen`, data model, tracklist pipeline) stays unchanged.

## New User Flow

1. User opens AddDjScreen, types "Korolova"
2. DDG search finds `1001tracklists.com/dj/korolova/` (same DDG approach as current `try1001TL`)
3. WebView loads the artist page → JS extracts set list: `{title, date, tracklistUrl, viewCount, youtubeVideoId}`
4. List displays with checkboxes + sort toggle: **Most Viewed** / **Most Recent**
5. User checks desired sets → taps "Importer (N)"
6. For each selected set: scrape tracklist via `TracklistWebScraper` + create `EpisodeEntity(youtubeVideoId)` + save tracks
7. Sets appear immediately in existing `DjDetailScreen`

## "Add More" Flow

A "+" button in `DjDetailScreen` triggers the same flow. Sets already imported (matched by `youtubeVideoId`) are shown greyed-out/disabled so the user can distinguish new vs. existing sets.

## Architecture

### New: `ArtistPageScraper`

Singleton Hilt service. Single public method:

```kotlin
suspend fun scrapeArtistSets(artistPageUrl: String): List<ArtistSet>?
```

Where `ArtistSet`:
```kotlin
data class ArtistSet(
    val title: String,
    val date: String,          // e.g. "2021-02-26"
    val tracklistUrl: String,  // full 1001TL tracklist URL
    val viewCount: Int,        // for "Most Viewed" sort
    val youtubeVideoId: String? // extracted from 1001TL page if present
)
```

Uses `WebView` on the main thread (same pattern as `TracklistWebScraper`):
- Loads `artistPageUrl`
- Injects JS that extracts set rows from the DOM
- Calls `Android.onSetsExtracted(json)` callback
- Timeout: 15s via `withTimeoutOrNull`
- `webView.destroy()` posted to main looper (same fix as `TracklistWebScraper`)

JS selectors on 1001TL artist pages:
- Set rows: `div.tlpTog, div.tl-item` (or `a[href*="/tracklist/"]` parent containers)
- Title: the link text
- Date: `span.date` or similar
- View count: `span.tlpViewCount` or similar
- YouTube ID: extracted from `href` attributes containing `youtube.com/watch?v=`

### Modified: `AddDjViewModel`

Replaces YouTube search with two-phase 1001TL discovery:

**Phase 1 — Find artist page:**
- DDG search: `site:1001tracklists.com/dj [query]`
- Extract first `uddg=` URL containing `1001tracklists.com/dj/`
- Same OkHttp approach as current `try1001TL` in `TrackRepository`

**Phase 2 — Scrape sets:**
- Call `ArtistPageScraper.scrapeArtistSets(artistPageUrl)`
- Sort by view count (default) or date
- Filter out already-imported sets (by `youtubeVideoId` match against existing episodes for this DJ)

New state:
```kotlin
data class ArtistSetUiItem(
    val set: ArtistSet,
    val isAlreadyImported: Boolean,
    val isSelected: Boolean = false
)

val sets: StateFlow<List<ArtistSetUiItem>>
val isLoading: StateFlow<Boolean>
val sortMode: StateFlow<SortMode>  // MOST_VIEWED | MOST_RECENT
val importProgress: StateFlow<ImportProgress?>  // null when idle

enum class SortMode { MOST_VIEWED, MOST_RECENT }
data class ImportProgress(val current: Int, val total: Int, val currentTitle: String)
```

**Import function:**
```kotlin
fun importSelected()
```
For each selected `ArtistSet`:
1. Call `TracklistWebScraper.scrape1001TL(set.tracklistUrl)` → get `List<ParsedTrack>`
2. Create `EpisodeEntity` with `youtubeVideoId`, `title`, `datePublished`, `episodeType = "liveset"`
3. Save episode + tracks via existing DAOs
4. Emit progress via `importProgress`

After all imports: navigate to `DjDetailScreen` (same as current `onDjAdded(djId)` callback).

### Modified: `AddDjScreen`

- Search bar → triggers artist page search
- Loading spinner while WebView scrapes
- Segmented control: **Most Viewed** | **Most Recent**
- Scrollable list of sets with:
  - Checkbox (disabled + greyed for already-imported)
  - Title + date
  - Track count (if available from scrape)
  - View count
- Bottom bar: **"Importer (N)"** button, enabled when ≥1 selected
- Progress overlay during import: "Importing 3/8 — Frozen Lake..."

## Files Impacted

| File | Action |
|------|--------|
| `service/ArtistPageScraper.kt` | CREATE |
| `ui/screens/liveset/AddDjViewModel.kt` | REWRITE |
| `ui/screens/liveset/AddDjScreen.kt` | REWRITE |
| `ui/screens/liveset/DjDetailScreen.kt` | MODIFY — add "+" FAB that navigates back to AddDjScreen with djId |

## What Does NOT Change

- `DjDetailScreen` content and layout (except "+" FAB)
- `EpisodeDetailScreen` and tracklist display
- `TracklistWebScraper` (reused as-is)
- `TrackRepository` and detection pipeline
- Navigation graph
- Data model (`PodcastEntity`, `EpisodeEntity`, Room schema)
- All other screens (Podcasts, Emissions, Radio, Favorites)
