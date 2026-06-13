from threading import Lock
from typing import Protocol
from uuid import UUID

from app.podmix.schemas import AnalysisJobResponse, AnalysisResultResponse


class AnalysisRepository(Protocol):
    def save(
        self,
        job: AnalysisJobResponse,
        result: AnalysisResultResponse,
    ) -> None: ...

    def get_job(self, job_id: UUID) -> AnalysisJobResponse | None: ...

    def get_result(self, analysis_id: UUID) -> AnalysisResultResponse | None: ...


class InMemoryAnalysisRepository:
    """Process-local repository used until persistent job storage is added."""

    def __init__(self) -> None:
        self._jobs: dict[UUID, AnalysisJobResponse] = {}
        self._results: dict[UUID, AnalysisResultResponse] = {}
        self._lock = Lock()

    def save(
        self,
        job: AnalysisJobResponse,
        result: AnalysisResultResponse,
    ) -> None:
        with self._lock:
            self._jobs[job.job_id] = job
            self._results[result.analysis_id] = result

    def get_job(self, job_id: UUID) -> AnalysisJobResponse | None:
        with self._lock:
            return self._jobs.get(job_id)

    def get_result(self, analysis_id: UUID) -> AnalysisResultResponse | None:
        with self._lock:
            return self._results.get(analysis_id)
