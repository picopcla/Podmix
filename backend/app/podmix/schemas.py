from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field


AnalysisStatus = Literal["pending", "running", "succeeded", "failed"]
ContentKind = Literal["podcast", "liveset"]
EnrichmentStatus = Literal["found", "partial", "not_found"]
TimestampStatus = Literal["none", "partial", "reliable"]
TracklistStatus = Literal["not_found", "found", "uncertain"]


class AnalysisRequest(BaseModel):
    local_content_id: int = Field(
        ge=1,
        description="Local APK episode or liveset id.",
    )
    title: str = Field(min_length=1, max_length=500)
    source_url: str | None = None
    creator_name: str | None = Field(default=None, max_length=300)
    description: str | None = None
    duration_seconds: int | None = Field(default=None, ge=1)
    first_mentioned_track: str | None = Field(default=None, max_length=500)


class AnalysisJobResponse(BaseModel):
    job_id: UUID
    status: AnalysisStatus
    analysis_id: UUID | None = None
    message: str | None = None


class AnalyzedTrack(BaseModel):
    position: int
    artist: str
    title: str
    start_time_sec: float | None = None
    timestamp_quality: Literal["missing", "explicit", "estimated"] = "missing"
    source: str


class AnalysisResultResponse(BaseModel):
    analysis_id: UUID
    local_content_id: int
    content_kind: ContentKind
    tracklist_status: TracklistStatus
    timestamp_status: TimestampStatus
    match_score: float = Field(ge=0, le=1)
    evidence: list[str]
    tracks: list[AnalyzedTrack]


class SavedTrackEnrichmentRequest(BaseModel):
    local_track_id: int = Field(ge=1, description="Local APK saved-track id.")
    artist: str = Field(min_length=1, max_length=300)
    title: str = Field(min_length=1, max_length=500)
    remix: str | None = Field(default=None, max_length=300)


class ExternalTrackLink(BaseModel):
    service: Literal["spotify", "deezer"]
    url: str
    confidence: float = Field(ge=0, le=1)


class SavedTrackEnrichmentResponse(BaseModel):
    local_track_id: int
    status: EnrichmentStatus
    normalized_artist: str
    normalized_title: str
    links: list[ExternalTrackLink]
    evidence: list[str]
