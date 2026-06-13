from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    database_path: Path
    public_base_url: str


def load_settings() -> Settings:
    return Settings(
        database_path=Path(
            os.getenv("PODMIX_BACKEND_DB_PATH", "/tmp/podmix-backend/podmix.db")
        ),
        public_base_url=os.getenv(
            "PODMIX_PUBLIC_BASE_URL",
            "https://podmix.mb4.fr",
        ),
    )

