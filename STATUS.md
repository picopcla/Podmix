# STATUS.md

## Session Status

Date: 2026-06-13
Branch: `codex/vps-backend`
Scope: `backend/`, `docker/`, `deploy/`, `scripts serveur`, docs backend

### Completed This Session

- Added a first Podmix V2 backend vertical slice under `backend/`
- Defined typed API contracts for podcast and liveset analysis jobs
- Added a stub endpoint for saved-track enrichment
- Added repository abstraction and SQLite-backed storage for analysis jobs/results
- Added API documentation in `backend/README.md`
- Added tests for the public HTTP contract
- Added a persistence test for the analysis repository
- Added Docker volume-backed backend state

### Validation

- `pytest -q` in `backend/` passes: 7 tests
- Python bytecode compile check passes for `backend/app` and `backend/tests`

## Backend / API Contracts

### Public base URL

- `https://podmix.mb4.fr`

### Endpoints

- `GET /health`
- `POST /api/podmix/podcasts/analyze`
- `POST /api/podmix/livesets/analyze`
- `GET /api/podmix/jobs/{job_id}`
- `GET /api/podmix/analyses/{analysis_id}`
- `POST /api/podmix/saved-tracks/enrich`

### Request contract

`POST /api/podmix/podcasts/analyze` and `POST /api/podmix/livesets/analyze` accept:

```json
{
  "local_content_id": 42,
  "title": "Episode or liveset title",
  "source_url": "https://example.com/audio",
  "creator_name": "Podcast or DJ name",
  "description": "Optional source description",
  "duration_seconds": 3600,
  "first_mentioned_track": "Artist - Title"
}
```

Required fields:

- `local_content_id` positive integer
- `title` non-empty string

`POST /api/podmix/saved-tracks/enrich` accepts:

```json
{
  "local_track_id": 7,
  "artist": "Laurent Garnier",
  "title": "Crispy Bacon",
  "remix": null
}
```

### Response contract

Analysis job response:

```json
{
  "job_id": "uuid",
  "status": "succeeded",
  "analysis_id": "uuid",
  "message": "stub analysis completed"
}
```

Analysis result response:

```json
{
  "analysis_id": "uuid",
  "local_content_id": 42,
  "content_kind": "podcast",
  "tracklist_status": "not_found",
  "timestamp_status": "none",
  "match_score": 0.0,
  "evidence": ["stub backend is reachable"],
  "tracks": []
}
```

Saved-track enrichment response:

```json
{
  "local_track_id": 7,
  "status": "not_found",
  "normalized_artist": "Laurent Garnier",
  "normalized_title": "Crispy Bacon",
  "links": [],
  "evidence": ["spotify and deezer adapters are not connected yet"]
}
```

## Breaking Changes

- `job_id` and `analysis_id` are UUID path parameters, not integers.
- Analysis jobs are currently stubbed and always return `status=succeeded` with `tracklist_status=not_found`.
- Saved-track enrichment is stubbed and does not return Spotify or Deezer links yet.
- The backend contract is limited to the `backend/` slice; the Android app is not modified in this session.
- Backend state now persists in SQLite across restarts.

## Handoff For Android

Android should only rely on the endpoints above and the JSON shapes documented here.

Do not assume:

- background polling semantics beyond `GET /api/podmix/jobs/{job_id}`
- tracklist presence from analysis responses
- enrichment links for saved tracks

The Android side should treat `not_found` as a real outcome, not an error.

## Next Backend Work

- Replace the analysis stub with podcast/liveset job execution
- Add persistent storage for jobs and analyses
- Add enrichment adapters for Spotify and Deezer
- Add deployment config for the public backend host if needed
