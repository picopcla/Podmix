"""Adaptateur minimal de l'annuaire communautaire Radio Browser."""

from __future__ import annotations

import json
import socket
import ssl
import http.client
from urllib.parse import urlencode, quote
from urllib.request import Request, urlopen, HTTPSHandler, build_opener

_FALLBACK_HOST = "de1.api.radio-browser.info"


class _RadioBrowserConnection(http.client.HTTPSConnection):
    """Connecte à l'adresse IP radio-browser mais valide le certificat contre le hostname."""

    def __init__(self, host: str, server_hostname: str, **kwargs):
        self._server_hostname = server_hostname
        super().__init__(host, **kwargs)

    def connect(self) -> None:
        self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        context = self._context or ssl._create_default_https_context()
        self.sock = context.wrap_socket(self.sock, server_hostname=self._server_hostname)


class _RadioBrowserHandler(HTTPSHandler):
    """Ouvre HTTPS en résolvant l'adresse IP soi-même, avec SNI correct."""

    def __init__(self, server_hostname: str) -> None:
        self._server_hostname = server_hostname
        super().__init__(context=ssl.create_default_context())

    def https_open(self, req):
        return self.do_open(
            lambda host, **kwargs: _RadioBrowserConnection(host, self._server_hostname, **kwargs),
            req,
        )


def _pick_host() -> str:
    # Radio Browser recommande un DNS lookup sur 'all.api.radio-browser.info'.
    try:
        addresses = {a[4][0] for a in socket.getaddrinfo("all.api.radio-browser.info", None)}
    except (OSError, socket.gaierror):
        addresses = set()
    if not addresses:
        return _FALLBACK_HOST
    return sorted(addresses)[0]


def _fetch_stations(url: str, server_hostname: str) -> list[dict]:
    request = Request(url, headers={"User-Agent": "Podmix/2.0", "Host": server_hostname})
    opener = build_opener(_RadioBrowserHandler(server_hostname))
    with opener.open(request, timeout=10) as response:
        return json.load(response)


def _try_fetch_stations(query: str, limit: int, server_hostname: str) -> list[dict]:
    # Essaie chaque adresse IP retournée par 'all.api.radio-browser.info' puis le fallback.
    addresses: set[str] = set()
    try:
        addresses = {a[4][0] for a in socket.getaddrinfo("all.api.radio-browser.info", None)}
    except (OSError, socket.gaierror):
        pass
    if not addresses:
        try:
            addresses = {a[4][0] for a in socket.getaddrinfo(server_hostname, None)}
        except (OSError, socket.gaierror):
            pass
    term = quote(query.strip(), safe="")
    search_params = urlencode({
        "name": query.strip(),
        "hidebroken": "true",
        "order": "votes",
        "reverse": "true",
        "limit": min(limit, 50),
    })
    last_error: Exception | None = None
    for host in sorted(addresses):
        for path in [f"/json/stations/byname/{term}?{search_params}", f"/json/stations/search?{search_params}"]:
            url = f"https://{host}{path}"
            try:
                return _fetch_stations(url, server_hostname)
            except Exception as error:
                last_error = error
    if last_error:
        raise last_error
    return []


def search_radios(query: str, limit: int = 30) -> list[dict]:
    term = query.strip()
    if len(term) < 2:
        return []
    # L'endpoint /stations/byname est plus stable que /search lorsque Radio Browser
    # renvoie "no available server" (503) sur ses requêtes par mot-clé. On teste
    # chaque IP du DNS round-robin et le fallback.
    stations = _try_fetch_stations(term, limit, _FALLBACK_HOST)
    return [{
        "id": station.get("stationuuid", ""),
        "kind": "radio",
        "title": station.get("name", "").strip(),
        "description": " · ".join(value for value in (station.get("country"), station.get("tags", "").split(",")[0]) if value),
        "artworkUrl": station.get("favicon") or "",
        "streamUrl": station.get("url_resolved") or station.get("url") or "",
        "episodes": [],
    } for station in stations if station.get("stationuuid") and (station.get("url_resolved") or station.get("url"))]
