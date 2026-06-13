from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator


SCHEMA = """
CREATE TABLE IF NOT EXISTS analysis_jobs (
    job_id TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    analysis_id TEXT,
    message TEXT,
    content_kind TEXT NOT NULL,
    request_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS analysis_results (
    analysis_id TEXT PRIMARY KEY,
    local_content_id INTEGER NOT NULL,
    content_kind TEXT NOT NULL,
    tracklist_status TEXT NOT NULL,
    timestamp_status TEXT NOT NULL,
    match_score REAL NOT NULL,
    evidence_json TEXT NOT NULL,
    tracks_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
"""


def _connect(database_path: Path) -> sqlite3.Connection:
    database_path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(database_path)
    connection.row_factory = sqlite3.Row
    return connection


def initialize_database(database_path: Path) -> None:
    with _connect(database_path) as connection:
        connection.executescript(SCHEMA)
        connection.commit()


@contextmanager
def open_connection(database_path: Path) -> Iterator[sqlite3.Connection]:
    connection = _connect(database_path)
    try:
        yield connection
        connection.commit()
    finally:
        connection.close()

