"""Validation and HTML rendering for public, timestamped Podmix shares.

The share service deliberately stores metadata only.  It never fetches, proxies,
or persists audio: recipients listen to the publisher's original URL.
"""

from __future__ import annotations

from html import escape
from urllib.parse import urlparse


class ShareValidationError(ValueError):
    """The submitted public-share metadata cannot be published safely."""


def public_https_url(value: object, field: str, required: bool = False, ignore_invalid: bool = False) -> str:
    cleaned = str(value or "").strip()
    if not cleaned:
        if required:
            raise ShareValidationError(f"{field} est obligatoire")
        return ""
    parsed = urlparse(cleaned)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        if ignore_invalid and not required:
            return ""
        raise ShareValidationError(f"{field} doit être une URL HTTPS publique")
    return cleaned


def text(value: object, field: str, limit: int, required: bool = False) -> str:
    cleaned = " ".join(str(value or "").split()).strip()
    if required and not cleaned:
        raise ShareValidationError(f"{field} est obligatoire")
    if len(cleaned) > limit:
        raise ShareValidationError(f"{field} est trop long")
    return cleaned


def seconds(value: object, field: str, maximum: float) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError) as error:
        raise ShareValidationError(f"{field} est invalide") from error
    if result < 0 or result > maximum:
        raise ShareValidationError(f"{field} est hors limites")
    return round(result, 3)


def normalize_share_payload(payload: object) -> dict:
    if not isinstance(payload, dict):
        raise ShareValidationError("Requête invalide")
    start_seconds = seconds(payload.get("startSeconds"), "startSeconds", 7 * 24 * 60 * 60)
    end_raw = payload.get("endSeconds")
    end_seconds = seconds(end_raw, "endSeconds", 7 * 24 * 60 * 60) if end_raw is not None else 0.0
    if end_seconds and end_seconds <= start_seconds + 1:
        raise ShareValidationError("La fin du passage doit suivre son début")
    if end_seconds and end_seconds - start_seconds > 20 * 60:
        raise ShareValidationError("Un passage partagé ne peut pas dépasser 20 minutes")
    source_kind = text(payload.get("sourceKind"), "sourceKind", 20, True)
    if source_kind not in {"podcast", "show", "dj"}:
        raise ShareValidationError("Type de source non partageable")
    source_url = public_https_url(payload.get("sourceUrl"), "sourceUrl", True)
    audio_url = public_https_url(payload.get("audioUrl"), "audioUrl")
    # A DJ set is never played through Podmix's own live-set relay on the
    # public page.  The outbound source link remains the legal fallback.
    if source_kind == "dj":
        audio_url = ""
    return {
        "source_kind": source_kind,
        "source_title": text(payload.get("sourceTitle"), "sourceTitle", 300, True),
        "episode_title": text(payload.get("episodeTitle"), "episodeTitle", 300),
        "artist": text(payload.get("artist"), "artist", 300, True),
        "track_title": text(payload.get("trackTitle"), "trackTitle", 300, True),
        "source_url": source_url,
        "audio_url": audio_url,
        # Optional outbound artwork/catalog links must not make an otherwise
        # valid share unusable when an old feed still exposes HTTP URLs.
        "artwork_url": public_https_url(payload.get("artworkUrl"), "artworkUrl", ignore_invalid=True),
        "spotify_url": public_https_url(payload.get("spotifyUrl"), "spotifyUrl", ignore_invalid=True),
        "deezer_url": public_https_url(payload.get("deezerUrl"), "deezerUrl", ignore_invalid=True),
        "start_seconds": start_seconds,
        "end_seconds": end_seconds,
    }


def format_time(value: float) -> str:
    total = max(0, int(value))
    hours, remainder = divmod(total, 3600)
    minutes, seconds_value = divmod(remainder, 60)
    return f"{hours}:{minutes:02d}:{seconds_value:02d}" if hours else f"{minutes}:{seconds_value:02d}"


