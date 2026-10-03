"""Extract safe tracklist/media links published inside an RSS description."""

from __future__ import annotations

import html
import ipaddress
import re
import socket
from urllib.parse import urljoin, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

from bs4 import BeautifulSoup

DIRECT_HOSTS = {
    "1001tracklists.com",
    "www.1001tracklists.com",
    "youtube.com",
    "www.youtube.com",
    "youtu.be",
    "soundcloud.com",
    "www.soundcloud.com",
    "mixcloud.com",
    "www.mixcloud.com",
}
LANDING_HOSTS = {
    "lnk.to",
    "www.lnk.to",
    "linktr.ee",
    "www.linktr.ee",
    "podlink.to",
    "www.podlink.to",
}
URL_PATTERN = re.compile(r"https://[^\s<>\"']+", re.IGNORECASE)


def _public_https_url(value: str, allowed_hosts: set[str]) -> str:
    cleaned = html.unescape(value).rstrip(".,;:!?)]}")
    parsed = urlparse(cleaned)
    host = (parsed.hostname or "").casefold()
    if parsed.scheme != "https" or host not in allowed_hosts or parsed.username or parsed.password:
        raise ValueError("Lien publié non autorisé")
    try:
        addresses = {
            ipaddress.ip_address(item[4][0])
            for item in socket.getaddrinfo(host, parsed.port or 443, type=socket.SOCK_STREAM)
        }
    except (socket.gaierror, ValueError) as error:
        raise ValueError("Hôte publié introuvable") from error
    if not addresses or any(not address.is_global for address in addresses):
        raise ValueError("Adresse publiée privée ou locale")
    return cleaned


def _description_urls(description: str) -> list[str]:
    soup = BeautifulSoup(description, "html.parser")
    values = [str(anchor.get("href") or "") for anchor in soup.select("a[href]")]
    values.extend(URL_PATTERN.findall(soup.get_text(" ")))
    unique: list[str] = []
    for value in values:
        cleaned = html.unescape(value).strip()
        if cleaned and cleaned not in unique:
            unique.append(cleaned)
    return unique


class _SafePublishedRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        safe_url = _public_https_url(new_url, DIRECT_HOSTS | LANDING_HOSTS)
        return super().redirect_request(request, file_pointer, code, message, headers, safe_url)


def _landing_targets(url: str) -> list[str]:
    safe_url = _public_https_url(url, LANDING_HOSTS)
    request = Request(
        safe_url,
        headers={"User-Agent": "Mozilla/5.0 Podmix/2.0", "Accept": "text/html"},
    )
    with build_opener(_SafePublishedRedirects()).open(request, timeout=12) as response:
        final_url = response.geturl()
        final_host = (urlparse(final_url).hostname or "").casefold()
        if final_host in DIRECT_HOSTS:
            return [_public_https_url(final_url, DIRECT_HOSTS)]
        _public_https_url(final_url, LANDING_HOSTS)
        payload = response.read(1_000_001)
        content_type = str(response.headers.get("Content-Type") or "")
    if len(payload) > 1_000_000 or "html" not in content_type.casefold():
        return []
    soup = BeautifulSoup(payload.decode("utf-8", errors="replace"), "html.parser")
    targets: list[str] = []
    for anchor in soup.select("a[href]"):
        candidate = urljoin(final_url, str(anchor.get("href") or ""))
        try:
            candidate = _public_https_url(candidate, DIRECT_HOSTS)
        except ValueError:
            continue
        if candidate not in targets:
            targets.append(candidate)
    return targets[:8]


def extract_published_links(description: str) -> list[str]:
    """Return direct media/tracklist URLs, resolving known smart-link pages."""
    discovered: list[str] = []
    for value in _description_urls(description)[:12]:
        host = (urlparse(value).hostname or "").casefold()
        try:
            if host in DIRECT_HOSTS:
                targets = [_public_https_url(value, DIRECT_HOSTS)]
            elif host in LANDING_HOSTS:
                targets = _landing_targets(value)
            else:
                continue
        except (OSError, ValueError):
            continue
        for target in targets:
            if target not in discovered:
                discovered.append(target)
        if len(discovered) >= 8:
            break
    return discovered[:8]
