import type { CatalogSource, DetectionJob, DjSearchResult, LiveSetDetails, LiveSetSearchResult, LiveSetTrack, PodcastSearchResult } from './domain'
import { Capacitor } from '@capacitor/core'

const API_TIMEOUT_MS = 45_000

async function apiFetch(input: RequestInfo | URL, init: RequestInit = {}, timeoutMs = API_TIMEOUT_MS) {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), timeoutMs)
  try {
    return await fetch(input, { ...init, signal: controller.signal })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new Error('Le serveur ne répond pas dans le délai prévu')
    }
    throw error
  } finally {
    window.clearTimeout(timeout)
  }
}

export function getApiUrl(): string {
  if (import.meta.env.VITE_API_URL) {
    return import.meta.env.VITE_API_URL.replace(/\/+$/, '')
  }
  const defaultUrl = Capacitor.isNativePlatform() ? 'http://10.0.2.2:8099' : 'http://localhost:8099'
  return (localStorage.getItem('podmix-api-url') || defaultUrl).replace(/\/+$/, '')
}

export function setApiUrl(value: string): string {
  const normalized = value.trim().replace(/\/+$/, '')
  const parsed = new URL(normalized)
  if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error('Utilisez une adresse HTTP ou HTTPS')
  if (!parsed.hostname) throw new Error('Adresse serveur invalide')
  localStorage.setItem('podmix-api-url', normalized)
  return normalized
}

export async function testApi(): Promise<{ status: string; service: string; mode: string }> {
  const response = await apiFetch(`${getApiUrl()}/health`)
  if (!response.ok) throw new Error(`Serveur indisponible (${response.status})`)
  return response.json()
}

