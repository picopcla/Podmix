"""Empreinte acoustique optionnelle avec Chromaprint et AcoustID."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path


def identify_segment(audio_path: Path, start_seconds: float, length_seconds: int = 90) -> dict:
    api_key = os.environ.get("ACOUSTID_API_KEY", "").strip()
    if not api_key:
        return {"available": False, "message": "ACOUSTID_API_KEY n’est pas configurée sur le serveur"}
    ffmpeg, fpcalc = shutil.which("ffmpeg"), shutil.which("fpcalc")
    if not ffmpeg or not fpcalc:
        raise RuntimeError("FFmpeg et fpcalc sont requis pour l’identification acoustique")

    with tempfile.NamedTemporaryFile(suffix=".wav") as excerpt:
        decode = subprocess.run(
            [
                ffmpeg, "-v", "error", "-y", "-ss", str(max(0, start_seconds + 20)),
                "-t", str(length_seconds), "-i", str(audio_path), "-ac", "1", "-ar", "44100",
                "-c:a", "pcm_s16le", excerpt.name,
            ],
            capture_output=True, text=True, timeout=180, check=False,
        )
        if decode.returncode != 0:
            raise RuntimeError(f"Extraction FFmpeg impossible : {decode.stderr[-300:]}")
        fingerprint_process = subprocess.run(
            [fpcalc, "-json", "-length", str(length_seconds), excerpt.name],
            capture_output=True, text=True, timeout=60, check=False,
        )
        if fingerprint_process.returncode != 0:
            raise RuntimeError(f"Chromaprint impossible : {fingerprint_process.stderr[-300:]}")
        fingerprint = json.loads(fingerprint_process.stdout)

    parameters = urllib.parse.urlencode({
        "client": api_key,
        "duration": int(fingerprint["duration"]),
        "fingerprint": fingerprint["fingerprint"],
        "meta": "recordings+recordingids",
        "format": "json",
    })
    request = urllib.request.Request(
        f"https://api.acoustid.org/v2/lookup?{parameters}",
        headers={"Accept": "application/json", "User-Agent": "PodmixStudio/0.2"},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        payload = json.loads(response.read().decode())
    matches = []
    for result in payload.get("results") or []:
        for recording in result.get("recordings") or []:
            artists = ", ".join(
                str(artist.get("name") or "")
                for artist in recording.get("artists") or []
                if artist.get("name")
            )
            matches.append({
                "score": round(float(result.get("score") or 0) * 100),
                "acoustid": str(result.get("id") or ""),
                "mbid": str(recording.get("id") or ""),
                "title": str(recording.get("title") or ""),
                "artist": artists,
            })
    matches.sort(key=lambda item: item["score"], reverse=True)
    return {"available": True, "matches": matches[:10], "bestMatch": matches[0] if matches else None}
