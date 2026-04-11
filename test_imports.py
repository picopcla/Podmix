#!/usr/bin/env python3
"""Test des imports Python nécessaires pour le backend PodMix."""

import sys

print(f"Python {sys.version}")

modules = [
    "fastapi",
    "uvicorn",
    "httpx",
    "sqlalchemy",
    "alembic",
    "feedparser",
    "bs4",
    "youtube_search",
    "celery",
    "redis",
]

for module in modules:
    try:
        __import__(module)
        print(f"✓ {module}")
    except ImportError as e:
        print(f"✗ {module}: {e}")