def render_share_page(share: dict, canonical_url: str, brand_image_url: str) -> bytes:
    artist = escape(share["artist"])
    track_title = escape(share["track_title"])
    source_title = escape(share["source_title"])
    episode_title = escape(share.get("episode_title") or "")
    start_label = format_time(float(share["start_seconds"]))
    description = f"Entendu dans {share['source_title']} à {start_label}"
    image_url = share.get("artwork_url") or brand_image_url
    source_url = escape(share["source_url"], quote=True)
    audio_url = escape(share.get("audio_url") or "", quote=True)
    spotify_url = escape(share.get("spotify_url") or "", quote=True)
    deezer_url = escape(share.get("deezer_url") or "", quote=True)
    canonical = escape(canonical_url, quote=True)
    title = f"{share['artist']} — {share['track_title']} | Podmix"
    safe_title = escape(title)
    safe_description = escape(description)
    audio_markup = ""
    if audio_url:
        end_seconds = float(share.get("end_seconds") or 0)
        audio_markup = f'''<section class="listen"><audio controls preload="metadata" src="{audio_url}">Votre navigateur ne peut pas lire cet audio.</audio><p>La lecture commence à {start_label} et s’arrête à la fin du passage identifié.</p></section><script>const a=document.querySelector('audio'),s={float(share['start_seconds'])},e={end_seconds};let positioned=false;a.addEventListener('loadedmetadata',()=>{{if(!positioned){{positioned=true;a.currentTime=s}}}});a.addEventListener('timeupdate',()=>{{if(e&&a.currentTime>=e){{a.pause();a.currentTime=s}}}});</script>'''
    links = f'<a class="primary" href="{source_url}" rel="noreferrer">Ouvrir la publication originale</a>'
    if spotify_url:
        links += f'<a href="{spotify_url}" rel="noreferrer">Spotify</a>'
    if deezer_url:
        links += f'<a href="{deezer_url}" rel="noreferrer">Deezer</a>'
    episode_markup = f"<p class=episode>{episode_title}</p>" if episode_title else ""
    page = f'''<!doctype html><html lang="fr" prefix="og: https://ogp.me/ns#"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>{safe_title}</title><meta name="description" content="{safe_description}"><meta property="og:title" content="{safe_title}"><meta property="og:description" content="{safe_description}"><meta property="og:type" content="website"><meta property="og:url" content="{canonical}"><meta property="og:image" content="{escape(image_url, quote=True)}"><meta name="robots" content="noindex,nofollow"><meta name="referrer" content="no-referrer"><style>body{{margin:0;background:#292a30;color:#f6f2ea;font:16px system-ui,sans-serif}}main{{max-width:540px;margin:0 auto;padding:36px 20px}}img{{width:104px;height:104px;border-radius:14px;object-fit:cover;background:#383941}}h1{{font-size:27px;line-height:1.12;margin:18px 0 5px}}.source,.episode,p{{color:#d1d0cb}}.source{{margin:0;font-size:15px}}.episode{{font-size:13px}}.listen{{margin:28px 0;padding:18px;border:1px solid #555761;border-radius:13px;background:#34353b}}audio{{width:100%}}.listen p{{font-size:13px;margin:12px 0 0}}.links{{display:flex;flex-wrap:wrap;gap:9px}}a{{border:1px solid #747680;border-radius:8px;padding:10px 13px;color:#f6f2ea;text-decoration:none;font-weight:650}}a.primary{{background:#a8d7bd;color:#1c2220;border-color:#a8d7bd}}footer{{margin-top:34px;color:#b9b8b1;font-size:12px;line-height:1.5}}</style></head><body><main><img src="{escape(image_url, quote=True)}" alt="Pochette de {track_title}"><h1>{track_title}</h1><p class="source">{artist} · entendu à {start_label} dans {source_title}</p>{episode_markup}{audio_markup}<div class="links">{links}</div><footer>Passage identifié par Podmix. L’audio reste diffusé depuis sa publication originale ; Podmix ne conserve aucune copie.</footer></main></body></html>'''
    return page.encode("utf-8")