export async function createBoseCastSession(
  url: string,
  title: string,
  positionSeconds = 0,
  durationSeconds = 0,
): Promise<{ id: string; relayUrl: string; lanRelayUrl?: string; expiresAt: string; startSeconds: number }> {
  const response = await apiFetch(`${getApiUrl()}/v1/cast/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url, title, positionSeconds, durationSeconds }),
  })
  const payload = await response.json().catch(() => ({})) as {
    id?: string
    relayUrl?: string
    lanRelayUrl?: string
    expiresAt?: string
    startSeconds?: number
    message?: string
  }
  if (!response.ok || !payload.id || !payload.relayUrl || !payload.expiresAt) {
    throw new Error(payload.message ?? `Relais Bose indisponible (${response.status})`)
  }
  return {
    id: payload.id,
    relayUrl: payload.relayUrl,
    lanRelayUrl: payload.lanRelayUrl,
    expiresAt: payload.expiresAt,
    startSeconds: payload.startSeconds ?? 0,
  }
}

export type ShareRequest = {
  sourceKind: 'podcast' | 'show' | 'dj'
  sourceTitle: string
  episodeTitle: string
  artist: string
  trackTitle: string
  sourceUrl: string
  audioUrl?: string
  artworkUrl?: string
  spotifyUrl?: string
  deezerUrl?: string
  startSeconds: number
  endSeconds?: number
}

export async function createSharePage(payload: ShareRequest): Promise<{ id: string; shareUrl: string; expiresAt: string }> {
  const response = await apiFetch(`${getApiUrl()}/v1/shares`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  const body = await response.json().catch(() => ({})) as { id?: string; shareUrl?: string; expiresAt?: string; message?: string }
  if (!response.ok || !body.id || !body.shareUrl || !body.expiresAt) {
    throw new Error(body.message ?? `Partage indisponible (${response.status})`)
  }
  return { id: body.id, shareUrl: body.shareUrl, expiresAt: body.expiresAt }
}

export async function importRssFeed(url: string, kind: 'podcast' | 'show' = 'podcast', limit = 100): Promise<CatalogSource> {
  const response = await apiFetch(`${getApiUrl()}/v1/catalog/rss`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url, kind, limit }),
  })
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.message ?? 'Import du flux impossible')
  return payload
}

export async function searchRadios(query: string): Promise<CatalogSource[]> {
  const response = await apiFetch(`${getApiUrl()}/v1/catalog/radios?q=${encodeURIComponent(query)}`)
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.message ?? 'Annuaire radio indisponible')
  return payload.items
}

export async function searchPodcasts(query: string): Promise<PodcastSearchResult[]> {
  // iTunes et beaucoup de flux RSS ne permettent pas les appels cross-origin.
  // Le serveur centralise donc ces accès, ce qui fonctionne aussi dans WebView.
  const response = await apiFetch(`${getApiUrl()}/v1/catalog/podcasts?q=${encodeURIComponent(query)}`)
  const payload = await response.json().catch(() => ({})) as { items?: PodcastSearchResult[]; message?: string }
  if (!response.ok) throw new Error(payload.message ?? 'Annuaire podcasts indisponible')
  return payload.items ?? []
}

export async function importDjSet(url: string): Promise<CatalogSource> {
  const response = await apiFetch(`${getApiUrl()}/v1/catalog/dj`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url }),
  })
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.message ?? 'Import du DJ set impossible')
  return payload
}

export async function searchDjSets(query: string): Promise<DjSearchResult[]> {
  const response = await apiFetch(`${getApiUrl()}/v1/catalog/dj?q=${encodeURIComponent(query)}`)
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.message ?? 'Recherche DJ indisponible')
  return payload.items
}

/** Recherche dédiée aux live sets : aucune donnée podcast ni job d'analyse. */
export async function searchLiveSets(query: string, limit = 24): Promise<LiveSetSearchResult[]> {
  const response = await apiFetch(`${getApiUrl()}/v1/live-sets/search?q=${encodeURIComponent(query)}&limit=${encodeURIComponent(limit)}`)
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.message ?? 'Recherche de live sets indisponible')
  return payload.items ?? []
}

export function liveSetStreamUrl(url: string) {
  return `${getApiUrl()}/v1/live-sets/stream?url=${encodeURIComponent(url)}`
}

export async function resolveLiveSet(url: string): Promise<Partial<LiveSetDetails>> {
  const response = await apiFetch(`${getApiUrl()}/v1/live-sets/resolve`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url }),
  })
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.message ?? 'Lecture du live set indisponible')
  // Les URL audio YouTube/SoundCloud sont éphémères et certaines demandent
  // les en-têtes du client de résolution. La lecture passe donc par le relais
  // DJ du serveur, sans toucher au pipeline RSS/podcast.
  return {
    ...payload,
    audioUrl: liveSetStreamUrl(url),
  }
}

export async function resolveLiveSetTracklist(url: string, title: string, text = ''): Promise<{ tracks: LiveSetTrack[]; origin: string; sourceUrl: string }> {
  const response = await apiFetch(`${getApiUrl()}/v1/live-sets/tracklist`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url, title, text }),
  })
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.message ?? 'Tracklist DJ indisponible')
  return payload
}

export async function findTrackArtwork(
  tracks: Array<{ key: string; artist: string; title: string }>,
): Promise<Array<{ key: string; artworkUrl?: string }>> {
  const response = await apiFetch(`${getApiUrl()}/v1/catalog/artwork`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tracks: tracks.slice(0, 20) }),
  })
  const payload = await response.json() as {
    items?: Array<{ key: string; artworkUrl?: string }>
    message?: string
  }
  if (!response.ok) throw new Error(payload.message ?? 'Pochettes indisponibles')
  return payload.items ?? []
}

export async function findTrackLinks(
  tracks: Array<{ key: string; artist: string; title: string }>,
): Promise<Array<{ key: string; artworkUrl?: string; deezerUrl?: string; spotifyUrl?: string }>> {
  const response = await apiFetch(`${getApiUrl()}/v1/catalog/links`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tracks: tracks.slice(0, 10) }),
  })
  const payload = await response.json() as {
    items?: Array<{ key: string; artworkUrl?: string; deezerUrl?: string; spotifyUrl?: string }>
    message?: string
  }
  if (!response.ok) throw new Error(payload.message ?? 'Liens musicaux indisponibles')
  return payload.items ?? []
}

export async function getDetectionJob(jobId: string): Promise<DetectionJob> {
  const response = await apiFetch(`${getApiUrl()}/v1/detection-jobs/${jobId}`)
  if (!response.ok) throw new Error(`État de l’analyse indisponible (${response.status})`)
  return response.json() as Promise<DetectionJob>
}

export async function cancelDetectionJob(jobId: string): Promise<DetectionJob> {
  const response = await apiFetch(`${getApiUrl()}/v1/detection-jobs/${jobId}`, { method: 'DELETE' })
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.message ?? `Annulation impossible (${response.status})`)
  return payload as DetectionJob
}

export async function findDetectionJob(requestKey: string): Promise<DetectionJob | undefined> {
  const response = await apiFetch(`${getApiUrl()}/v1/detection-jobs?requestKey=${encodeURIComponent(requestKey)}`)
  if (response.status === 404) return undefined
  if (!response.ok) throw new Error(`Recherche de l’analyse indisponible (${response.status})`)
  return response.json() as Promise<DetectionJob>
}

export async function createEpisodeAnalysisJob(
  source: CatalogSource,
  episode: CatalogSource['episodes'][number],
  force = false,
): Promise<DetectionJob> {
  const externalTracklists = source.kind === 'dj' || source.musical === true
  const durationParts = episode.duration.split(':').map(Number)
  const durationSeconds = durationParts.every(Number.isFinite)
    ? durationParts.reduce((total, value) => total * 60 + value, 0)
    : 0
  const response = await apiFetch(`${getApiUrl()}/v1/episode-analysis`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      episodeId: episode.id,
      title: episode.title,
      description: episode.description,
      durationSeconds,
      feedUrl: source.feedUrl,
      sourceUrl: episode.sourceUrl,
      audioUrl: episode.audioUrl,
      preferred1001Url: episode.webTracklistUrl,
      publishedAt: episode.publishedAt,
      requestKey: `episode:${episode.id}`,
      sourceKind: source.kind,
      musical: source.musical === true,
      enable1001: externalTracklists,
      enableExternalTracklists: externalTracklists,
      webQueue: true,
      rssTracks: episode.tracks ?? [],
      force,
    }),
  })
  const payload = await response.json() as DetectionJob & { message?: string }
  if (!response.ok) throw new Error(payload.message ?? `Planification impossible (${response.status})`)
  return payload
}

export function observeDetectionJob(
  jobId: string,
  onUpdate: (job: DetectionJob) => void,
  onError: () => void,
) {
  const events = new EventSource(`${getApiUrl()}/v1/detection-jobs/${jobId}/events`)
  let terminal = false
  let polling = false
  let pollTimer = 0
  let pollFailures = 0
  const finish = (job: DetectionJob) => {
    onUpdate(job)
    terminal = ['completed', 'failed', 'cancelled'].includes(job.status)
    if (terminal) {
      events.close()
      window.clearTimeout(pollTimer)
    }
  }
  const poll = async () => {
    if (terminal) return
    try {
      const job = await getDetectionJob(jobId)
      pollFailures = 0
      finish(job)
      if (!terminal) pollTimer = window.setTimeout(poll, 1000)
    } catch {
      onError()
      pollFailures += 1
      const retryDelay = Math.min(30_000, 1_000 * (2 ** Math.min(pollFailures, 5)))
      if (!terminal) pollTimer = window.setTimeout(poll, retryDelay)
    }
  }
  events.addEventListener('job.updated', (event) => {
    try {
      const job = JSON.parse((event as MessageEvent).data) as DetectionJob
      finish(job)
    } catch {
      onError()
      if (!polling && !terminal) {
        polling = true
        void poll()
      }
    }
  })
  events.onerror = () => {
    events.close()
    if (!polling && !terminal) {
      polling = true
      void poll()
    }
  }
  return () => {
    terminal = true
    events.close()
    window.clearTimeout(pollTimer)
  }
}

export async function alignTracklist(jobId: string, text: string, options: { timestampSource?: 'external' } = {}): Promise<DetectionJob> {
  const response = await apiFetch(`${getApiUrl()}/v1/detection-jobs/${jobId}/tracklist`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text, timestampSource: options.timestampSource }),
  })
  if (!response.ok) throw new Error(response.status === 422 ? 'Aucun morceau reconnu dans ce texte' : `Alignement impossible (${response.status})`)
  return response.json() as Promise<DetectionJob>
}

export async function claimWebTimestampJob(): Promise<{
  id: string
  episodeId: string
  title: string
  referenceFirstTrack?: { artist?: string; title?: string }
  webCandidates?: Array<{ url: string; title: string; domain: string; address?: string }>
  publishedLinks?: string[]
} | null> {
  const response = await apiFetch(`${getApiUrl()}/v1/web-timestamp-jobs/next`)
  if (!response.ok) throw new Error(`File Web indisponible (${response.status})`)
  const payload = await response.json()
  return payload?.id ? payload : null
}

export async function completeWebTimestampJob(jobId: string, sourceUrl: string, candidates: Array<{ artist: string; title: string; providedTime: number }>): Promise<DetectionJob> {
  const response = await apiFetch(`${getApiUrl()}/v1/web-timestamp-jobs/${jobId}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sourceUrl, candidates }),
  })
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.message ?? `Import Web impossible (${response.status})`)
  return payload as DetectionJob
}

