import asyncio
from uuid import uuid4

import httpx

from app.main import app


def request(
    method: str,
    path: str,
    json: dict[str, object] | None = None,
) -> httpx.Response:
    async def send() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(
            transport=transport,
            base_url="http://testserver",
        ) as client:
            return await client.request(method, path, json=json)

    return asyncio.run(send())


def test_health() -> None:
    response = request("GET", "/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "service": "podmix-backend",
    }


def test_podcast_analysis_can_be_retrieved() -> None:
    start_response = request(
        "POST",
        "/api/podmix/podcasts/analyze",
        json={
            "local_content_id": 42,
            "title": "Test episode",
            "creator_name": "Test podcast",
        },
    )

    assert start_response.status_code == 200
    job = start_response.json()
    assert job["status"] == "succeeded"

    job_response = request("GET", f"/api/podmix/jobs/{job['job_id']}")
    assert job_response.status_code == 200
    assert job_response.json() == job

    result_response = request(
        "GET",
        f"/api/podmix/analyses/{job['analysis_id']}"
    )
    assert result_response.status_code == 200
    result = result_response.json()
    assert result["local_content_id"] == 42
    assert result["content_kind"] == "podcast"
    assert result["tracklist_status"] == "not_found"
    assert result["timestamp_status"] == "none"
    assert result["tracks"] == []


def test_invalid_job_id_returns_validation_error() -> None:
    response = request("GET", "/api/podmix/jobs/not-a-uuid")

    assert response.status_code == 422


def test_unknown_analysis_returns_not_found() -> None:
    response = request("GET", f"/api/podmix/analyses/{uuid4()}")

    assert response.status_code == 404
    assert response.json()["detail"] == "analysis result not found"


def test_analysis_request_rejects_invalid_local_id() -> None:
    response = request(
        "POST",
        "/api/podmix/livesets/analyze",
        json={"local_content_id": 0, "title": "Invalid"},
    )

    assert response.status_code == 422


def test_saved_track_enrichment_is_explicitly_stubbed() -> None:
    response = request(
        "POST",
        "/api/podmix/saved-tracks/enrich",
        json={
            "local_track_id": 7,
            "artist": "  Laurent Garnier ",
            "title": " Crispy Bacon  ",
        },
    )

    assert response.status_code == 200
    assert response.json() == {
        "local_track_id": 7,
        "status": "not_found",
        "normalized_artist": "Laurent Garnier",
        "normalized_title": "Crispy Bacon",
        "links": [],
        "evidence": [
            "spotify and deezer adapters are not connected yet"
        ],
    }
