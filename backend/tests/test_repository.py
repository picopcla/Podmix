from pathlib import Path
from uuid import uuid4

from app.podmix.repository import SqliteAnalysisRepository
from app.podmix.schemas import (
    AnalysisJobResponse,
    AnalysisRequest,
    AnalysisResultResponse,
)


def test_sqlite_analysis_repository_persists_round_trip(tmp_path: Path) -> None:
    database_path = tmp_path / "podmix.db"
    repository = SqliteAnalysisRepository(database_path)

    request = AnalysisRequest(
        local_content_id=11,
        title="Persistent test",
        creator_name="Podmix",
    )
    job = AnalysisJobResponse(
        job_id=uuid4(),
        status="succeeded",
        analysis_id=uuid4(),
        message="done",
    )
    result = AnalysisResultResponse(
        analysis_id=job.analysis_id,
        local_content_id=request.local_content_id,
        content_kind="podcast",
        tracklist_status="not_found",
        timestamp_status="none",
        match_score=0.0,
        evidence=["persisted"],
        tracks=[],
    )

    repository.save(request=request, job=job, result=result)

    reopened = SqliteAnalysisRepository(database_path)
    assert reopened.get_job(job.job_id) == job
    assert reopened.get_result(job.analysis_id) == result
