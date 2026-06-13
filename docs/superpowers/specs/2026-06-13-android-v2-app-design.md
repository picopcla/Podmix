# Design: Podmix Android V2 New App

**Date:** 2026-06-13  
**Scope:** Android only, new `appv2/` module, no changes to V1 behavior, no backend changes  
**Objective:** Build a separate Podmix V2 APK aligned with `codex.md`, using a local-first Android architecture and the public VPS at `https://podmix.mb4.fr`

---

## 1. Product Direction

Podmix V2 is not a generic podcast app. The Android client should feel like a listening console centered on long-form music content and track-aware playback.

The first usable V2 must establish these product truths immediately:

- `Podcast`, `Liveset`, `Emission`, and `Radio` are distinct content families with different business rules.
- `Favoris` and `Tracks sauvegardees` are separate modules.
- Analysis status must be visible in the UI, not hidden behind logs.
- Timestamps must be presented with explicit quality: reliable, partial, or absent.
- The APK only orchestrates UI, local cache, playback, and lightweight sync with the VPS.

---

## 2. Scope Boundaries

### In Scope

- New Android app module `appv2/`
- Compose UI, navigation, Room, playback shell, sync shell
- Domain models, repository contracts, use cases
- Public backend wiring to `https://podmix.mb4.fr`
- First vertical slice on `GET /health`
- Local persistence of backend state before display

### Out of Scope

- Any modification of V1 behavior in `app/`
- Any modification under `backend/`
- Scraping, AI, yt-dlp, fragile parsing, or heavy audio analysis in the APK
- Assuming undocumented API endpoints beyond what `STATUS.md` confirms

---

## 3. Architecture

The V2 app will start as a new Android module with strict internal boundaries so it can evolve without inheriting the V1 monolith.

### Module Strategy

- Keep existing `app/` unchanged as V1.
- Create `appv2/` as a separate Android application module.
- Give `appv2/` its own `namespace`, `applicationId`, manifest, resources, and Gradle config.
- Allow both apps to coexist in the repo during migration.

### Dependency Rule

```text
UI -> ViewModel -> UseCase -> Repository interface -> Data source
```

No screen talks directly to Retrofit, Room DAO, or player internals.

### Package Layout

```text
appv2/
  src/main/java/com/podmix/v2/
    core/
      config/
      database/
      network/
      result/
      logging/
    domain/
      model/
      repository/
      usecase/
        podcast/
        liveset/
        emission/
        radio/
        favorites/
        savedtracks/
        playback/
        download/
        health/
    data/
      local/
        dao/
        entity/
        mapper/
      remote/
        api/
        dto/
      repository/
    features/
      hub/
      podcast/
      liveset/
      emission/
      radio/
      favorites/
      savedtracks/
      settings/
      player/
    playback/
    sync/
    di/
```

---

## 4. Domain Model

The domain must be defined first so that UI and data layers follow business rules instead of inventing them.

### Core Enums

- `ContentType`: `PODCAST`, `LIVESET`, `EMISSION`, `RADIO`
- `AnalysisStatus`: `IDLE`, `PENDING`, `RUNNING`, `SUCCEEDED`, `PARTIAL`, `FAILED`
- `TimestampQuality`: `RELIABLE`, `PARTIAL`, `ABSENT`
- `DownloadStatus`: `NOT_DOWNLOADED`, `QUEUED`, `DOWNLOADING`, `DOWNLOADED`, `FAILED`
- `PlaybackSourceType`: `STREAM`, `LOCAL_FILE`, `LIVE_STREAM`

### Core Models

- `Podcast`
- `Liveset`
- `Emission`
- `Radio`
- `FavoriteContent`
- `SavedTrack`
- `TrackReference`
- `AnalysisSummary`
- `PlayableMedia`
- `PlaybackProgress`
- `BackendHealth`

### Business Constraints

- `Emission` never exposes tracklist or timestamp analysis actions.
- `Radio` never becomes favorite content and never stores persistent tracklists.
- `FavoriteContent` only wraps podcast episodes and livesets.
- `SavedTrack` only comes from podcasts and livesets.
- `PlayableMedia` is the only input accepted by the playback layer.

