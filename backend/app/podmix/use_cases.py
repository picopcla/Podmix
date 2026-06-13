from uuid import UUID, uuid4

from app.podmix.schemas import (
    AnalysisJobResponse,
    AnalysisRequest,
    AnalysisResultResponse,
)


class InMemoryAnalysisStore:
    """Temporary store for the first VPS vertical slice.

    This intentionally returns deterministic stub results. Real 1001TL/YT/AI
    adapters replace this behind the same API contract.
    """

    def __init__(self) -> None:
        self._jobs: dict[UUID, AnalysisJobResponse] = {}
        self._results: dict[UUID, AnalysisResultResponse] = {}

    def create_stub_job(
        self,
        content_kind: str,
        request: AnalysisRequest,
    ) -> AnalysisJobResponse:
        job_id = uuid4()
        analysis_id = uuid4()

        result = AnalysisResultResponse(
            analysis_id=analysis_id,
            local_content_id=request.local_content_id,
            content_kind=content_kind,  # type: ignore[arg-type]
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

        self._results[analysis_id] = result
        self._jobs[job_id] = job
        return job

    def get_job(self, job_id: str) -> AnalysisJobResponse | None:
        return self._jobs.get(UUID(job_id))

    def get_result(self, analysis_id: str) -> AnalysisResultResponse | None:
        return self._results.get(UUID(analysis_id))


analysis_store = InMemoryAnalysisStore()

