from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field


AnalysisStatus = Literal["pending", "running", "succeeded", "failed"]
TimestampStatus = Literal["none", "partial", "reliable"]
TracklistStatus = Literal["not_found", "found", "uncertain"]


class AnalysisRequest(BaseModel):
    local_content_id: int = Field(description="Local APK episode or liveset id.")
    title: str
    source_url: str | None = None
    creator_name: str | None = None
    description: str | None = None
    duration_seconds: int | None = None
    first_mentioned_track: str | None = None


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
    content_kind: Literal["podcast", "liveset"]
    tracklist_status: TracklistStatus
    timestamp_status: TimestampStatus
    match_score: float
    evidence: list[str]
    tracks: list[AnalyzedTrack]

