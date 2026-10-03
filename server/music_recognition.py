"""Reconnaissance musicale manuelle via AudD.

Le jeton reste exclusivement dans ``PODMIX_AUDD_API_TOKEN`` sur le serveur.
L'application n'envoie qu'un court extrait WAV capturé après une action
explicite de l'utilisateur.
"""

from __future__ import annotations

import json
import os
import uuid
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


MAX_SAMPLE_BYTES = 650 * 1024
AUDD_URL = "https://api.audd.io/"


class MusicRecognitionError(ValueError):
    pass


def _artwork_url(candidate: object) -> str:
    """Return the first usable artwork URL from an AudD provider payload."""
    if not isinstance(candidate, dict):
        return ""
    images = candidate.get("images")
    if isinstance(images, list):
        for image in images:
            if isinstance(image, dict) and image.get("url"):
                return str(image["url"])
    for key in ("cover_big", "cover", "url"):
        if candidate.get(key):
            return str(candidate[key])
    return ""


def _multipart_form(fields: dict[str, str], sample: bytes) -> tuple[bytes, str]:
    boundary = f"----PodmixAudD{uuid.uuid4().hex}"
    chunks: list[bytes] = []
    for name, value in fields.items():
        chunks.extend((
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
            str(value).encode(),
            b"\r\n",
        ))
    chunks.extend((
        f"--{boundary}\r\n".encode(),
        b'Content-Disposition: form-data; name="file"; filename="podmix-sample.wav"\r\n',
        b"Content-Type: audio/wav\r\n\r\n",
        sample,
        b"\r\n",
        f"--{boundary}--\r\n".encode(),
    ))
    return b"".join(chunks), boundary


def recognize_music(sample: bytes) -> dict[str, object]:
    """Envoie un extrait court à AudD et normalise le résultat pour Podmix."""
    token = os.environ.get("PODMIX_AUDD_API_TOKEN", "").strip()
    if not token:
        raise MusicRecognitionError("Reconnaissance musicale non configurée")
    if not sample or len(sample) < 2_000:
        raise MusicRecognitionError("Extrait audio trop court")
    if len(sample) > MAX_SAMPLE_BYTES:
        raise MusicRecognitionError("Extrait audio trop volumineux")

    body, boundary = _multipart_form({
        "api_token": token,
        "return": "spotify,deezer,apple_music",
    }, sample)
    request = Request(AUDD_URL, data=body, method="POST", headers={
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "Content-Length": str(len(body)),
        "User-Agent": "Podmix/1.0 music-recognition",
    })
    try:
        with urlopen(request, timeout=25) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (HTTPError, URLError, TimeoutError) as error:
        raise MusicRecognitionError("Service de reconnaissance indisponible") from error
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise MusicRecognitionError("Réponse de reconnaissance invalide") from error

    if payload.get("status") != "success":
        error = payload.get("error")
        if isinstance(error, dict):
            message = error.get("error_message") or error.get("message")
        else:
            message = error
        raise MusicRecognitionError(str(message or "Reconnaissance impossible"))
    match = payload.get("result")
    if not isinstance(match, dict):
        return {"matched": False}

    spotify = match.get("spotify") if isinstance(match.get("spotify"), dict) else {}
    deezer = match.get("deezer") if isinstance(match.get("deezer"), dict) else {}
    apple_music = match.get("apple_music") if isinstance(match.get("apple_music"), dict) else {}
    artwork = ""
    for candidate in (spotify.get("album", {}), deezer.get("album", {}), apple_music.get("artwork", {})):
        artwork = _artwork_url(candidate)
        if artwork:
            break
    return {
        "matched": True,
        "title": str(match.get("title") or ""),
        "artist": str(match.get("artist") or ""),
        "album": str(match.get("album") or ""),
        "artworkUrl": artwork,
        "spotifyUrl": str(spotify.get("external_urls", {}).get("spotify", "") if isinstance(spotify.get("external_urls"), dict) else spotify.get("url", "")),
        "deezerUrl": str(deezer.get("link") or deezer.get("url") or ""),
        "appleMusicUrl": str(apple_music.get("url") or ""),
    }
