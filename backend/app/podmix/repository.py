from __future__ import annotations

import json
from pathlib import Path
from typing import Protocol
from uuid import UUID

from app.core.config import load_settings
from app.podmix.schemas import (
    AnalysisJobResponse,
    AnalysisRequest,
    AnalysisResultResponse,
    AnalyzedTrack,
)
from app.storage.database import initialize_database, open_connection


class AnalysisRepository(Protocol):
    def save(
        self,
        request: AnalysisRequest,
        job: AnalysisJobResponse,
        result: AnalysisResultResponse,
    ) -> None: ...

    def get_job(self, job_id: UUID) -> AnalysisJobResponse | None: ...

    def get_result(self, analysis_id: UUID) -> AnalysisResultResponse | None: ...


class SqliteAnalysisRepository:
    """Persistent repository for job/result state."""

    def __init__(self, database_path: Path) -> None:
        self._database_path = database_path
        initialize_database(self._database_path)

    def save(
        self,
        request: AnalysisRequest,
        job: AnalysisJobResponse,
        result: AnalysisResultResponse,
    ) -> None:
        with open_connection(self._database_path) as connection:
            connection.execute(
                """
                INSERT OR REPLACE INTO analysis_jobs (
                    job_id, status, analysis_id, message, content_kind,
                    request_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))
                """,
                (
                    str(job.job_id),
                    job.status,
                    str(job.analysis_id) if job.analysis_id else None,
                    job.message,
                    result.content_kind,
                    request.model_dump_json(),
                ),
            )
            connection.execute(
                """
                INSERT OR REPLACE INTO analysis_results (
                    analysis_id, local_content_id, content_kind,
                    tracklist_status, timestamp_status, match_score,
                    evidence_json, tracks_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))
                """,
                (
                    str(result.analysis_id),
                    result.local_content_id,
                    result.content_kind,
                    result.tracklist_status,
                    result.timestamp_status,
                    result.match_score,
                    json.dumps(result.evidence),
                    json.dumps([track.model_dump(mode="json") for track in result.tracks]),
                ),
            )

    def get_job(self, job_id: UUID) -> AnalysisJobResponse | None:
        with open_connection(self._database_path) as connection:
            row = connection.execute(
                """
                SELECT job_id, status, analysis_id, message
                FROM analysis_jobs
                WHERE job_id = ?
                """,
                (str(job_id),),
            ).fetchone()
        if row is None:
            return None
        return AnalysisJobResponse(
            job_id=UUID(row["job_id"]),
            status=row["status"],
            analysis_id=UUID(row["analysis_id"]) if row["analysis_id"] else None,
            message=row["message"],
        )

    def get_result(self, analysis_id: UUID) -> AnalysisResultResponse | None:
        with open_connection(self._database_path) as connection:
            row = connection.execute(
                """
                SELECT analysis_id, local_content_id, content_kind,
                       tracklist_status, timestamp_status, match_score,
                       evidence_json, tracks_json
                FROM analysis_results
                WHERE analysis_id = ?
                """,
                (str(analysis_id),),
            ).fetchone()
        if row is None:
            return None
        return AnalysisResultResponse(
            analysis_id=UUID(row["analysis_id"]),
            local_content_id=row["local_content_id"],
            content_kind=row["content_kind"],
            tracklist_status=row["tracklist_status"],
            timestamp_status=row["timestamp_status"],
            match_score=row["match_score"],
            evidence=json.loads(row["evidence_json"]),
            tracks=[AnalyzedTrack(**item) for item in json.loads(row["tracks_json"])],
        )


def build_analysis_repository() -> SqliteAnalysisRepository:
    return SqliteAnalysisRepository(load_settings().database_path)
