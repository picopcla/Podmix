import type { CatalogSource, DetectionJob, DjSearchResult, PodcastSearchResult } from './domain'
import { Capacitor } from '@capacitor/core'

const DEFAULT_API_URL = import.meta.env.VITE_API_URL
  ?? (Capacitor.isNativePlatform() ? 'http://10.0.2.2:8099' : 'http://localhost:8099')

export function getApiUrl(): string {
  return (localStorage.getItem('podmix-api-url') || DEFAULT_API_URL).replace(/\/+$/, '')
}

export function setApiUrl(value: string): string {
  const normalized = value.trim().replace(/\/+$/, '')
  const parsed = new URL(normalized)
  if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error('Utilisez une adresse HTTP ou HTTPS')
  if (!parsed.hostname) throw new Error('Adresse serveur invalide')
  localStorage.setItem('podmix-api-url', normalized)
  return normalized
}

export async function testApi(): Promise<{ status: string; service: string; engine: string }> {
  const response = await fetch(`${getApiUrl()}/health`)
  if (!response.ok) throw new Error(`Serveur indisponible (${response.status})`)
  return response.json()
}

export async function createBoseCastSession(
  url: string,
  title: string,
  positionSeconds = 0,
  durationSeconds = 0,
): Promise<{ id: string; relayUrl: string; expiresAt: string; startSeconds: number }> {
  const response = await fetch(`${getApiUrl()}/v1/cast/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url, title, positionSeconds, durationSeconds }),
  })
  const payload = await response.json().catch(() => ({})) as {
    id?: string
    relayUrl?: string
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
    expiresAt: payload.expiresAt,
    startSeconds: payload.startSeconds ?? 0,
  }
}

export async function importRssFeed(url: string, kind: 'podcast' | 'show' = 'podcast', limit = 100): Promise<CatalogSource> {
  const response = await fetch(`${getApiUrl()}/v1/catalog/rss`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url, kind, limit }),
  })
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.message ?? 'Import du flux impossible')
  return payload
}

export async function searchRadios(query: string): Promise<CatalogSource[]> {
  const response = await fetch(`${getApiUrl()}/v1/catalog/radios?q=${encodeURIComponent(query)}`)
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.message ?? 'Annuaire radio indisponible')
  return payload.items
}

export async function searchPodcasts(query: string): Promise<PodcastSearchResult[]> {
  const response = await fetch(`${getApiUrl()}/v1/catalog/podcasts?q=${encodeURIComponent(query)}`)
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.message ?? 'Annuaire podcasts indisponible')
  return payload.items
}

export async function importDjSet(url: string): Promise<CatalogSource> {
  const response = await fetch(`${getApiUrl()}/v1/catalog/dj`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url }),
  })
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.message ?? 'Import du DJ set impossible')
  return payload
}

export async function searchDjSets(query: string): Promise<DjSearchResult[]> {
  const response = await fetch(`${getApiUrl()}/v1/catalog/dj?q=${encodeURIComponent(query)}`)
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.message ?? 'Recherche DJ indisponible')
  return payload.items
}

export async function findTrackArtwork(
  tracks: Array<{ key: string; artist: string; title: string }>,
): Promise<Array<{ key: string; artworkUrl?: string }>> {
  const response = await fetch(`${getApiUrl()}/v1/catalog/artwork`, {
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
  const response = await fetch(`${getApiUrl()}/v1/catalog/links`, {
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

export async function uploadAudio(file: File): Promise<{ id: string }> {
  const response = await fetch(`${getApiUrl()}/v1/uploads`, {
    method: 'POST',
    headers: {
      'Content-Type': file.type || 'application/octet-stream',
      'X-Filename': encodeURIComponent(file.name),
    },
    body: file,
  })
  if (!response.ok) throw new Error(`L’import audio répond ${response.status}`)
  return response.json() as Promise<{ id: string }>
}

export async function uploadRemoteAudio(url: string, filename: string): Promise<{ id: string }> {
  const response = await fetch(`${getApiUrl()}/v1/uploads/from-url`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url, filename }),
  })
  const payload = await response.json().catch(() => ({})) as { id?: string; message?: string }
  if (!response.ok || !payload.id) throw new Error(payload.message ?? `Import distant impossible (${response.status})`)
  return { id: payload.id }
}

export async function getDetectionJob(jobId: string): Promise<DetectionJob> {
  const response = await fetch(`${getApiUrl()}/v1/detection-jobs/${jobId}`)
  if (!response.ok) throw new Error(`État de l’analyse indisponible (${response.status})`)
  return response.json() as Promise<DetectionJob>
}

export async function findDetectionJob(requestKey: string): Promise<DetectionJob | undefined> {
  const response = await fetch(`${getApiUrl()}/v1/detection-jobs?requestKey=${encodeURIComponent(requestKey)}`)
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
  const response = await fetch(`${getApiUrl()}/v1/episode-analysis`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      episodeId: episode.id,
      title: episode.title,
      description: episode.description,
      audioUrl: episode.audioUrl,
      sourceUrl: episode.sourceUrl,
      requestKey: `episode:${episode.id}`,
      sourceKind: source.kind,
      musical: source.musical === true,
      enable1001: externalTracklists,
      enableExternalTracklists: externalTracklists,
      refineTimestamps: true,
      force,
    }),
  })
  const payload = await response.json() as DetectionJob & { message?: string }
  if (!response.ok) throw new Error(payload.message ?? `Planification impossible (${response.status})`)
  return payload
}

export type DetectionJobOptions = {
  episodeId?: string
  title?: string
  description?: string
  sourceUrl?: string
  automaticTracklist?: boolean
  enable1001?: boolean
  enableExternalTracklists?: boolean
  sourceKind?: CatalogSource['kind']
  musical?: boolean
  refineTimestamps?: boolean
  requestKey?: string
  force?: boolean
}

export async function createDetectionJob(
  audioName: string,
  uploadId: string,
  options: DetectionJobOptions = {},
): Promise<DetectionJob> {
  const response = await fetch(`${getApiUrl()}/v1/detection-jobs`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      episodeId: options.episodeId ?? 'local-workbench',
      audioSource: { kind: 'upload', label: audioName, uploadId },
      strategies: ['chroma', 'transitions', 'catalogues'],
      title: options.title ?? audioName,
      description: options.description ?? '',
      sourceUrl: options.sourceUrl ?? '',
      automaticTracklist: options.automaticTracklist ?? false,
      enable1001: options.enable1001 ?? false,
      enableExternalTracklists: options.enableExternalTracklists ?? options.enable1001 ?? false,
      sourceKind: options.sourceKind ?? '',
      musical: options.musical ?? false,
      refineTimestamps: options.refineTimestamps ?? false,
      requestKey: options.requestKey,
      force: options.force ?? false,
    }),
  })
  if (!response.ok) throw new Error(`Le serveur de détection répond ${response.status}`)
  return response.json() as Promise<DetectionJob>
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
      finish(job)
      if (!terminal) pollTimer = window.setTimeout(poll, 1000)
    } catch {
      onError()
    }
  }
  events.addEventListener('job.updated', (event) => {
    const job = JSON.parse((event as MessageEvent).data) as DetectionJob
    finish(job)
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

export async function alignTracklist(jobId: string, text: string): Promise<DetectionJob> {
  const response = await fetch(`${getApiUrl()}/v1/detection-jobs/${jobId}/tracklist`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text }),
  })
  if (!response.ok) throw new Error(response.status === 422 ? 'Aucun morceau reconnu dans ce texte' : `Alignement impossible (${response.status})`)
  return response.json() as Promise<DetectionJob>
}

export async function discoverTracklist(jobId: string, url: string): Promise<{ tracks: DetectionJob['tracks']; candidateCount: number; message?: string }> {
  const response = await fetch(`${getApiUrl()}/v1/detection-jobs/${jobId}/discover`, {
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
  const response = await fetch(`${getApiUrl()}/v1/detection-jobs/${jobId}/discover-1001`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(isUrl ? { url: value.trim() } : { query: value.trim() }),
  })
  const payload = await response.json() as { tracks: DetectionJob['tracks']; candidateCount: number; source?: string; sourceUrl?: string; message?: string }
  if (!response.ok) throw new Error(payload.message ?? `Recherche de tracklist impossible (${response.status})`)
  return payload
}

export async function searchTracklistCandidates(query: string): Promise<Array<{ url: string; title: string; snippet: string; domain: string; address?: string }>> {
  const response = await fetch(`${getApiUrl()}/v1/tracklists/candidates?q=${encodeURIComponent(query)}&limit=8`)
  const payload = await response.json() as {
    results?: Array<{ url: string; title: string; snippet: string; domain: string; address?: string }>
    message?: string
  }
  if (!response.ok) throw new Error(payload.message ?? `Recherche web impossible (${response.status})`)
  return payload.results ?? []
}

export async function validateCatalogTrack(jobId: string, trackId: number): Promise<{ track: DetectionJob['tracks'][number]; accepted: boolean; artworkUrl?: string; deezerUrl?: string; spotifyUrl?: string }> {
  const response = await fetch(`${getApiUrl()}/v1/detection-jobs/${jobId}/tracks/${trackId}/validate`, { method: 'POST' })
  const payload = await response.json() as { track: DetectionJob['tracks'][number]; accepted: boolean; artworkUrl?: string; deezerUrl?: string; spotifyUrl?: string; message?: string }
  if (!response.ok) throw new Error(payload.message ?? `Catalogue indisponible (${response.status})`)
  return payload
}

export async function fingerprintTrack(jobId: string, trackId: number): Promise<{ track: DetectionJob['tracks'][number]; available: boolean; bestMatch?: { score: number; artist: string; title: string; mbid: string }; message?: string }> {
  const response = await fetch(`${getApiUrl()}/v1/detection-jobs/${jobId}/tracks/${trackId}/fingerprint`, { method: 'POST' })
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.message ?? `Empreinte acoustique indisponible (${response.status})`)
  return payload
}

export async function refineDetectionJob(jobId: string): Promise<DetectionJob> {
  const response = await fetch(`${getApiUrl()}/v1/detection-jobs/${jobId}/refine`, { method: 'POST' })
  const payload = await response.json() as DetectionJob & { message?: string }
  if (!response.ok) throw new Error(payload.message ?? `Raffinage impossible (${response.status})`)
  return payload
}