---

## 5. Data and Persistence

Room is the source of display state. Remote responses must be normalized and stored locally before UI consumption.

### Initial Room Scope

The first iteration of Room in V2 should stay narrow:

- `backend_health`
- `content_catalog`
- `analysis_state`
- `saved_tracks`
- `favorite_contents`
- `playback_progress`

The first vertical slice only requires `backend_health`, but the database should be created in a way that clearly supports later content slices.

### Remote Strategy

- `PodmixBackendApi` uses Retrofit with base URL sourced from config.
- Base URL default is `https://podmix.mb4.fr`.
- No hard-coded LAN or private IPs.
- Only `GET /health` is live in the first slice.
- Other API interfaces may exist as placeholders, but must not be wired as if the contract were final until documented in `STATUS.md`.

---

## 6. UI Design

The V2 entry point is a unified hub, not a bottom-nav-first shell copied from V1.

### Hub Screen

The home screen acts as a command surface for the app:

- prominent backend health banner
- grouped cards for `Podcast`, `Liveset`, `Emission`, `Radio`
- separate entry points for `Favoris` and `Tracks sauvegardees`
- visible analysis semantics
- persistent mini-player shell at the bottom

### Visual Direction

- dark, console-like presentation
- content-family cards with distinct identity
- timeline and status language emphasized over decorative podcast tropes
- reliable/partial/absent timestamp state visible through labels and color treatment

### Navigation

Initial V2 navigation:

- `Hub`
- `Podcast`
- `Liveset`
- `Emission`
- `Radio`
- `Favoris`
- `Tracks sauvegardees`
- `Settings`
- `Player`

The hub is the entry point. Additional sections are reachable from the hub and can later grow into deeper flows.

---

## 7. Vertical Slice Order

The implementation should move in slices that validate the architecture without depending on undocumented backend behavior.

### Slice 1: App Foundation + Health

- create `appv2/`
- configure separate APK identity
- create V2 theme, application, manifest, DI, navigation shell
- define core domain models and repository interfaces
- add Room database and backend health table
- add Retrofit client for `GET /health`
- persist health locally
- show health status in the hub screen

### Slice 2: Content Shell

- add empty or local-only feature screens for `Podcast`, `Liveset`, `Emission`, `Radio`
- add shared content summary card model
- establish analysis status rendering patterns

### Slice 3: Playback Shell

- define `ResolvePlayableMediaUseCase`, `PlayMediaUseCase`, `PersistPlaybackProgressUseCase`
- add mini-player state holder and player screen shell
- no V1 player service reuse unless a narrow piece is cleanly extractable

### Slice 4: Favorites and Saved Tracks Shell

- add Room tables and feature screens
- enforce business-rule separation

Further slices depend on backend API contracts being documented in `STATUS.md`.

---

## 8. Error Handling and Sync

- `GET /health` failure must produce a persisted offline or degraded state, not just a thrown exception.
- Sync logic stays lightweight and Android-owned: polling, refresh triggers, local storage.
- Long-running analysis orchestration stays on the VPS.
- Android logs should be diagnostic and structured, but not the primary user feedback surface.

---

## 9. Testing Strategy

The first V2 implementation should include targeted coverage for architecture-critical seams.

- unit tests for domain model mapping and health repository behavior
- unit tests for `ObserveBackendHealthUseCase`
- DAO tests for persisted backend health state
- simple Compose tests for hub rendering of health state

The first milestone does not require a full end-to-end playback test suite.

---

## 10. Delivery Rules for This Session

This session should deliver:

- `appv2/` module scaffold
- V2 APK identity
- first V2 architecture packages
- first domain models and repository contracts
- Room + Retrofit health slice
- unified hub screen with backend status
- `STATUS.md` update at session end
- commit and push on `codex/android-v2`

If backend requirements block Android progress, the missing contract must be written into `STATUS.md` under `Android / Needs From Backend`.