export async function failWebTimestampJob(jobId: string, message: string): Promise<DetectionJob> {
  const response = await apiFetch(`${getApiUrl()}/v1/web-timestamp-jobs/${jobId}/failure`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message }),
  })
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.message ?? `Échec Web impossible à enregistrer (${response.status})`)
  return payload as DetectionJob
}
export async function discoverTracklist(jobId: string, url: string): Promise<{ tracks: DetectionJob['tracks']; candidateCount: number; message?: string }> {
  const response = await apiFetch(`${getApiUrl()}/v1/detection-jobs/${jobId}/discover`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url }),
  })
  const payload = await response.json() as { tracks: DetectionJob['tracks']; candidateCount: number; message?: string }
  if (!response.ok) throw new Error(payload.message ?? `Découverte impossible (${response.status})`)
  return payload
}

export async function discover1001Tracklist(jobId: string, value: string): Promise<{ tracks: DetectionJob['tracks']; candidateCount: number; source?: string; sourceUrl?: string; message?: string }> {
  const isUrl = /^https:\/\//i.test(value.trim())
  const response = await apiFetch(`${getApiUrl()}/v1/detection-jobs/${jobId}/discover-1001`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(isUrl ? { url: value.trim() } : { query: value.trim() }),
  })
  const payload = await response.json() as { tracks: DetectionJob['tracks']; candidateCount: number; source?: string; sourceUrl?: string; message?: string }
  if (!response.ok) throw new Error(payload.message ?? `Recherche de tracklist impossible (${response.status})`)
  return payload
}

export async function searchTracklistCandidates(query: string): Promise<Array<{ url: string; title: string; snippet: string; domain: string; address?: string }>> {
  const response = await apiFetch(`${getApiUrl()}/v1/tracklists/candidates?q=${encodeURIComponent(query)}&limit=8`)
  const payload = await response.json() as {
    results?: Array<{ url: string; title: string; snippet: string; domain: string; address?: string }>
    message?: string
  }
  if (!response.ok) throw new Error(payload.message ?? `Recherche web impossible (${response.status})`)
  return payload.results ?? []
}

export async function validateCatalogTrack(jobId: string, trackId: number): Promise<{ track: DetectionJob['tracks'][number]; accepted: boolean; artworkUrl?: string; deezerUrl?: string; spotifyUrl?: string }> {
  const response = await apiFetch(`${getApiUrl()}/v1/detection-jobs/${jobId}/tracks/${trackId}/validate`, { method: 'POST' })
  const payload = await response.json() as { track: DetectionJob['tracks'][number]; accepted: boolean; artworkUrl?: string; deezerUrl?: string; spotifyUrl?: string; message?: string }
  if (!response.ok) throw new Error(payload.message ?? `Catalogue indisponible (${response.status})`)
  return payload
}
