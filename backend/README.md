# Podmix V2 Backend

FastAPI service for Podmix analysis jobs and saved-track enrichment.

Public base URL:

```text
https://podmix.mb4.fr
```

Local Docker URL:

```text
http://localhost:8099
```

## Run

```bash
docker compose up --build
```

By default, state is persisted in `/var/lib/podmix/podmix.db` inside the
container. For local runs without Docker, the fallback path is
`/tmp/podmix-backend/podmix.db`.

Health check:

```bash
curl https://podmix.mb4.fr/health
```

## API Contract

Interactive OpenAPI documentation is available at `/docs`.

### Start podcast analysis

`POST /api/podmix/podcasts/analyze`

### Start liveset analysis

`POST /api/podmix/livesets/analyze`

Both endpoints accept:

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

Required fields are `local_content_id` (positive integer) and `title`
(non-empty string).

Current stub response:

```json
{
  "job_id": "a6bcd2d5-ef79-4a87-9d98-a4b789f65a55",
  "status": "succeeded",
  "analysis_id": "759a59d1-b0c1-401d-ae09-cbb90bcd1655",
  "message": "stub analysis completed"
}
```

### Get job

`GET /api/podmix/jobs/{job_id}`

Returns the job response above. Unknown UUIDs return `404`; malformed UUIDs
return `422`.

### Get analysis

`GET /api/podmix/analyses/{analysis_id}`

Current stub result:

```json
{
  "analysis_id": "759a59d1-b0c1-401d-ae09-cbb90bcd1655",
  "local_content_id": 42,
  "content_kind": "podcast",
  "tracklist_status": "not_found",
  "timestamp_status": "none",
  "match_score": 0.0,
  "evidence": ["stub backend is reachable"],
  "tracks": []
}
```

The stub deliberately reports `not_found`; it does not fabricate tracks or
timestamps.

### Enrich saved track

`POST /api/podmix/saved-tracks/enrich`

```json
{
  "local_track_id": 7,
  "artist": "Laurent Garnier",
  "title": "Crispy Bacon",
  "remix": null
}
```

Current stub result:

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

This endpoint is only for explicitly saved tracks. It must not enrich podcast
episodes, livesets, emissions, radios, or content favorites.

## Tests

```bash
python -m pip install -r requirements-dev.txt
pytest -q
```
