"""Import RSS/Atom minimal avec protections SSRF."""

from __future__ import annotations

import ipaddress
import socket
import xml.etree.ElementTree as ET
from urllib.parse import urlparse, urlunparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

try:
    from .tracklist import align_tracklist, parse_tracklist
except ImportError:
    from tracklist import align_tracklist, parse_tracklist


def _text(node: ET.Element, names: tuple[str, ...]) -> str:
    for child in node.iter():
        if child.tag.split("}")[-1] in names and child.text:
            return child.text.strip()
    return ""


def _safe_url(value: str) -> str:
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Le flux doit utiliser une URL HTTPS publique")
    for result in socket.getaddrinfo(parsed.hostname, parsed.port or 443):
        if not ipaddress.ip_address(result[4][0]).is_global:
            raise ValueError("Adresse privée ou locale interdite")
    return value


class _SafeRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return super().redirect_request(
            request, file_pointer, code, message, headers, _safe_url(new_url)
        )


def import_feed(url: str, kind: str = "podcast", limit: int = 100) -> dict:
    if kind not in {"podcast", "show"}:
        raise ValueError("Type de flux invalide")
    safe_url = _safe_url(url)
    request = Request(safe_url, headers={"User-Agent": "Podmix/2.0"})
    with build_opener(_SafeRedirects()).open(request, timeout=12) as response:
        payload = response.read(5_000_001)
    if len(payload) > 5_000_000:
        raise ValueError("Flux trop volumineux")
    root = ET.fromstring(payload)
    channel = next((node for node in root.iter() if node.tag.split("}")[-1] in {"channel", "feed"}), root)
    image = ""
    for node in channel.iter():
        if node.tag.split("}")[-1] in {"image", "logo"}:
            image = node.attrib.get("href") or _text(node, ("url",)) or (node.text or "").strip()
            if image:
                break
    episode_limit = min(500, max(1, int(limit)))
    episodes = []
    for index, item in enumerate(node for node in channel.iter() if node.tag.split("}")[-1] in {"item", "entry"}):
        enclosure = next((child for child in item.iter() if child.tag.split("}")[-1] == "enclosure" and child.attrib.get("url")), None)
        audio_url = enclosure.attrib["url"] if enclosure is not None else ""
        if not audio_url:
            link = next((child for child in item.iter() if child.tag.split("}")[-1] == "link" and child.attrib.get("rel") == "enclosure"), None)
            audio_url = link.attrib.get("href", "") if link is not None else ""
        audio_url = _secure_known_audio_url(audio_url)
        description = _text(item, ("description", "summary", "content"))
        duration = _text(item, ("duration",))
        candidates = parse_tracklist(description, structured_only=True)
        tracks = align_tracklist(candidates, [], _duration_seconds(duration)) if len(candidates) >= 2 else []
        for track in tracks:
            track["evidence"] = ["Tracklist publiée dans la description RSS", *track.get("evidence", [])]
        episodes.append({
            "id": _text(item, ("guid", "id")) or f"{safe_url}#{index}",
            "title": _text(item, ("title",)) or f"Épisode {index + 1}",
            "description": description,
            "publishedAt": _text(item, ("pubDate", "published", "updated")),
            "duration": duration,
            "audioUrl": audio_url,
            "artworkUrl": image,
            "tracks": tracks,
        })
        if len(episodes) >= episode_limit:
            break
    return {
        "id": safe_url, "kind": kind,
        "title": _text(channel, ("title",)) or urlparse(safe_url).hostname,
        "description": _text(channel, ("description", "subtitle")),
        "artworkUrl": image, "feedUrl": safe_url, "episodes": episodes,
    }
def _secure_known_audio_url(value: str) -> str:
    parsed = urlparse(value)
    if parsed.scheme == "http" and parsed.hostname == "audio.thisisdistorted.com":
        return urlunparse(parsed._replace(scheme="https"))
    return value


def _duration_seconds(value: str) -> float | None:
    parts = value.strip().split(":")
    try:
        numbers = [int(part) for part in parts]
    except ValueError:
        return None
    if len(numbers) == 3:
        return float(numbers[0] * 3600 + numbers[1] * 60 + numbers[2])
    if len(numbers) == 2:
        return float(numbers[0] * 60 + numbers[1])
    if len(numbers) == 1 and numbers[0] > 0:
        return float(numbers[0])
    return None
