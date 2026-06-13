from uuid import UUID, uuid4

from app.podmix.repository import AnalysisRepository, build_analysis_repository
from app.podmix.schemas import (
    AnalysisJobResponse,
    AnalysisRequest,
    AnalysisResultResponse,
    ContentKind,
    SavedTrackEnrichmentRequest,
    SavedTrackEnrichmentResponse,
)


class StartAnalysisUseCase:
    """Start a deterministic analysis job for the first VPS vertical slice.

    Real discovery and matching adapters will replace the stub result without
    changing the route contract or storage boundary.
    """

    def __init__(self, repository: AnalysisRepository) -> None:
        self._repository = repository

    def execute(
        self,
        request: AnalysisRequest,
        content_kind: ContentKind,
    ) -> AnalysisJobResponse:
        job_id = uuid4()
        analysis_id = uuid4()

        result = AnalysisResultResponse(
            analysis_id=analysis_id,
            local_content_id=request.local_content_id,
            content_kind=content_kind,
            tracklist_status="not_found",
            timestamp_status="none",
            match_score=0.0,
            evidence=["stub backend is reachable"],
            tracks=[],
        )
        job = AnalysisJobResponse(
            job_id=job_id,
            status="succeeded",
            analysis_id=analysis_id,
            message="stub analysis completed",
        )

        self._repository.save(request=request, job=job, result=result)
        return job


class GetAnalysisJobUseCase:
    def __init__(self, repository: AnalysisRepository) -> None:
        self._repository = repository

    def execute(self, job_id: UUID) -> AnalysisJobResponse | None:
        return self._repository.get_job(job_id)


class GetAnalysisResultUseCase:
    def __init__(self, repository: AnalysisRepository) -> None:
        self._repository = repository

    def execute(self, analysis_id: UUID) -> AnalysisResultResponse | None:
        return self._repository.get_result(analysis_id)


class EnrichSavedTrackUseCase:
    """Keep external music links scoped to explicitly saved tracks."""

    def execute(
        self,
        request: SavedTrackEnrichmentRequest,
    ) -> SavedTrackEnrichmentResponse:
        return SavedTrackEnrichmentResponse(
            local_track_id=request.local_track_id,
            status="not_found",
            normalized_artist=request.artist.strip(),
            normalized_title=request.title.strip(),
            links=[],
            evidence=["spotify and deezer adapters are not connected yet"],
        )


analysis_repository = build_analysis_repository()
start_analysis = StartAnalysisUseCase(analysis_repository)
get_analysis_job = GetAnalysisJobUseCase(analysis_repository)
get_analysis_result = GetAnalysisResultUseCase(analysis_repository)
enrich_saved_track = EnrichSavedTrackUseCase()
