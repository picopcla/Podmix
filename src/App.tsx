import { useEffect, useRef, useState } from 'react'
import type { CSSProperties, ChangeEvent } from 'react'
import { App as CapacitorApp } from '@capacitor/app'
import { Capacitor } from '@capacitor/core'
import { Share } from '@capacitor/share'
import { AudioLines, Cast, Check, ChevronDown, ChevronRight, Clock3, CloudUpload, Command, Disc3, Download, ExternalLink, Gauge, Heart, Library, ListMusic, LoaderCircle, Mic2, MoreHorizontal, Pause, Play, Plus, Radio, Repeat, RotateCcw, Search, Settings2, Share2, SkipBack, SkipForward, Sparkles, Speaker, SquarePlay, Trash2, WandSparkles, Wifi } from 'lucide-react'
import WaveSurfer from 'wavesurfer.js'
import { alignTracklist, cancelDetectionJob, claimWebTimestampJob, completeWebTimestampJob, createBoseCastSession, createEpisodeAnalysisJob, createSharePage, discover1001Tracklist, discoverTracklist, failWebTimestampJob, findDetectionJob, findTrackArtwork, findTrackLinks, getApiUrl, getDetectionJob, importRssFeed, liveSetStreamUrl, observeDetectionJob, resolveLiveSet, resolveLiveSetTracklist, searchDjSets, searchLiveSets, searchPodcasts, searchRadios, searchTracklistCandidates, setApiUrl, testApi, validateCatalogTrack } from './api'
import type { CatalogSource, DetectionJob, Episode, LiveSetDetails, LiveSetSearchResult, OfflineEpisode, PodcastSearchResult, Track } from './domain'
import { loadCatalog, loadOfflineEpisodes, loadSession, saveCatalog, saveOfflineEpisodes, saveSession } from './storage'

// Lucide intentionally does not ship brand logos; this keeps the familiar
// YouTube play-mark while the adjacent label makes the destination explicit.
const Youtube = SquarePlay
import { podmixPlayer } from './nativePlayer'
import type { BoseDevice, CastDevice, LibraryItem } from './nativePlayer'
import { checkForUpdate, currentVersion, downloadAndUpdate } from './updates'
import type { UpdateManifest, UpdateProgress } from './updates'
import { mergeCatalogSource, mergeEpisode, newEpisodesFromFeed } from './catalogMerge'
import { loadCompletedEpisodeIds, loadListeningSessions, loadPlaybackHistory, saveCompletedEpisodeIds, saveListeningSessions, savePlaybackHistory, searchListeningSessions, updateListeningSessions } from './history'
import type { ListeningSession, PlaybackHistoryItem } from './history'
import podmixInfinityLogo from './assets/podmix-logo-radio-c.png'
import './App.css'
import './identification.css'

const initialTracks: Track[] = []

type AppView = 'home' | 'resume' | 'favorites' | 'history' | 'studio' | 'settings' | 'liveSets' | 'djLibrary'
type HomeSectionId = 'resume' | 'podcasts' | 'shows' | 'radios' | 'djSets' | 'offline'
type HistoryItem = PlaybackHistoryItem
type FavoriteTrackEntry = {
  key: string
  source: CatalogSource
  episode: Episode
  track: Track
  trackIndex: number
}
type AppSettings = {
  continuousPlayback: boolean
  mobileQuality: boolean
  automaticAnalysis: boolean
  musicBrainzValidation: boolean
  maxPodcastEpisodes: number
  maxShowEpisodes: number
  maxDjEpisodes: number
}
type OutputDevice =
  | { id: 'phone'; kind: 'phone'; name: string; description: string; connected: boolean }
  | ({ kind: 'cast' } & CastDevice)
  | ({ kind: 'bose'; connected: boolean } & BoseDevice)
type NowPlayingItem = {
  id: string
  title: string
  artist: string
  url: string
  artworkUrl?: string
  scope?: 'episode' | 'track' | 'favorite' | 'radio' | 'liveSet'
}
type StoredBoseSession = {
  ip: string
  name: string
  item: NowPlayingItem
  contentOffsetSeconds: number
  streamStartSeconds: number
  durationSeconds: number
  positionSeconds?: number
  playing?: boolean
  savedAt: number
}

const defaultSettings: AppSettings = {
  continuousPlayback: true,
  mobileQuality: false,
  automaticAnalysis: true,
  musicBrainzValidation: true,
  maxPodcastEpisodes: 100,
  maxShowEpisodes: 50,
  maxDjEpisodes: 50,
}

// v2 démarre une file propre : la v1 pouvait contenir des épisodes anciens
// d'un RSS qui n'étaient simplement pas encore présents localement.
const AUTOMATIC_TIMESTAMP_QUEUE_KEY = 'podmix-new-episode-timestamp-queue-v2'
const RSS_BASELINE_SOURCES_KEY = 'podmix-rss-timestamp-baseline-sources-v1'
const BOSE_OUTPUT_SELECTION_KEY = 'podmix-bose-output-selection-v1'

function loadAutomaticTimestampQueue(): string[] {
  try {
    const stored = JSON.parse(localStorage.getItem(AUTOMATIC_TIMESTAMP_QUEUE_KEY) || '[]')
    return Array.isArray(stored) ? stored.filter((item): item is string => typeof item === 'string' && item.length > 0) : []
  } catch {
    return []
  }
}

function loadRssBaselineSources(): string[] {
  try {
    const stored = JSON.parse(localStorage.getItem(RSS_BASELINE_SOURCES_KEY) || '[]')
    return Array.isArray(stored) ? stored.filter((item): item is string => typeof item === 'string' && item.length > 0) : []
  } catch {
    return []
  }
}

function episodeLimitFor(source: CatalogSource, settings: AppSettings) {
  if (source.kind === 'podcast') return settings.maxPodcastEpisodes
  if (source.kind === 'show') return settings.maxShowEpisodes
  if (source.kind === 'dj') return settings.maxDjEpisodes
  return Number.POSITIVE_INFINITY
}

function trimSourceToEpisodeLimit(source: CatalogSource, settings: AppSettings) {
  const limit = episodeLimitFor(source, settings)
  if (source.episodes.length <= limit) return source
  const episodes = source.episodes
    .map((episode, index) => ({ episode, index }))
    .sort((left, right) => publishedTimestamp(right.episode.publishedAt) - publishedTimestamp(left.episode.publishedAt) || left.index - right.index)
    .slice(0, limit)
    .map(({ episode }) => episode)
  return { ...source, episodes }
}

function stripShowTimestamping(source: CatalogSource): CatalogSource {
  if (source.kind !== 'show') return source
  return {
    ...source,
    musical: false,
    episodes: source.episodes.map(({ tracks: _tracks, analysis: _analysis, webTracklistUrl: _webTracklistUrl, ...episode }) => episode),
  }
}

function formatTime(seconds: number) {
  const safe = Number.isFinite(seconds) ? Math.max(0, seconds) : 0
  const hours = Math.floor(safe / 3600)
  const minutes = Math.floor((safe % 3600) / 60)
  const secs = Math.floor(safe % 60)
  return hours > 0 ? `${hours}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}` : `${minutes}:${secs.toString().padStart(2, '0')}`
}

function formatUpdateDate(timestamp: number) {
  if (!timestamp) return 'Inconnue'
  return new Intl.DateTimeFormat('fr-FR', {
    dateStyle: 'short',
    timeStyle: 'medium',
  }).format(new Date(timestamp))
}

function formatEpisodeDate(value?: string) {
  const timestamp = value ? Date.parse(value) : NaN
  if (!Number.isFinite(timestamp)) return ''
  return new Intl.DateTimeFormat('fr-FR', { dateStyle: 'medium' }).format(new Date(timestamp))
}

function usesEstimatedPositions(tracks: Track[] | undefined) {
  return (tracks ?? []).some((track) =>
    track.timestampStatus === 'pending'
    || track.timestampSource === 'provisional'
    || (track.evidence ?? []).some((item) => item === 'Ordre de la tracklist'),
  )
}

function timestampSourceLabel(track: Track) {
  if (track.timestampSource === 'rss') return 'RSS · original'
  if (track.timestampSource === 'youtube') return 'YT · vérifié'
  if (track.timestampSource === 'external') return '1001TL · vérifié'
  if (track.timestampSource === 'audio') return 'Audio · analysé'
  if (track.timestampSource === 'manual') return 'Repère manuel'
  if (track.timestampSource === 'provisional') return 'Provisoire · sans timestamp source'
  return 'Source non précisée'
}

function sourceKindLabel(source: CatalogSource) {
  if (source.kind === 'show') return 'Émission'
  if (source.kind === 'dj') return 'DJ set'
  if (source.kind === 'radio') return 'Radio'
  return 'Podcast'
}

function analysisLabel(episode: Episode) {
  if (!episode.analysis) return ''
  if (episode.analysis.status === 'completed') return episode.tracks?.length ? 'Tracklist prête' : 'Analyse terminée'
  if (episode.analysis.status === 'failed') return 'Analyse échouée'
  if (episode.analysis.status === 'cancelled') return 'Analyse annulée'
  return `${episode.analysis.stage} · ${episode.analysis.progress}%`
}

function hasVerifiedWebTimestamps(tracks: Track[] | undefined) {
  const list = tracks ?? []
  if (list.length < 2) return false
  const validCount = list.filter((track) => (
    (track.timestampSource === 'external' || track.timestampSource === 'youtube')
    && track.timestampStatus === 'provided'
    && Number(track.time) > 0
  )).length
  return validCount / list.length >= 0.6
}

function hasReusableCompletedTimestamps(tracks: Track[] | undefined) {
  const list = tracks ?? []
  if (list.length < 2) return false
  if (hasVerifiedWebTimestamps(list)) return true
  const validCount = list.filter((track) => (
    ['rss', 'audio'].includes(track.timestampSource ?? '')
    && track.timestampStatus === 'provided'
    && Number(track.time) > 0
  )).length
  // Le premier morceau commence normalement à zéro. Exiger une majorité de
  // positions strictement positives rejette les anciens faux résultats 0:00.
  return validCount / list.length >= 0.6
}

function hasVerifiedTimestampsFrom(tracks: Track[] | undefined, source: 'external' | 'youtube') {
  const list = tracks ?? []
  if (list.length < 2) return false
  const validCount = list.filter((track) => (
    track.timestampSource === source
    && track.timestampStatus === 'provided'
    && Number(track.time) > 0
  )).length
  return validCount / list.length >= 0.6
}

function hasAudioTimestamps(tracks: Track[] | undefined) {
  const list = tracks ?? []
  return list.length >= 2 && list.every((track) => (
    track.timestampSource === 'audio'
    && track.timestampStatus === 'provided'
    && Number.isFinite(Number(track.time))
  ))
}

function episodeTimestampStatus(episode: Episode) {
  const tracks = episode.tracks ?? []
  if (episode.analysis?.status === 'queued') {
    return { label: 'Timestamping en attente', shortLabel: 'Attente', tone: 'pending' }
  }
  if (episode.analysis?.status === 'running' || episode.analysis?.status === 'web_pending') {
    return { label: 'Timestamping en cours', shortLabel: 'Analyse', tone: 'running' }
  }
  if (episode.analysis?.status === 'failed') {
    return { label: 'Repères de morceaux indisponibles (le téléchargement reste possible)', shortLabel: 'Pistes', tone: 'failed' }
  }
  if (episode.analysis?.status === 'cancelled') {
    return { label: 'Repères de morceaux annulés (le téléchargement reste possible)', shortLabel: 'Pistes', tone: 'failed' }
  }
  const hasYoutubeTimestamps = hasVerifiedTimestampsFrom(tracks, 'youtube')
  const has1001Timestamps = hasVerifiedTimestampsFrom(tracks, 'external')
  const hasAnalyzedAudioTimestamps = hasAudioTimestamps(tracks)
  const hasRssTimestamps = tracks.length > 0 && tracks.every((track) => (
    track.timestampSource === 'rss' && track.timestampStatus === 'provided'
  ))
  if (hasYoutubeTimestamps) return { label: 'YT · vérifié', shortLabel: 'YT', tone: 'youtube' }
  if (has1001Timestamps) return { label: '1001TL · vérifié', shortLabel: '1001TL', tone: 'tl1001' }
  if (hasAnalyzedAudioTimestamps) return { label: 'Audio · analysé', shortLabel: 'Audio', tone: 'audio' }
  if (hasRssTimestamps) return { label: 'RSS · original', shortLabel: 'RSS', tone: 'rss' }
  // Une analyse terminée sans source de temps explicite signifie que les
  // positions affichées restent à 0:00 : c'est un échec Web, pas une attente.
  if (episode.analysis?.status === 'completed') {
    return { label: 'Repères Web indisponibles (le téléchargement reste possible)', shortLabel: 'Pistes', tone: 'failed' }
  }
  if (tracks.some((track) => track.timestampSource === 'provisional')) {
    return { label: 'Timestamping en attente', shortLabel: 'Attente', tone: 'pending' }
  }
  return { label: 'Timestamping non vérifié', shortLabel: 'À vérifier', tone: 'idle' }
}

function analysisIsActive(status?: DetectionJob['status']) {
  return status === 'queued' || status === 'running' || status === 'web_pending'
}

function needsTimestamping(tracks: Track[] | undefined, episodeAnalysis: { status?: DetectionJob['status']; jobId?: string } | undefined) {
  // Un échec reste visible et ne boucle pas indéfiniment. Il se relance avec
  // « Actualiser » ; la file automatique passe aussitôt à l'épisode suivant.
  if (episodeAnalysis?.status === 'failed') return false
  // Une actualisation de podcast est persistée sous forme d'épisode « queued »
  // sans jobId. Elle doit survivre à une fermeture/réouverture de l'app.
  if (episodeAnalysis?.status === 'queued' || episodeAnalysis?.status === 'running' || episodeAnalysis?.status === 'web_pending') return true
  if (!(tracks ?? []).length) return true
  return (tracks ?? []).some((track) =>
    track.timestampSource === 'provisional'
    || track.timestampStatus === 'pending'
    || !Number.isFinite(track.time),
  )
}

function parseDuration(value: string): number {
  const parts = value.split(':').map(Number)
  if (!parts.length || parts.some((part) => !Number.isFinite(part) || part < 0)) return 0
  return parts.reduce((total, part) => total * 60 + part, 0)
}

/** Les descriptions RSS sont souvent du HTML dans une section CDATA. Elles
 * restent des données, jamais du HTML à injecter dans l'application. */
function descriptionToText(value: string): string {
  const html = value
    .replace(/<\s*br\s*\/?>/gi, '\n')
    .replace(/<\s*\/p\s*>\s*<\s*p[^>]*>/gi, '\n\n')
  const parsed = new DOMParser().parseFromString(html, 'text/html').body.textContent ?? ''
  return parsed.replace(/\u00a0/g, ' ').replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim()
}

function publishedTimestamp(value: string | undefined) {
  const timestamp = value ? Date.parse(value) : NaN
  return Number.isFinite(timestamp) ? timestamp : 0
}

function episodeNumberFromTitle(value: string) {
  return value.match(/(?:episode|radioshow|show)\s*#?\s*(\d{1,5})/i)?.[1] ?? ''
}

function native1001SearchQueries(title: string, source?: CatalogSource) {
  const cleanTitle = title
    .replace(/#/g, '')
    .replace(/\bEpisode\b/gi, '')
    .replace(/\s+/g, ' ')
    .trim()
  const episodeNumber = episodeNumberFromTitle(title)
  const presenter = source?.description.match(
    /\b([A-ZÀ-ÖØ-Þ][\p{L}'’-]+(?:\s+[A-ZÀ-ÖØ-Þ][\p{L}'’-]+){1,2})\s+(?:presents?|présente)\b/iu,
  )?.[1]
  const sourceTitle = source?.title.replace(/\s+/g, ' ').trim()
  return [
    presenter && sourceTitle && episodeNumber ? `${presenter} ${sourceTitle} ${episodeNumber}` : '',
    presenter && episodeNumber ? `${presenter} ${episodeNumber}` : '',
    sourceTitle && episodeNumber ? `${sourceTitle} ${episodeNumber}` : '',
    cleanTitle,
    title,
  ].map((item) => item.replace(/\s+/g, ' ').trim())
    .filter((item, index, all) => item.length >= 3 && all.indexOf(item) === index)
}

function rank1001Candidates<T extends { url: string; title?: string }>(candidates: T[], episodeTitle: string) {
  const episodeNumber = episodeNumberFromTitle(episodeTitle)
  return [...candidates]
    .filter((item, index, all) => all.findIndex((candidate) => candidate.url === item.url) === index)
    .sort((left, right) => score(right) - score(left))

  function score(candidate: T) {
    const text = `${candidate.title ?? ''} ${candidate.url}`.toLowerCase()
    if (text.includes('mémorisée')) return 10_000
    if (!episodeNumber || !text.includes(episodeNumber)) return 0
    return /(?:episode|radioshow|show)[^0-9]{0,8}/.test(text) ? 200 : 50
  }
}

function sourceLatestEpisodeTimestamp(source: CatalogSource) {
  return Math.max(0, ...source.episodes.map((episode) => publishedTimestamp(episode.publishedAt)))
}

function sortTimestampJobs(items: Array<{ source: CatalogSource; episode: Episode }>) {
  return items.sort((left, right) =>
    publishedTimestamp(right.episode.publishedAt) - publishedTimestamp(left.episode.publishedAt)
    || sourceLatestEpisodeTimestamp(right.source) - sourceLatestEpisodeTimestamp(left.source)
    || right.episode.id.localeCompare(left.episode.id),
  )
}

function episodePlaybackStatus(episode: Episode, position: number, liveDuration = 0) {
  const duration = liveDuration > 0 ? liveDuration : parseDuration(episode.duration)
  if (position < 1) return { kind: 'new', label: 'À lire', percent: 0 }
  if (duration <= 0) return { kind: 'progress unknown', label: 'En cours', percent: 0 }
  const percent = Math.min(100, Math.max(1, Math.round((position / duration) * 100)))
  if (percent >= 98) return { kind: 'done', label: 'Lu', percent: 100 }
  return { kind: 'progress', label: 'En cours', percent }
}

function favoriteTrackKey(episodeId: string, track: Track, trackIndex: number) {
  return `${episodeId}::track::${track.id}::${trackIndex}`
}

function playbackIntentActive(state: { playing: boolean; playRequested?: boolean }) {
  return state.playRequested ?? state.playing
}

// Android Auto and the native service can expose an episode under a prefixed
// media id (resume::<id>, episode::<id>...). Resume buttons look up the plain
// episode id, so every save must use the plain one.
function normalizeEpisodeMediaId(id: string): string {
  let result = id
  for (let guard = 0; guard < 4; guard += 1) {
    const stripped = result.replace(/^(resume|episode-resume|episode-start|episode)::/, '')
    if (stripped === result) break
    result = stripped
  }
  return result
}

// Debug ring buffer (last 60 saves) so a stuck resume position can be diagnosed
// on the device: read localStorage key podmix-resume-debug-v1 via chrome://inspect.
function logResumeDebug(entry: Record<string, unknown>) {
  try {
    const key = 'podmix-resume-debug-v1'
    const previous = JSON.parse(localStorage.getItem(key) ?? '[]') as unknown[]
    const next = [...(Array.isArray(previous) ? previous : []), { at: new Date().toISOString(), ...entry }].slice(-60)
    localStorage.setItem(key, JSON.stringify(next))
  } catch {
    // Diagnostics must never break playback.
  }
}

function sameNowPlaying(left: NowPlayingItem | undefined, right: NowPlayingItem) {
  return Boolean(left
    && left.id === right.id
    && left.title === right.title
    && left.artist === right.artist
    && left.url === right.url
    && left.scope === right.scope
    && left.artworkUrl === right.artworkUrl)
}

function trackArtwork(track: Track, episode: Episode, source: CatalogSource) {
  return track.artworkUrl || episode.artworkUrl || source.artworkUrl || ''
}

function ServiceLogo({ service }: { service: 'deezer' | 'spotify' }) {
  if (service === 'spotify') {
    return <svg className="favorite-service-logo spotify-logo" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="11" fill="currentColor" />
      <path d="M6.4 9.1c3.9-1.15 8.25-.73 11.2.88M6.9 12.2c3.28-.93 6.95-.57 9.75.72M7.5 15.15c2.55-.66 5.34-.38 7.48.55" fill="none" stroke="#151615" strokeWidth="1.55" strokeLinecap="round" />
    </svg>
  }
  return <svg className="favorite-service-logo deezer-logo" viewBox="0 0 24 24" aria-hidden="true">
    <rect x="2" y="4" width="3.1" height="4" rx=".5" fill="#ff5f4a" /><rect x="2" y="9" width="3.1" height="4" rx=".5" fill="#ff9e3d" /><rect x="2" y="14" width="3.1" height="6" rx=".5" fill="#f6d94c" />
    <rect x="6.2" y="7" width="3.1" height="6" rx=".5" fill="#93d953" /><rect x="6.2" y="14" width="3.1" height="6" rx=".5" fill="#50d3b0" />
    <rect x="10.4" y="4" width="3.1" height="9" rx=".5" fill="#45b9ed" /><rect x="10.4" y="14" width="3.1" height="6" rx=".5" fill="#5787ed" />
    <rect x="14.6" y="7" width="3.1" height="6" rx=".5" fill="#9569e8" /><rect x="14.6" y="14" width="3.1" height="6" rx=".5" fill="#c85bdc" />
    <rect x="18.8" y="4" width="3.1" height="16" rx=".5" fill="#ec5e92" />
  </svg>
}

function favoriteServiceControl(service: 'deezer' | 'spotify', url?: string, artist = '', title = '') {
  const label = service === 'deezer' ? 'Deezer' : 'Spotify'
  // Spotify ne propose pas de recherche publique fiable par API sans clé. Si
  // le morceau exact n'est pas encore résolu, le bouton ouvre donc la recherche
  // Spotify déjà remplie : l'accès reste utile au lieu de disparaître en gris.
  const searchUrl = service === 'spotify' && artist && title
    ? `https://open.spotify.com/search/${encodeURIComponent(`${artist} ${title}`)}`
    : undefined
  const targetUrl = url || searchUrl
  return targetUrl
    ? <a className={`favorite-service ${service} available ${url ? 'exact' : 'search'}`} href={targetUrl} target="_blank" rel="noreferrer" aria-label={url ? `${label} disponible : ouvrir le morceau` : `${label} : rechercher le morceau`} title={url ? `Écouter sur ${label}` : `Rechercher ce morceau sur ${label}`}><ServiceLogo service={service} /><span className="favorite-service-label">{label}</span></a>
    : <button className={`favorite-service ${service} unavailable`} type="button" disabled aria-label={`${label} indisponible pour ce morceau`} title={`${label} indisponible`}><ServiceLogo service={service} /><span className="favorite-service-label">{label}</span></button>
}

function loadFavoriteTrackIds() {
  try {
    const stored = JSON.parse(localStorage.getItem('podmix-track-favorites-v1') ?? '[]')
    return Array.isArray(stored) ? stored.map(String) : []
  } catch {
    return []
  }
}

function loadBoseSession(): StoredBoseSession | undefined {
  try {
    const stored = JSON.parse(localStorage.getItem('podmix-bose-session-v1') ?? 'null') as Partial<StoredBoseSession> | null
    if (!stored?.ip || !stored.item?.id || !stored.item.url || !stored.item.title) return undefined
    return {
      ip: stored.ip,
      name: stored.name || 'Bose SoundTouch',
      item: stored.item as NowPlayingItem,
      contentOffsetSeconds: Math.max(0, Number(stored.contentOffsetSeconds) || 0),
      streamStartSeconds: Math.max(0, Number(stored.streamStartSeconds) || 0),
      durationSeconds: Math.max(0, Number(stored.durationSeconds) || 0),
      positionSeconds: stored.positionSeconds === undefined
        ? undefined
        : Math.max(0, Number(stored.positionSeconds) || 0),
      playing: stored.playing === undefined ? undefined : Boolean(stored.playing),
      savedAt: Number(stored.savedAt) || 0,
    }
  } catch {
    return undefined
  }
}

function CachedArtwork({ artworkUrl, cachedArtworkUrl, title, Icon, size = 34 }: { artworkUrl?: string; cachedArtworkUrl?: string; title: string; Icon: typeof Mic2; size?: number }) {
  const [localArtworkUrl, setLocalArtworkUrl] = useState('')
  const [unavailable, setUnavailable] = useState(false)

  useEffect(() => {
    setUnavailable(false)
    setLocalArtworkUrl('')
    if (!artworkUrl || cachedArtworkUrl || !podmixPlayer.isNative || !/^https?:/i.test(artworkUrl)) return
    let active = true
    void podmixPlayer.cacheArtwork(artworkUrl)
      .then((cached) => { if (active && cached.dataUrl) setLocalArtworkUrl(cached.dataUrl) })
      .catch(() => undefined)
    return () => { active = false }
  }, [artworkUrl, cachedArtworkUrl])

  const imageUrl = cachedArtworkUrl || localArtworkUrl || artworkUrl

  useEffect(() => setUnavailable(false), [imageUrl])

  if (!imageUrl || unavailable) {
    return <Icon size={size} role="img" aria-label={`Logo ${title}`} />
  }

  return <img src={imageUrl} alt={`Logo ${title}`} onError={() => setUnavailable(true)} />
}

function App() {
  const waveformRef = useRef<HTMLDivElement>(null)
  const waveRef = useRef<WaveSurfer | null>(null)
  const trackQueueRef = useRef<{
    episodeId: string
    episodeTitle: string
    sourceTitle: string
    audioUrl: string
    tracks: Track[]
    mediaIds: string[]
    artworkUrls: string[]
  } | undefined>(undefined)
  const favoriteQueueRef = useRef<FavoriteTrackEntry[] | undefined>(undefined)
  const episodePlaybackRef = useRef<string | undefined>(undefined)
  // Les résolutions d'URL DJ sont asynchrones. Lors de plusieurs appuis rapides,
  // seul le dernier choix doit avoir le droit de modifier le lecteur Media3.
  const playbackRequestRef = useRef(0)
  const lastPersistedPlaybackRef = useRef({ id: '', position: -1, at: 0 })
  const skipPendingRef = useRef(false)

  function beginPlaybackRequest() {
    playbackRequestRef.current += 1
    return playbackRequestRef.current
  }

  function isCurrentPlaybackRequest(requestId: number) {
    return playbackRequestRef.current === requestId
  }

  function favoriteQueueEntryForState(state: { mediaId: string; queueIndex: number }) {
    const entries = favoriteQueueRef.current
    if (!entries?.length || !state.mediaId) return undefined
    const indexed = entries[state.queueIndex]
    // queueIndex belongs to the favorites queue, while a restored episode
    // queue has its own unrelated index. The media id is the authority.
    if (indexed?.key === state.mediaId) return indexed
    return entries.find((entry) => entry.key === state.mediaId)
  }

  function resolveActiveMediaId(state: { mediaId: string; queueIndex: number }) {
    const favoriteItem = favoriteQueueEntryForState(state)
    if (favoriteItem) return favoriteItem.key
    const trackQueue = trackQueueRef.current
    if (trackQueue && state.queueIndex >= 0 && state.queueIndex < trackQueue.mediaIds.length) {
      return trackQueue.mediaIds[state.queueIndex]
    }
    return state.mediaId
  }

  const persistenceReady = useRef(false)
  const backupInputRef = useRef<HTMLInputElement>(null)
  const undoStack = useRef<Track[][]>([])
  const [tracks, setTracks] = useState(initialTracks)
  const [selectedId, setSelectedId] = useState(0)
  const [isPlaying, setIsPlaying] = useState(false)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(3420)
  const [hasLocalAudio, setHasLocalAudio] = useState(false)
  const [audioName, setAudioName] = useState('Nouvelle session')
  const [detection, setDetection] = useState<'idle' | 'running' | 'done'>('idle')
  const [progress, setProgress] = useState(0)
  const [detectionStage, setDetectionStage] = useState('En attente')
  const [detectionError, setDetectionError] = useState('')
  const [currentJobId, setCurrentJobId] = useState('')
  const [tracklistText, setTracklistText] = useState('')
  const [aligning, setAligning] = useState(false)
  const [sourceUrl, setSourceUrl] = useState('')
  const [discovering, setDiscovering] = useState(false)
  const [searchingSource, setSearchingSource] = useState(false)
  const [tl1001Query, setTl1001Query] = useState('')
  const [discovering1001, setDiscovering1001] = useState(false)
  const [validatingCatalog, setValidatingCatalog] = useState(false)
  const [activeView, setActiveView] = useState<AppView>('home')
  const [liveSetQuery, setLiveSetQuery] = useState('')
  const [liveSetResults, setLiveSetResults] = useState<LiveSetSearchResult[]>([])
  const [liveSetResultLimit, setLiveSetResultLimit] = useState(24)
  const [liveSetSearching, setLiveSetSearching] = useState(false)
  const [liveSetError, setLiveSetError] = useState('')
  const [liveSetProvider, setLiveSetProvider] = useState<'all' | 'youtube' | 'soundcloud'>('all')
  const [liveSetSort, setLiveSetSort] = useState<'relevance' | 'recent' | 'popular'>('relevance')
  const [activeLiveSetId, setActiveLiveSetId] = useState('')
  const [liveSetBusyId, setLiveSetBusyId] = useState('')
  const [liveSetTracklistText, setLiveSetTracklistText] = useState('')
  // Un résultat Live Set devient une source DJ dans le catalogue : aucune
  // bibliothèque locale parallèle aux podcasts et émissions.
  const [savedLiveSets, setSavedLiveSets] = useState<LiveSetDetails[]>([])
  const [resumeExpanded, setResumeExpanded] = useState(false)
  const [offlineExpanded, setOfflineExpanded] = useState(false)
  const [homeSectionOrder, setHomeSectionOrder] = useState<HomeSectionId[]>(() => {
    try {
      const stored = JSON.parse(localStorage.getItem('podmix-home-section-order-v1') ?? 'null')
      const allowed = ['podcasts', 'shows', 'radios', 'djSets']
      if (Array.isArray(stored) && stored.every((id) => allowed.includes(id))) {
        return [...stored, ...allowed.filter((id) => !stored.includes(id))] as HomeSectionId[]
      }
    } catch {}
    return ['podcasts', 'shows', 'radios', 'djSets']
  })
  const [dragSection, setDragSection] = useState<HomeSectionId | null>(null)
  const dragStartY = useRef(0)
  const dragCurrentY = useRef(0)
  const dragLongPressTimer = useRef<number | null>(null)

  function handleSectionDragStart(sectionId: HomeSectionId, clientY: number) {
    dragStartY.current = clientY
    dragCurrentY.current = clientY
    dragLongPressTimer.current = window.setTimeout(() => {
      setDragSection(sectionId)
    }, 500)
  }

  function handleSectionDragMove(clientY: number) {
    if (!dragSection) return
    dragCurrentY.current = clientY
    const deltaY = clientY - dragStartY.current
    const threshold = 80
    const order = [...homeSectionOrder]
    const currentIndex = order.indexOf(dragSection)
    if (deltaY > threshold && currentIndex < order.length - 1) {
      const newIndex = currentIndex + 1
      order.splice(currentIndex, 1)
      order.splice(newIndex, 0, dragSection)
      setHomeSectionOrder(order)
      localStorage.setItem('podmix-home-section-order-v1', JSON.stringify(order))
      dragStartY.current = clientY
    } else if (deltaY < -threshold && currentIndex > 0) {
      const newIndex = currentIndex - 1
      order.splice(currentIndex, 1)
      order.splice(newIndex, 0, dragSection)
      setHomeSectionOrder(order)
      localStorage.setItem('podmix-home-section-order-v1', JSON.stringify(order))
      dragStartY.current = clientY
    }
  }

  function handleSectionDragEnd() {
    if (dragLongPressTimer.current) {
      clearTimeout(dragLongPressTimer.current)
      dragLongPressTimer.current = null
    }
    setDragSection(null)
  }

  useEffect(() => {
    function handleGlobalPointerMove(event: PointerEvent) {
      if (dragSection) {
        handleSectionDragMove(event.clientY)
      }
    }
    function handleGlobalPointerUp() {
      if (dragSection) {
        handleSectionDragEnd()
      }
    }
    window.addEventListener('pointermove', handleGlobalPointerMove)
    window.addEventListener('pointerup', handleGlobalPointerUp)
    return () => {
      window.removeEventListener('pointermove', handleGlobalPointerMove)
      window.removeEventListener('pointerup', handleGlobalPointerUp)
    }
  }, [dragSection])

  const [catalog, setCatalog] = useState<CatalogSource[]>(loadCatalog)
  const [cachedHomeArtwork, setCachedHomeArtwork] = useState<Record<string, string>>({})
  const [linkLookupRefresh, setLinkLookupRefresh] = useState(0)
  const catalogRef = useRef(catalog)
  const nativeLibrarySignatureRef = useRef('')

  function restoreTrackQueueForMediaId(mediaId: string) {
    // Favorite items deliberately reuse the track ids from their episodes,
    // but their queue order is the favorites order. Rebuilding an episode
    // queue here makes index N point to an entirely different title.
    if (favoriteQueueRef.current?.some((entry) => entry.key === mediaId)) {
      trackQueueRef.current = undefined
      return undefined
    }
    const currentQueue = trackQueueRef.current
    const currentIndex = currentQueue?.mediaIds.indexOf(mediaId) ?? -1
    if (currentQueue && currentIndex >= 0) {
      return { queue: currentQueue, trackIndex: currentIndex }
    }
    for (const source of catalogRef.current) {
      for (const episode of source.episodes) {
        const tracks = episode.tracks ?? []
        const trackIndex = tracks.findIndex((track, index) => favoriteTrackKey(episode.id, track, index) === mediaId)
        if (trackIndex < 0) continue
        const queue = {
          episodeId: episode.id,
          episodeTitle: episode.title,
          sourceTitle: source.title,
          audioUrl: offlineEpisodesRef.current.find((item) => item.id === episode.id && item.status === 'completed' && item.localUri)?.localUri || episode.audioUrl,
          tracks,
          mediaIds: tracks.map((track, index) => favoriteTrackKey(episode.id, track, index)),
          artworkUrls: tracks.map((track) => trackArtwork(track, episode, source)),
        }
        trackQueueRef.current = queue
        return { queue, trackIndex }
      }
    }
    return undefined
  }

  useEffect(() => {
    setCatalog((sources) => {
      // Les toutes premières versions du parseur 1001Tracklists ajoutaient
      // les ingrédients internes des mashups comme de faux morceaux à 0:00.
      // Nettoyage unique du catalogue local, sans toucher aux podcasts.
      const normalized = sources.map((source) => {
        const withoutShowTimestamping = stripShowTimestamping(source)
        if (withoutShowTimestamping.kind !== 'dj') return withoutShowTimestamping
        const episodes = withoutShowTimestamping.episodes.map((episode) => {
          const tracks = removeInvalidLiveSetZeroCues(episode.tracks)
          return tracks === episode.tracks ? episode : { ...episode, tracks }
        })
        const episodesChanged = episodes.some((episode, index) => episode !== withoutShowTimestamping.episodes[index])
        return episodesChanged ? { ...withoutShowTimestamping, episodes } : withoutShowTimestamping
      })
      const changed = normalized.some((source, index) => source !== sources[index])
      return changed ? normalized : sources
    })
  }, [])
  const timestampQueueBusy = useRef(false)
  const terminalTimestampEpisodes = useRef(new Set<string>())
  // Cette file ne contient que les épisodes entrés après l'ajout d'un
  // podcast, ou apparus depuis dans un flux RSS déjà connu. Les anciens
  // épisodes restent strictement manuels.
  const automaticTimestampEpisodeIds = useRef(new Set(loadAutomaticTimestampQueue()))
  const rssBaselineSourceIds = useRef(new Set(loadRssBaselineSources()))
  // Les épisodes explicitement actualisés doivent être rejoués une fois, y
  // compris si leur ancienne tracklist paraît déjà complète.
  const forcedTimestampEpisodes = useRef(new Set<string>())
  const manualTimestampQueueActive = useRef(false)
  const activeTimestampSourceId = useRef(localStorage.getItem('podmix-active-timestamp-source-v1') ?? '')
  const observedScheduledJobs = useRef(new Set<string>())
  const artworkLookupAttempted = useRef(new Set<string>())
  const linkLookupAttempted = useRef(new Set<string>())
  const [showAddSource, setShowAddSource] = useState(false)
  const [feedUrl, setFeedUrl] = useState('')
  const [feedError, setFeedError] = useState('')
  const [addingFeed, setAddingFeed] = useState(false)
  const [selectedSourceId, setSelectedSourceId] = useState('')
  const [selectedEpisodeId, setSelectedEpisodeId] = useState('')
  const [pendingPlaybackTarget, setPendingPlaybackTarget] = useState<{ sourceId: string; episodeId: string }>()
  const [nowPlaying, setNowPlaying] = useState<NowPlayingItem>()
  const nowPlayingRef = useRef(nowPlaying)
  const [fullPlayerOpen, setFullPlayerOpen] = useState(false)
  const miniPlayerSwipeStart = useRef<number | null>(null)
  const miniPlayerIgnoreTap = useRef(false)
  const [globalPlaying, setGlobalPlaying] = useState(false)
  const [repeatMode, setRepeatMode] = useState<0 | 1 | 2>(0)
  const [globalPosition, setGlobalPosition] = useState(0)
  const [globalDuration, setGlobalDuration] = useState(0)
  const [activeMediaId, setActiveMediaId] = useState('')
  const [canSkipNext, setCanSkipNext] = useState(false)
  const [canSkipPrevious, setCanSkipPrevious] = useState(false)
  const [skipPendingDirection, setSkipPendingDirection] = useState<'next' | 'previous' | null>(null)
  const [downloadMessage, setDownloadMessage] = useState('')
  const [sourceMode, setSourceMode] = useState<'rss' | 'show' | 'radio' | 'dj'>('rss')
  const [radioQuery, setRadioQuery] = useState('')
  const [radioResults, setRadioResults] = useState<CatalogSource[]>([])
  const [podcastQuery, setPodcastQuery] = useState('')
  const [podcastResults, setPodcastResults] = useState<PodcastSearchResult[]>([])
  const [searchingPodcasts, setSearchingPodcasts] = useState(false)
  const [searchingRadios, setSearchingRadios] = useState(false)
  const [favoriteTrackIds, setFavoriteTrackIds] = useState<string[]>(loadFavoriteTrackIds)
  const [sharingFavoriteKey, setSharingFavoriteKey] = useState('')
  const [nativeFavoritesReady, setNativeFavoritesReady] = useState(!podmixPlayer.isNative)
  const [history, setHistory] = useState<HistoryItem[]>(loadPlaybackHistory)
  const historyRef = useRef(history)
  const [completedEpisodeIds, setCompletedEpisodeIds] = useState<string[]>(loadCompletedEpisodeIds)
  const completedEpisodeIdsRef = useRef(completedEpisodeIds)
  const [listeningSessions, setListeningSessions] = useState<ListeningSession[]>(loadListeningSessions)
  const [historyQuery, setHistoryQuery] = useState('')
  const [offlineEpisodes, setOfflineEpisodes] = useState<OfflineEpisode[]>(loadOfflineEpisodes)
  const offlineEpisodesRef = useRef(offlineEpisodes)
  const [showSearch, setShowSearch] = useState(false)
  const [globalQuery, setGlobalQuery] = useState('')
  const [castMessage, setCastMessage] = useState('')
  const [showOutputPicker, setShowOutputPicker] = useState(false)
  const [outputDevices, setOutputDevices] = useState<OutputDevice[]>([])
  const [discoveringOutputs, setDiscoveringOutputs] = useState(false)
  const [connectingOutputId, setConnectingOutputId] = useState('')
  const [activeOutput, setActiveOutput] = useState<{ kind: 'phone' | 'cast' | 'bose'; id: string; name: string }>({
    kind: 'phone',
    id: 'phone',
    name: 'Ce téléphone',
  })
  const activeOutputRef = useRef(activeOutput)
  useEffect(() => { activeOutputRef.current = activeOutput }, [activeOutput])

  function persistAutomaticTimestampQueue() {
    localStorage.setItem(
      AUTOMATIC_TIMESTAMP_QUEUE_KEY,
      JSON.stringify([...automaticTimestampEpisodeIds.current]),
    )
  }

  function enqueueNewEpisodesForTimestamping(episodes: Episode[]) {
    let changed = false
    for (const episode of episodes) {
      if (episode.audioUrl && !automaticTimestampEpisodeIds.current.has(episode.id)) {
        automaticTimestampEpisodeIds.current.add(episode.id)
        changed = true
      }
    }
    if (changed) persistAutomaticTimestampQueue()
  }

  function finishAutomaticEpisodeTimestamping(episodeId: string) {
    if (automaticTimestampEpisodeIds.current.delete(episodeId)) persistAutomaticTimestampQueue()
  }

  function establishRssBaseline(sourceId: string) {
    if (rssBaselineSourceIds.current.has(sourceId)) return
    rssBaselineSourceIds.current.add(sourceId)
    localStorage.setItem(RSS_BASELINE_SOURCES_KEY, JSON.stringify([...rssBaselineSourceIds.current]))
  }

  async function resetRepeatForNewQueue() {
    if (repeatMode !== 0) setRepeatMode(0)
    await podmixPlayer.setRepeatMode(0)
  }

  const [boseIp, setBoseIp] = useState(() => localStorage.getItem('podmix-bose-ip') ?? '')
  const [boseVolume, setBoseVolume] = useState(30)
  const [boseMessage, setBoseMessage] = useState('')
  const [castVolume, setCastVolume] = useState(50)
  const [playerVolume, setPlayerVolume] = useState(100)
  const boseActiveRef = useRef(false)
  const boseStartPositionRef = useRef(0)
  const boseContentOffsetRef = useRef(0)
  const bosePersistedAtRef = useRef(0)
  const boseClockRef = useRef({ positionSeconds: 0, updatedAt: 0, playing: false, remotePositionSeconds: 0 })
  const boseTrackTransitionRef = useRef(false)
  // Remote Bose commands are network operations and may take longer than a
  // tap. Queue them so that a rapid episode/track change always leaves Bose
  // on the last requested item, never on a late earlier request.
  const boseSendQueueRef = useRef<Promise<void>>(Promise.resolve())
  const boseSendGenerationRef = useRef(0)
  // A SoundTouch exposes only a small HTTP control plane.  Do not poll it
  // while Stop → SetAVTransportURI → Play is running: a concurrent /info,
  // /volume or /now_playing request can make a second rapid replacement
  // appear to stall on older firmware.
  const boseTransferPendingRef = useRef(false)
  const boseResumeAfterInterruptionRef = useRef(false)
  const bosePollFailuresRef = useRef(0)
  const bosePollPendingRef = useRef(false)
  const playerPollPendingRef = useRef(false)
  const boseVolumePendingRef = useRef<number | null>(null)
  const boseVolumeSendingRef = useRef(false)

  function isBoseOutputActive() {
    return boseActiveRef.current
      || activeOutputRef.current.kind === 'bose'
  }

  function preferredBoseIp() {
    return boseIp.trim()
      || localStorage.getItem('podmix-bose-ip')?.trim()
      || ''
  }

  function selectBoseOutput(ip: string, name = 'Bose SoundTouch') {
    const bose = { kind: 'bose' as const, id: ip, name }
    boseActiveRef.current = true
    activeOutputRef.current = bose
    setBoseIp(ip)
    setActiveOutput(bose)
  }

  function preferredEpisodePlaybackUrl(episodeId: string, remoteUrl: string) {
    return offlineEpisodesRef.current.find(
      (item) => item.id === episodeId && item.status === 'completed' && item.localUri,
    )?.localUri || remoteUrl
  }

  function boseTransferItem(item: NowPlayingItem): NowPlayingItem {
    if (/^https?:\/\//i.test(item.url)) return item

    // Media3 deliberately uses the downloaded content:// (or file://) copy
    // while the phone is offline. A SoundTouch cannot access an Android-local
    // URI: transfer the original RSS/catalog URL instead. Keeping that choice
    // here, at the common Bose boundary, also covers seeks, skips and episode
    // changes made after Bose has already been selected.
    const catalogEpisode = catalogRef.current
      .flatMap((source) => source.episodes)
      .find((episode) => episode.id === item.id && /^https?:\/\//i.test(episode.audioUrl))
    const favoriteEpisode = favoriteQueueRef.current
      ?.find((entry) => entry.episode.id === item.id && /^https?:\/\//i.test(entry.episode.audioUrl))
      ?.episode
    const remoteUrl = catalogEpisode?.audioUrl || favoriteEpisode?.audioUrl
    if (!remoteUrl) {
      throw new Error('La copie hors ligne est lisible sur le téléphone, mais son adresse Internet est introuvable pour la Bose')
    }
    return { ...item, url: remoteUrl }
  }

  function resetBoseClock(positionSeconds: number, playing: boolean, remotePositionSeconds = 0) {
    boseClockRef.current = {
      positionSeconds: Math.max(0, positionSeconds),
      updatedAt: Date.now(),
      playing,
      remotePositionSeconds: Math.max(0, remotePositionSeconds),
    }
  }

  function currentBoseClockPosition(now = Date.now()) {
    const clock = boseClockRef.current
    if (!clock.updatedAt) return Math.max(0, clock.positionSeconds)
    return Math.max(0, clock.positionSeconds + (clock.playing ? (now - clock.updatedAt) / 1000 : 0))
  }

  function syncBoseClock(remotePositionSeconds: number, playing: boolean) {
    const now = Date.now()
    const clock = boseClockRef.current
    const remote = Math.max(0, remotePositionSeconds)
    let position = currentBoseClockPosition(now)
    const remoteAdvanced = remote > 0 && (
      clock.remotePositionSeconds <= 0
      || remote > clock.remotePositionSeconds + 0.25
      || remote < clock.remotePositionSeconds - 1
    )
    if (remoteAdvanced) {
      position = Math.max(
        0,
        boseStartPositionRef.current + remote - boseContentOffsetRef.current,
      )
    }
    boseClockRef.current = {
      positionSeconds: position,
      updatedAt: now,
      playing,
      remotePositionSeconds: remote,
    }
    return position
  }

  function persistBoseClock(
    positionSeconds: number,
    playing: boolean,
    item = nowPlayingRef.current,
    durationSeconds = globalDuration,
  ) {
    const stored = loadBoseSession()
    if (!stored || !item) return
    localStorage.setItem('podmix-bose-session-v1', JSON.stringify({
      ...stored,
      item,
      contentOffsetSeconds: boseContentOffsetRef.current,
      streamStartSeconds: boseStartPositionRef.current,
      durationSeconds,
      positionSeconds: Math.max(0, positionSeconds),
      playing,
      savedAt: Date.now(),
    } satisfies StoredBoseSession))
  }

  useEffect(() => {
    if (!podmixPlayer.isNative) return
    const listener = CapacitorApp.addListener('backButton', () => {
      if (showOutputPicker) {
        setShowOutputPicker(false)
      } else if (showAddSource) {
        setShowAddSource(false)
      } else if (showSearch) {
        setShowSearch(false)
      } else if (selectedEpisodeId) {
        const source = catalogRef.current.find((item) => item.id === selectedSourceId)
        if (source?.kind === 'dj') {
          setSelectedEpisodeId('')
          setSelectedSourceId('')
          setActiveView('djLibrary')
        } else {
          setSelectedEpisodeId('')
        }
      } else if (selectedSourceId) {
        setSelectedSourceId('')
      } else if (activeView !== 'home') {
        setActiveView('home')
      } else {
        void CapacitorApp.minimizeApp()
      }
    })
    return () => {
      void listener.then((handle) => handle.remove()).catch(() => undefined)
    }
  }, [activeView, selectedEpisodeId, selectedSourceId, showAddSource, showOutputPicker, showSearch])
  const [settings, setSettings] = useState<AppSettings>(() => {
    try {
      return { ...defaultSettings, ...JSON.parse(localStorage.getItem('podmix-settings-v1') ?? '') } as AppSettings
    } catch {
      return defaultSettings
    }
  })
  const [backupMessage, setBackupMessage] = useState('')
  const [refreshingSource, setRefreshingSource] = useState(false)
  const [serverUrl, setServerUrlState] = useState(getApiUrl)
  const [serverMessage, setServerMessage] = useState('')
  const [studioEpisode, setStudioEpisode] = useState<{ sourceId: string; episodeId: string }>()
  const studioEpisodeRef = useRef<{ sourceId: string; episodeId: string } | undefined>(undefined)
  const [publishMessage, setPublishMessage] = useState('')
  const [, setStorage] = useState({ downloadedBytes: 0, availableBytes: 0, totalBytes: 0 })
  const [availableUpdate, setAvailableUpdate] = useState<UpdateManifest | null>(null)
  const [appInfo, setAppInfo] = useState<{ versionName: string; versionCode: number; lastUpdateTime: number }>({
    versionName: currentVersion,
    versionCode: 0,
    lastUpdateTime: 0,
  })
  const [updateMessage, setUpdateMessage] = useState('Recherche automatique…')
  const [updateProgress, setUpdateProgress] = useState<UpdateProgress | null>(null)

  useEffect(() => () => waveRef.current?.destroy(), [])
  useEffect(() => {
    void podmixPlayer.getAppInfo().then(setAppInfo).catch(() => undefined)
  }, [])
  useEffect(() => {
    const readPlaybackTarget = () => {
      void podmixPlayer.getPendingPlaybackTarget()
        .then((target) => { if (target.sourceId) setPendingPlaybackTarget(target) })
        .catch(() => undefined)
    }
    readPlaybackTarget()
    if (!podmixPlayer.isNative) return
    const listener = CapacitorApp.addListener('appStateChange', ({ isActive }) => {
      if (isActive) readPlaybackTarget()
    })
    return () => { void listener.then((handle) => handle.remove()).catch(() => undefined) }
  }, [])
  useEffect(() => {
    if (!pendingPlaybackTarget) return
    const source = catalog.find((item) => item.id === pendingPlaybackTarget.sourceId)
    if (!source) return
    setActiveView('home')
    setSelectedSourceId(source.id)
    // La notification sert de raccourci vers la page de la source. L'épisode
    // reste jouable dans son contexte, sans imposer l'écran de lecture.
    setSelectedEpisodeId('')
    setPendingPlaybackTarget(undefined)
  }, [catalog, pendingPlaybackTarget])
  useEffect(() => {
    if (!podmixPlayer.isNative) return
    const listener = CapacitorApp.addListener('appStateChange', ({ isActive }) => {
      if (!isActive) {
        // A telephone call can suspend the WebView while the SoundTouch
        // output is paused by its audio route. Remember only a genuinely
        // playing session: a user pause must remain a pause after the call.
        boseResumeAfterInterruptionRef.current = Boolean(
          boseActiveRef.current && loadBoseSession()?.playing,
        )
        return
      }
      if (!boseResumeAfterInterruptionRef.current) return
      boseResumeAfterInterruptionRef.current = false
      window.setTimeout(() => {
        void (async () => {
          if (!boseActiveRef.current) return
          const ip = boseIp.trim()
          if (!ip) return
          try {
            const state = await podmixPlayer.boseGetState(ip)
            const position = syncBoseClock(state.positionSeconds ?? 0, Boolean(state.playing))
            if (!state.playing) {
              const stored = loadBoseSession()
              if (!stored?.item) return
              // A bare PLAY can leave old SoundTouch firmware in PLAY_STATE
              // with a frozen HTTP stream. Reopen the relay at the saved
              // position, exactly like the normal pause/resume button.
              await sendToBose(
                stored.item,
                stored.contentOffsetSeconds + position,
                stored.contentOffsetSeconds,
                ip,
                stored.name,
              )
              setBoseMessage('Lecture Bose rétablie après l’interruption')
              return
            }
            resetBoseClock(position, true, state.positionSeconds ?? 0)
            persistBoseClock(position, true)
            setGlobalPosition(position)
            setGlobalPlaying(true)
            setBoseMessage('Lecture reprise après l’appel')
          } catch {
            setBoseMessage('Reprise Bose impossible après l’appel')
          }
        })()
      }, 900)
    })
    return () => { void listener.then((handle) => handle.remove()).catch(() => undefined) }
  }, [boseIp])
  useEffect(() => {
    checkForUpdate()
      .then((update) => {
        setAvailableUpdate(update)
        setUpdateMessage(update ? `Version ${update.versionName} disponible` : 'Application à jour')
      })
      .catch(() => setUpdateMessage('Vérification impossible hors connexion'))
  }, [])
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setShowSearch(true)
      }
      if (event.key === 'Escape') {
        setShowSearch(false)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])
  useEffect(() => {
    if (!showAddSource || !['rss', 'show'].includes(sourceMode)) return
    const query = podcastQuery.trim()
    if (query.length < 2) {
      setPodcastResults([])
      setSearchingPodcasts(false)
      return
    }
    let cancelled = false
    // Le délai évite une requête à chaque frappe, mais l'indicateur doit être
    // immédiat : sinon la recherche donne l'impression d'avoir figé l'écran.
    setSearchingPodcasts(true)
    const timer = window.setTimeout(() => {
      setFeedError('')
      setPodcastResults([])
      void searchPodcasts(query)
        .then((results) => { if (!cancelled) setPodcastResults(results) })
        .catch((error) => { if (!cancelled) setFeedError(error instanceof Error ? error.message : 'Recherche impossible') })
        .finally(() => { if (!cancelled) setSearchingPodcasts(false) })
    }, 350)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [podcastQuery, showAddSource, sourceMode])
  useEffect(() => {
    if (!showAddSource || sourceMode !== 'radio') return
    const query = radioQuery.trim()
    if (query.length < 2) {
      setRadioResults([])
      setSearchingRadios(false)
      return
    }
    let cancelled = false
    setSearchingRadios(true)
    const timer = window.setTimeout(() => {
      setFeedError('')
      void searchRadios(query)
        .then((results) => { if (!cancelled) setRadioResults(results) })
        .catch((error) => { if (!cancelled) setFeedError(error instanceof Error ? error.message : 'Recherche impossible') })
        .finally(() => { if (!cancelled) setSearchingRadios(false) })
    }, 350)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [radioQuery, showAddSource, sourceMode])
  useEffect(() => {
    // La recherche DJ doit se comporter comme celles des podcasts et radios :
    // elle démarre après une courte pause de saisie, sans obliger à toucher le
    // bouton « Rechercher » sur le téléphone.
    const visible = activeView === 'liveSets' || (showAddSource && sourceMode === 'dj')
    if (!visible) return
    const query = liveSetQuery.trim()
    if (query.length < 2) {
      setLiveSetResults([])
      setLiveSetError('')
      setLiveSetSearching(false)
      return
    }
    let cancelled = false
    setLiveSetSearching(true)
    const timer = window.setTimeout(() => {
      setLiveSetError('')
      void searchLiveSets(query, liveSetResultLimit)
        .then((results) => { if (!cancelled) setLiveSetResults(results) })
        .catch((error) => { if (!cancelled) setLiveSetError(error instanceof Error ? error.message : 'Recherche de live sets indisponible') })
        .finally(() => { if (!cancelled) setLiveSetSearching(false) })
    }, 450)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [activeView, liveSetQuery, liveSetResultLimit, showAddSource, sourceMode])
  const webWorkerBusy = useRef(false)
  useEffect(() => {
    // Le service natif HTTP seul ne doit pas décider d'un échec : certaines
    // pages 1001 publient les repères uniquement après rendu JavaScript.
    // La voie WebView existante reste donc l'autorité jusqu'à ce que le rendu
    // en service de fond soit couvert par des tests réels.
    let cancelled = false
    const processWebJob = async () => {
      if (cancelled || webWorkerBusy.current) return
      webWorkerBusy.current = true
      try {
        const job = await claimWebTimestampJob()
        if (!job) { webWorkerBusy.current = false; return }
        const claimedSource = catalogRef.current.find((source) => source.episodes.some((episode) => episode.id === job.episodeId))
        if (claimedSource) {
          persistEpisodeAnalysis({ sourceId: claimedSource.id, episodeId: job.episodeId }, {
            id: job.id,
            episodeId: job.episodeId,
            status: 'running',
            stage: 'WebView sources Web en cours',
            progress: 70,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
            tracks: [],
            operation: 'research',
          })
        }
        const nativeSearchQueries = native1001SearchQueries(job.title, claimedSource)
        let candidates = rank1001Candidates([...(job.webCandidates ?? [])]
          .filter((item, index, all) => (
            item.domain.endsWith('1001tracklists.com')
            && all.findIndex((candidate) => candidate.url === item.url) === index
          )), job.title)
        const failures: string[] = []
        let completedJob = false
        const withTimeout = async <T,>(promise: Promise<T>, milliseconds: number, message: string): Promise<T> => (
          Promise.race([
            promise,
            new Promise<never>((_, reject) => window.setTimeout(
              () => reject(new Error(message)),
              milliseconds,
            )),
          ])
        )
        const publishedLinks = [...(job.publishedLinks ?? [])].sort((left, right) => {
          const priority = (url: string) => (
            /soundcloud\.com|mixcloud\.com/i.test(url) ? 0
              : /youtu\.be|youtube\.com/i.test(url) ? 1
                : 2
          )
          return priority(left) - priority(right)
        })
        for (const publishedUrl of publishedLinks.slice(0, 3)) {
          try {
            console.info('[Podmix] Source publiée testée', publishedUrl)
            const result = await withTimeout(
              podmixPlayer.fetchPublishedTracklist(publishedUrl),
              20000,
              `Timeout source publiée : ${publishedUrl}`,
            )
            console.info('[Podmix] Source publiée lue', publishedUrl, result.tracks.length)
            console.info('[Podmix] Payload timestamps publié → VPS', {
              sourceUrl: result.sourceUrl,
              count: result.tracks.length,
              first: result.tracks[0]?.providedTime,
              second: result.tracks[1]?.providedTime,
              last: result.tracks.at(-1)?.providedTime,
              uniqueTimes: [...new Set(result.tracks.map((track) => track.providedTime))],
            })
            if (result.tracks.length < 3) continue
            const completed = await completeWebTimestampJob(job.id, result.sourceUrl, result.tracks.map((track) => ({
              artist: track.artist,
              title: track.title,
              providedTime: track.providedTime,
            })))
            if (claimedSource) {
              persistEpisodeTracklistUrl({ sourceId: claimedSource.id, episodeId: completed.episodeId }, result.sourceUrl)
              persistEpisodeAnalysis({ sourceId: claimedSource.id, episodeId: completed.episodeId }, completed)
            }
            completedJob = true
            break
          } catch (error) {
            const message = error instanceof Error ? error.message : String(error)
            console.warn('[Podmix] Source publiée rejetée', publishedUrl, message)
            failures.push(`${publishedUrl} : ${message}`)
          }
        }
        // Recherche native 1001 systématique : seul le téléphone dispose ici d'un accès fiable.
        for (const searchQuery of nativeSearchQueries) {
          try {
            console.info('[Podmix] Recherche native 1001 sur téléphone :', searchQuery)
            const nativeCandidates = (await withTimeout(
              podmixPlayer.searchTracklists1001(searchQuery),
              25000,
              `Timeout recherche 1001 : ${searchQuery}`,
            )).candidates
            candidates = rank1001Candidates([...candidates, ...nativeCandidates]
              .filter((item) => item.domain.endsWith('1001tracklists.com')), job.title)
            if (candidates.some((item) => item.url.includes('/tracklist/') && item.url.includes(episodeNumberFromTitle(job.title)))) break
          } catch (error) {
            console.warn('[Podmix] Recherche native 1001 échouée', searchQuery, error)
          }
        }
        // Fallback optionnel via l'API du serveur si le téléphone n'a rien trouvé
        if (!candidates.length) {
          for (const searchQuery of nativeSearchQueries) {
            try {
              const searchedCandidates = await searchTracklistCandidates(searchQuery)
              candidates = rank1001Candidates([...candidates, ...searchedCandidates]
                .filter((item, index, all) => (
                  item.domain.endsWith('1001tracklists.com')
                  && all.findIndex((candidate) => candidate.url === item.url) === index
                )), job.title)
              if (candidates.length) break
            } catch {
              // La recherche serveur peut être temporairement indisponible.
            }
          }
        }
        for (const candidate of completedJob ? [] : candidates.slice(0, 3)) {
          try {
            const result = await withTimeout(
              podmixPlayer.fetchTracklist1001(candidate.url, candidate.address),
              20000,
              `Timeout page 1001 : ${candidate.url}`,
            )
            console.info('[Podmix] Payload timestamps 1001 → VPS', {
              sourceUrl: result.sourceUrl,
              count: result.tracks.length,
              first: result.tracks[0]?.providedTime,
              second: result.tracks[1]?.providedTime,
              last: result.tracks.at(-1)?.providedTime,
              uniqueTimes: [...new Set(result.tracks.map((track) => track.providedTime))],
            })
            if (result.tracks.length < 3) continue
            const completed = await completeWebTimestampJob(job.id, result.sourceUrl, result.tracks.map((track) => ({
              artist: track.artist,
              title: track.title,
              providedTime: track.providedTime,
            })))
            if (claimedSource) {
              persistEpisodeTracklistUrl({ sourceId: claimedSource.id, episodeId: completed.episodeId }, result.sourceUrl)
              persistEpisodeAnalysis({ sourceId: claimedSource.id, episodeId: completed.episodeId }, completed)
            }
            completedJob = true
            break
          } catch (error) {
            const message = error instanceof Error ? error.message : String(error)
            console.warn('[Podmix] 1001 rejeté', candidate.url, message)
            failures.push(`${candidate.url} : ${message}`)
            // La page suivante candidate est essayée ; aucun contournement anti-bot.
          }
        }
        let audioFallbackQueued = false
        if (!completedJob) {
          const message = failures.length
            ? failures.slice(0, 6).join(' · ')
            : (candidates.length ? 'Aucune tracklist 1001 exploitable' : 'Aucun candidat 1001 trouvé')
          const fallbackJob = await failWebTimestampJob(job.id, message)
          audioFallbackQueued = analysisIsActive(fallbackJob.status)
          if (claimedSource) {
            persistEpisodeAnalysis({ sourceId: claimedSource.id, episodeId: fallbackJob.episodeId }, fallbackJob)
          }
        }
        // Un échec Web peut remettre le même job en file sur le mini-PC pour
        // l'analyse audio. Dans ce cas l'épisode reste observable jusqu'au
        // résultat final au lieu d'être classé terminal par le téléphone.
        if (!audioFallbackQueued) {
          terminalTimestampEpisodes.current.add(job.episodeId)
          finishAutomaticEpisodeTimestamping(job.episodeId)
        }
        void scheduleNextTimestamping()
      } catch (error) {
        console.error('[Podmix] Worker timestamps Web interrompu', error)
      } finally {
        webWorkerBusy.current = false
      }
    }
    void processWebJob()
    const timer = window.setInterval(() => void processWebJob(), 15000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [])
  function selectSourceMode(mode: 'rss' | 'show' | 'radio' | 'dj') {
    setSourceMode(mode)
    setFeedError('')
    setPodcastResults([])
    if (mode !== 'dj') setLiveSetResults([])
  }
  useEffect(() => {
    loadSession()
      .then((session) => {
        if (session) {
          setAudioName(session.audioName)
          setTracks(session.tracks)
          if (session.tracks[0]) setSelectedId(session.tracks[0].id)
        }
      })
      .finally(() => { persistenceReady.current = true })
  }, [])
  useEffect(() => {
    if (!persistenceReady.current) return
    const timer = window.setTimeout(() => { void saveSession(audioName, tracks) }, 250)
    return () => window.clearTimeout(timer)
  }, [audioName, tracks])
  useEffect(() => {
    catalogRef.current = catalog
    saveCatalog(catalog)
  }, [catalog])
  useEffect(() => {
    if (!podmixPlayer.isNative) return
    let active = true
    const artworkSources = catalog.filter((source) => Boolean(source.artworkUrl))
    if (!artworkSources.length) return
    void Promise.all(artworkSources.map(async (source) => {
      try {
        const cached = await podmixPlayer.cacheArtwork(source.artworkUrl)
        return [source.id, cached.dataUrl || cached.uri] as const
      } catch {
        // L'image distante reste utilisable tant que le téléphone est en
        // ligne ; HomeArtwork affichera le logo de secours hors connexion.
        return undefined
      }
    })).then((entries) => {
      if (!active) return
      const resolved = entries.filter((entry): entry is readonly [string, string] => Boolean(entry))
      if (!resolved.length) return
      setCachedHomeArtwork((current) => ({ ...current, ...Object.fromEntries(resolved) }))
    })
    return () => { active = false }
  }, [catalog])
  useEffect(() => {
    const limitedCatalog = catalog.map((source) => trimSourceToEpisodeLimit(source, settings))
    const removedEpisodeIds = new Set(catalog.flatMap((source, index) => {
      const kept = new Set(limitedCatalog[index].episodes.map((episode) => episode.id))
      return source.episodes.filter((episode) => !kept.has(episode.id)).map((episode) => episode.id)
    }))
    if (!removedEpisodeIds.size) return

    setCatalog(limitedCatalog)
    setOfflineEpisodes((items) => items.filter((episode) => !removedEpisodeIds.has(episode.id)))
    if (selectedEpisodeId && removedEpisodeIds.has(selectedEpisodeId)) setSelectedEpisodeId('')
    if (podmixPlayer.isNative) {
      void Promise.all([...removedEpisodeIds].map((id) => podmixPlayer.removeDownload(id).catch(() => undefined)))
    }
  }, [catalog, selectedEpisodeId, settings])
  useEffect(() => {
    // Older RSS imports could assign one ID to a whole podcast. Keep only a
    // history record that identifies its episode by audio URL/title; the
    // other rows must be shown as unread rather than borrowing that progress.
    setHistory((items) => {
      const episodes = catalog.flatMap((source) => source.episodes)
      const remapped = items.flatMap((item) => {
        const matching = episodes.filter((episode) => (
          (Boolean(item.url) && item.url === episode.audioUrl)
          || (item.title === episode.title && item.artist === catalog.find((source) => source.episodes.includes(episode))?.title)
        ))
        if (matching.length === 1) return [{ ...item, id: matching[0].id }]
        const isSharedLegacyId = episodes.filter((episode) => episode.id === item.id).length > 1
        return isSharedLegacyId ? [] : [item]
      })
      const changed = remapped.length !== items.length || remapped.some((item, index) => item.id !== items[index]?.id)
      if (!changed) return items
      historyRef.current = remapped
      savePlaybackHistory(remapped)
      return remapped
    })
  }, [catalog])
  useEffect(() => {
    if (!navigator.onLine || artworkLookupAttempted.current.size >= 60) return
    const candidates = catalog.flatMap((source) => source.episodes.flatMap((episode) =>
      (episode.tracks ?? []).flatMap((track, trackIndex) => {
        const key = favoriteTrackKey(episode.id, track, trackIndex)
        return !track.artworkUrl
          && track.artist.trim()
          && track.title.trim()
          && !artworkLookupAttempted.current.has(key)
          ? [{ key, artist: track.artist, title: track.title }]
          : []
      }),
    )).sort((left, right) =>
      Number(favoriteTrackIds.includes(right.key)) - Number(favoriteTrackIds.includes(left.key)),
    ).slice(0, Math.min(20, 60 - artworkLookupAttempted.current.size))
    if (!candidates.length) return
    candidates.forEach((candidate) => artworkLookupAttempted.current.add(candidate.key))
    void findTrackArtwork(candidates).then((items) => {
      const artworkByKey = new Map(items
        .filter((item) => item.artworkUrl)
        .map((item) => [item.key, item.artworkUrl!]))
      if (!artworkByKey.size) return
      setCatalog((sources) => sources.map((source) => ({
        ...source,
        episodes: source.episodes.map((episode) => ({
          ...episode,
          tracks: episode.tracks?.map((track, trackIndex) => {
            const artworkUrl = artworkByKey.get(favoriteTrackKey(episode.id, track, trackIndex))
            return artworkUrl ? { ...track, artworkUrl } : track
          }),
        })),
      })))
    }).catch(() => undefined)
  }, [catalog, favoriteTrackIds])
  useEffect(() => {
    if (!navigator.onLine) return
    const candidates = catalog.flatMap((source) => source.episodes.flatMap((episode) =>
      (episode.tracks ?? []).flatMap((track, trackIndex) => {
        const key = favoriteTrackKey(episode.id, track, trackIndex)
        return favoriteTrackIds.includes(key)
          && (!track.deezerUrl || !track.spotifyUrl)
          && track.artist.trim()
          && track.title.trim()
          && !linkLookupAttempted.current.has(key)
          ? [{ key, artist: track.artist, title: track.title }]
          : []
      }),
    )).slice(0, 10)
    if (!candidates.length) return
    candidates.forEach((candidate) => linkLookupAttempted.current.add(candidate.key))
    void findTrackLinks(candidates).then((items) => {
      const linksByKey = new Map(items.map((item) => [item.key, item]))
      setCatalog((sources) => sources.map((source) => ({
        ...source,
        episodes: source.episodes.map((episode) => ({
          ...episode,
          tracks: episode.tracks?.map((track, trackIndex) => {
            const key = favoriteTrackKey(episode.id, track, trackIndex)
            const links = linksByKey.get(key)
            if (!links) return track
            return {
              ...track,
              artworkUrl: track.artworkUrl || links.artworkUrl,
              deezerUrl: track.deezerUrl || links.deezerUrl,
              spotifyUrl: track.spotifyUrl || links.spotifyUrl,
            }
          }),
        })),
      })))
    }).catch(() => candidates.forEach((candidate) => linkLookupAttempted.current.delete(candidate.key)))
  }, [catalog, favoriteTrackIds, linkLookupRefresh])
  useEffect(() => {
    let stopped = false
    const refreshFeeds = async () => {
      if (!navigator.onLine) return
      const lastRefresh = Number(localStorage.getItem('podmix-last-feed-refresh') || 0)
      const hasDuplicateEpisodeIds = catalogRef.current.some((source) => {
        const ids = source.episodes.map((episode) => episode.id)
        return new Set(ids).size !== ids.length
      })
      if (!hasDuplicateEpisodeIds && Date.now() - lastRefresh < 15 * 60 * 1000) return
      const enteredEpisodes: Array<{ source: CatalogSource; episode: Episode }> = []
      let attemptedFeeds = 0
      let refreshedFeeds = 0
      const refreshed = await Promise.all(catalogRef.current.map(async (source) => {
        if (!source.feedUrl || !['podcast', 'show'].includes(source.kind)) return source
        attemptedFeeds += 1
        try {
          const next = await importRssFeed(source.feedUrl, source.kind === 'show' ? 'show' : 'podcast', source.kind === 'show' ? settings.maxShowEpisodes : settings.maxPodcastEpisodes)
          refreshedFeeds += 1
          const hasRssBaseline = rssBaselineSourceIds.current.has(source.id)
          // La première lecture d'un podcast existant pose seulement la ligne
          // de base. Ensuite, seules les entrées apparues depuis cette ligne
          // de base déclenchent un job automatique.
          const newlyArrived = hasRssBaseline ? newEpisodesFromFeed(source, next) : []
          if (settings.automaticAnalysis && source.kind !== 'show') {
            for (const episode of newlyArrived) {
              if (episode.audioUrl) enteredEpisodes.push({ source, episode })
            }
          }
          establishRssBaseline(source.id)
          const merged = mergeCatalogSource(source, next)
          if (!newlyArrived.length) return merged
          const newIds = newlyArrived.map((episode) => episode.id)
          const visibleEpisodeIds = new Set(merged.episodes.map((episode) => episode.id))
          return {
            ...merged,
            newEpisodeIds: [...new Set([...(merged.newEpisodeIds ?? []), ...newIds])].filter((id) => visibleEpisodeIds.has(id)),
            unseenEpisodeIds: [...new Set([...(merged.unseenEpisodeIds ?? []), ...newIds])].filter((id) => visibleEpisodeIds.has(id)),
          }
        } catch {
          return source
        }
      }))
      if (!stopped) {
        if (attemptedFeeds === 0 || refreshedFeeds > 0) {
          localStorage.setItem('podmix-last-feed-refresh', String(Date.now()))
        }
        setCatalog(refreshed)
        enqueueNewEpisodesForTimestamping(enteredEpisodes.map(({ episode }) => episode))
        const nextEpisode = enteredEpisodes.sort((left, right) =>
          publishedTimestamp(right.episode.publishedAt) - publishedTimestamp(left.episode.publishedAt)
        )[0]
        if (nextEpisode) {
          // Ne coupe jamais une analyse en cours. Ce verrou choisit seulement
          // le podcast qui prendra le prochain créneau de la file unique.
          activeTimestampSourceId.current = nextEpisode.source.id
          localStorage.setItem('podmix-active-timestamp-source-v1', nextEpisode.source.id)
        }
        await scheduleNextTimestamping(refreshed)
      }
    }
    const onOnline = () => { void refreshFeeds() }
    window.addEventListener('online', onOnline)
    void refreshFeeds()
    const timer = window.setInterval(refreshFeeds, 30 * 60 * 1000)
    return () => {
      stopped = true
      window.removeEventListener('online', onOnline)
      window.clearInterval(timer)
    }
  }, [settings.automaticAnalysis, settings.maxPodcastEpisodes, settings.maxShowEpisodes])
  useEffect(() => {
    if (settings.automaticAnalysis) void scheduleNextTimestamping()
  }, [settings.automaticAnalysis])
  useEffect(() => {
    if (!settings.automaticAnalysis) return
    const timer = window.setTimeout(() => void scheduleNextTimestamping(), 250)
    return () => window.clearTimeout(timer)
  }, [catalog, settings.automaticAnalysis])
  useEffect(() => {
    if (!selectedSourceId || !settings.automaticAnalysis) return
    const source = catalogRef.current.find((item) => item.id === selectedSourceId)
    if (!source || source.kind === 'radio' || source.kind === 'show') return
    // Ouvrir un podcast donne la main à sa file. Le job déjà en cours n'est
    // jamais interrompu ; ce choix s'applique au prochain épisode à lancer.
    activeTimestampSourceId.current = source.id
    localStorage.setItem('podmix-active-timestamp-source-v1', source.id)
    void scheduleNextTimestamping([source, ...catalogRef.current.filter((item) => item.id !== source.id)])
  }, [selectedSourceId, settings.automaticAnalysis])
  useEffect(() => localStorage.setItem('podmix-track-favorites-v1', JSON.stringify(favoriteTrackIds)), [favoriteTrackIds])
  useEffect(() => {
    if (!podmixPlayer.isNative) return
    let active = true
    const restoreNativeFavorites = async () => {
      try {
        const native = await podmixPlayer.getFavorites()
        if (active && native.initialized) setFavoriteTrackIds(native.ids)
      } finally {
        if (active) setNativeFavoritesReady(true)
      }
    }
    void restoreNativeFavorites()
    const listener = CapacitorApp.addListener('appStateChange', ({ isActive }) => {
      if (isActive) {
        void restoreNativeFavorites()
        void syncTimestampJobStatuses()
      }
    })
    return () => {
      active = false
      void listener.then((handle) => handle.remove()).catch(() => undefined)
    }
  }, [])
  useEffect(() => {
    if (!catalog.length) return
    setFavoriteTrackIds((items) => {
      const migrated = items.flatMap((id) => {
        if (id.includes('::track::')) return [id]
        const legacyId = Number(id)
        if (!Number.isFinite(legacyId)) return [id]
        const matches = catalog.flatMap((source) => source.episodes.flatMap((episode) =>
          (episode.tracks ?? []).flatMap((track, index) =>
            track.id === legacyId ? [favoriteTrackKey(episode.id, track, index)] : []),
        ))
        return matches.length ? matches : [id]
      })
      const unique = [...new Set(migrated)]
      return unique.length === items.length && unique.every((id, index) => id === items[index])
        ? items
        : unique
    })
  }, [catalog])
  useEffect(() => {
    historyRef.current = history
    savePlaybackHistory(history)
  }, [history])
  useEffect(() => {
    completedEpisodeIdsRef.current = completedEpisodeIds
    saveCompletedEpisodeIds(completedEpisodeIds)
  }, [completedEpisodeIds])
  useEffect(() => saveListeningSessions(listeningSessions), [listeningSessions])

  // Older completed plays may predate the dedicated completed-episodes list.
  // Recover them from a session only when it truly reached the end; never use
  // a partial position as a "read" indicator.
  useEffect(() => {
    const recovered = catalog.flatMap((source) => source.episodes.flatMap((episode) => {
      const catalogDuration = parseDuration(episode.duration)
      const completed = listeningSessions.some((session) => {
        if (session.id !== episode.id) return false
        const duration = Math.max(catalogDuration, session.duration ?? 0)
        return duration > 0 && session.position / duration >= 0.98
      })
      return completed ? [episode.id] : []
    }))
    if (!recovered.length) return
    setCompletedEpisodeIds((ids) => {
      const next = [...new Set([...recovered, ...ids])].slice(0, 2_000)
      return next.length === ids.length ? ids : next
    })
  }, [catalog, listeningSessions])

  function markEpisodeCompleted(id: string) {
    if (completedEpisodeIdsRef.current.includes(id)) return
    const next = [id, ...completedEpisodeIdsRef.current].slice(0, 2_000)
    completedEpisodeIdsRef.current = next
    saveCompletedEpisodeIds(next)
    setCompletedEpisodeIds(next)
  }

  function pushHistoryItem(item: HistoryItem) {
    const next = [item, ...historyRef.current.filter((h) => h.id !== item.id)].slice(0, 1_000)
    historyRef.current = next
    setHistory(next)
    setListeningSessions((sessions) => {
      const updated = updateListeningSessions(sessions, item)
      saveListeningSessions(updated)
      return updated
    })
  }

  // L'historique court peut être purgé lorsqu'un flux RSS est réactualisé ou
  // lorsqu'un épisode se termine. Les sessions d'écoute, elles, gardent le
  // dernier point durablement. Tous les boutons de reprise doivent consulter
  // les deux : c'est particulièrement important pour les émissions, qui ne
  // disposent pas de tracklist pour reconstruire leur position.
  function savedResumeForEpisode(episode: Episode) {
    const historyItem = historyRef.current.find((item) => item.id === episode.id)
    const sessionItem = listeningSessions
      .filter((item) => item.id === episode.id)
      .sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt))[0]
    const historyAt = historyItem ? Date.parse(historyItem.playedAt) : 0
    const sessionAt = sessionItem ? Date.parse(sessionItem.updatedAt) : 0
    const latest = sessionAt >= historyAt ? sessionItem : historyItem
    const position = Math.max(0, latest?.position ?? 0)
    const duration = Math.max(parseDuration(episode.duration), latest?.duration ?? 0)
    return {
      position: duration > 0 && position / duration >= 0.98 ? 0 : position,
      duration,
    }
  }
  useEffect(() => { nowPlayingRef.current = nowPlaying }, [nowPlaying])
  useEffect(() => localStorage.setItem('podmix-bose-ip', boseIp), [boseIp])
  useEffect(() => {
    if (podmixPlayer.isNative) void restoreOutputAfterLaunch()
    // Si la SoundTouch est réellement encore en lecture, sa session mémorisée
    // est une continuité de lecture, pas une nouvelle diffusion automatique.
    // Sinon la sortie locale reste le comportement de base.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  useEffect(() => localStorage.setItem('podmix-settings-v1', JSON.stringify(settings)), [settings])
  useEffect(() => {
    offlineEpisodesRef.current = offlineEpisodes
    saveOfflineEpisodes(offlineEpisodes)
  }, [offlineEpisodes])
  useEffect(() => {
    if (!podmixPlayer.isNative) return
    void podmixPlayer.getCompletedEpisodeIds()
      .then((nativeIds) => {
        setCompletedEpisodeIds((currentIds) => [
          ...new Set([...nativeIds, ...currentIds]),
        ].slice(0, 2_000))
      })
      .catch((error) => console.error("Récupération des épisodes lus Android impossible", error))
  }, [])
  useEffect(() => {
    if (!podmixPlayer.isNative) return
    const offlineById = new Map(offlineEpisodes.filter((item) => item.status === 'completed' && item.localUri).map((item) => [item.id, item]))
    const library: LibraryItem[] = catalog.flatMap<LibraryItem>((source) => {
      if (source.kind === 'radio' && source.streamUrl) {
        return [{
          id: `source::${source.id}`,
          parentId: 'podmix-root',
          groupId: source.id,
          kind: 'radio',
          url: source.streamUrl,
          title: source.title,
          artist: 'Radio en direct',
          artworkUrl: source.artworkUrl,
          browsable: false,
          playable: true,
        }]
      }
      const sourceId = `source::${source.id}`
      const episodes = source.episodes.flatMap((episode) => {
        const offline = offlineById.get(episode.id)
        // La migration des anciennes sessions terminées alimente déjà
        // completedEpisodeIds. Ne jamais relire listeningSessions ici : cette
        // collection évolue pendant la lecture et ferait reconstruire puis
        // sérialiser les milliers d'entrées Android Auto à chaque seconde.
        const completed = completedEpisodeIdsRef.current.includes(episode.id)
        // Android Auto n'offre pas de couleur ou de barre de progression dans
        // ses listes. Sur certains autoradios le sous-titre est masqué : le
        // repère doit donc être dans le titre visible, pas seulement dessous.
        const episodeArtist = completed
          ? `✓ Lu · ${source.title}`
          : source.title
        const episodeTitle = completed ? `${episode.title} · ✓ Lu` : episode.title
        // Android Auto peut ouvrir un set déjà importé avant le correctif.
        // On remplace alors son ancienne URL YouTube directe par le relais DJ.
        const url = offline?.localUri || (source.kind === 'dj' && episode.sourceUrl ? liveSetStreamUrl(episode.sourceUrl) : episode.audioUrl)
        if (!url) return []
        if (!episode.tracks?.length) {
          return [{
            id: `episode::${episode.id}`,
            parentId: sourceId,
            groupId: source.id,
            kind: source.kind,
            url,
            title: episodeTitle,
            artist: episodeArtist,
            artworkUrl: episode.artworkUrl || source.artworkUrl,
            // Even an émission without a tracklist needs an episode page in
            // Android Auto: it is where the listener chooses 00:00 or the
            // saved resume point. Directly playable entries always start at 0.
            browsable: true,
            playable: false,
          }]
        }
        const episodeId = `episode::${episode.id}`
        const trackItems = episode.tracks.map((track, index) => {
          const nextTime = episode.tracks?.[index + 1]?.time
          const mediaId = favoriteTrackKey(episode.id, track, index)
          // Offline: use clipping, Online: use segment proxy (URL will be replaced at playback time)
          return {
            id: mediaId,
            parentId: episodeId,
            groupId: episode.id,
            kind: source.kind,
            favoriteId: mediaId,
            url, // Full episode URL, will be replaced with segment URL at playback if online
            title: track.title,
            artist: track.artist || source.title,
            artworkUrl: trackArtwork(track, episode, source),
            browsable: false,
            playable: true,
            startPositionSeconds: Math.max(0, track.time), // For offline clipping
            ...(nextTime !== undefined && nextTime > track.time
              ? { endPositionSeconds: nextTime }
              : {}),
            trackStartTime: track.time, // Store for segment proxy
            trackEndTime: nextTime, // Store for segment proxy
          }
        })
        return [{
          id: episodeId,
          parentId: sourceId,
          groupId: source.id,
          kind: source.kind,
          url,
          title: episodeTitle,
          artist: episodeArtist,
          artworkUrl: episode.artworkUrl || source.artworkUrl,
          browsable: true,
          // Android Auto must open an episode to expose its track list.  Making
          // this container playable turns transport next/previous into episode
          // navigation instead of the tracks it contains.
          playable: false,
        }, ...trackItems]
      })
      if (!episodes.length) return []
      return [{
        id: sourceId,
        parentId: 'podmix-root',
        kind: source.kind,
        title: source.title,
        artist: source.kind === 'podcast' ? 'Podcast' : source.kind === 'show' ? 'Émission' : 'DJ set',
        artworkUrl: source.artworkUrl,
        browsable: true,
        playable: false,
      }, ...episodes]
    })
    // Artwork/link enrichment can update the React catalogue several times in
    // quick succession. Debounce and fingerprint the native representation so
    // a 2.5 MB Android Auto library is committed once, not on every enrichment.
    const signature = JSON.stringify(library)
    if (signature === nativeLibrarySignatureRef.current) return
    const timer = window.setTimeout(() => {
      nativeLibrarySignatureRef.current = signature
      void podmixPlayer.syncLibrary(library)
        .then((result) => console.info(`Bibliothèque Android Auto synchronisée : ${result.count} éléments`))
        .catch((error) => {
          if (nativeLibrarySignatureRef.current === signature) nativeLibrarySignatureRef.current = ''
          console.error('Synchronisation Android Auto impossible', error)
        })
    }, 2_000)
    return () => window.clearTimeout(timer)
  }, [catalog, offlineEpisodes, completedEpisodeIds])
  useEffect(() => {
    if (!podmixPlayer.isNative || !nativeFavoritesReady) return
    void podmixPlayer.syncFavorites(favoriteTrackIds)
      .catch((error) => console.error('Synchronisation des favoris Android Auto impossible', error))
  }, [favoriteTrackIds, nativeFavoritesReady])
  useEffect(() => {
    if (!podmixPlayer.isNative) return
    void podmixPlayer.syncSubscriptions(
      catalog
        .filter((source) => source.feedUrl && ['podcast', 'show'].includes(source.kind))
        .map((source) => ({ id: source.id, title: source.title, feedUrl: source.feedUrl! })),
    ).catch((error) => console.error('Synchronisation des abonnements Android impossible', error))
  }, [catalog])
  useEffect(() => {
    if (!podmixPlayer.isNative) return
    const resumeItems: Array<{ id: string; episodeId: string; title: string; artist: string; url: string; artworkUrl: string; positionSeconds: number; durationSeconds: number }> = catalog
      .filter((source) => ['podcast', 'show', 'dj'].includes(source.kind))
      .flatMap((source) => source.episodes.map((episode) => ({ source, episode, resume: savedResumeForEpisode(episode) })))
      .filter(({ resume }) => resume.position > 1)
      .sort((left, right) => right.resume.position - left.resume.position)
      .slice(0, 10)
      .map(({ source, episode, resume }) => {
        return {
          id: `resume::${episode.id}`,
          episodeId: episode.id,
          title: episode.title,
          artist: source.title,
          url: episode.audioUrl,
          artworkUrl: episode.artworkUrl || source.artworkUrl,
          positionSeconds: resume.position,
          durationSeconds: resume.duration,
        }
      })
    // Android Auto and the phone share the native completion store. Sending
    // only unfinished resumes left Android Auto with an old position after an
    // episode had been completed in the WebView.
    void podmixPlayer.syncResume(resumeItems, completedEpisodeIds)
      .catch((error) => console.error("Synchronisation de la reprise Android Auto impossible", error))
  }, [catalog, history, listeningSessions, completedEpisodeIds])
  useEffect(() => {
    void podmixPlayer.getStorage().then(setStorage).catch(() => undefined)
  }, [offlineEpisodes])
  useEffect(() => {
    if (!podmixPlayer.isNative) return
    const listener = podmixPlayer.onStateChanged((state) => {
      if (!isBoseOutputActive()) {
        setGlobalPlaying(playbackIntentActive(state))
        setGlobalPosition(Math.max(0, state.positionSeconds))
        setGlobalDuration(Math.max(0, state.durationSeconds))
      }
      setCanSkipNext(state.hasNext)
      setCanSkipPrevious(state.hasPrevious)
      setActiveMediaId(resolveActiveMediaId(state))
      if (state.error) setDownloadMessage(`Lecture impossible : ${state.error}`)
      if (studioEpisodeRef.current && state.mediaId === studioEpisodeRef.current.episodeId) {
        setCurrentTime(state.positionSeconds)
        if (state.durationSeconds > 0) setDuration(state.durationSeconds)
        setIsPlaying(state.playing)
      }
      if (state.mediaId && state.title) {
        const trackQueue = trackQueueRef.current
        const favoriteItem = favoriteQueueEntryForState(state)
        const activeTrack = trackQueue && state.queueIndex >= 0 && state.queueIndex < trackQueue.mediaIds.length ? trackQueue.tracks[state.queueIndex] : undefined
        const queued = catalogRef.current
          .flatMap((source) => source.episodes.map((episode) => ({ ...episode, artist: source.title })))
          .find((episode) => episode.id === state.mediaId)
        setNowPlaying((current) => {
          const next = {
          id: favoriteItem?.episode.id ?? (activeTrack ? trackQueue!.episodeId : normalizeEpisodeMediaId(state.mediaId)),
          title: favoriteItem?.track.title ?? activeTrack?.title ?? state.title,
          artist: favoriteItem?.track.artist ?? activeTrack?.artist ?? state.artist,
          url: favoriteItem
            ? preferredEpisodePlaybackUrl(favoriteItem.episode.id, favoriteItem.episode.audioUrl)
            : trackQueue?.audioUrl ?? queued?.audioUrl ?? current?.url ?? '',
          scope: favoriteItem ? 'favorite' : activeTrack ? (episodePlaybackRef.current ? 'episode' : 'track') : current?.scope ?? 'episode',
          artworkUrl: favoriteItem
            ? trackArtwork(favoriteItem.track, favoriteItem.episode, favoriteItem.source)
            : activeTrack
              ? trackQueue?.artworkUrls[state.queueIndex]
              : queued?.artworkUrl ?? current?.artworkUrl,
          } as NowPlayingItem
          return sameNowPlaying(current, next) ? current : next
        })
      }
      if ((!playbackIntentActive(state) && state.playbackState !== 1) || state.playbackState === 4) {
        persistPlaybackState(state, true)
      }
    })
    return () => { void listener.then((handle) => handle.remove()).catch(() => undefined) }
  }, [])
  useEffect(() => {
    // The Android service can keep playing while the WebView is recreated.
    // Rebuild the track queue from its media ID so opening an episode after a
    // restart highlights the exact track being resumed straight away.
    if (!podmixPlayer.isNative || !catalog.length) return
    let cancelled = false
    void podmixPlayer.getState().then((state) => {
      if (cancelled || !state.mediaId) return
      // Une file de favoris emploie volontairement les mêmes identifiants de
      // morceau que la tracklist d'un épisode. Lors d'une mise à jour du
      // catalogue, il ne faut donc pas la reconstruire comme une file
      // d'épisode : cela écrasait, après quelques secondes, le titre favori
      // du mini-lecteur par un titre de l'épisode.
      if (favoriteQueueRef.current?.some((entry) => entry.key === state.mediaId)) return
      const restored = restoreTrackQueueForMediaId(state.mediaId)
      if (!restored) return
      const activeTrack = restored.queue.tracks[restored.trackIndex]
      setActiveMediaId(state.mediaId)
      setGlobalPosition(Math.max(0, state.positionSeconds))
      setGlobalDuration(Math.max(0, state.durationSeconds))
      setGlobalPlaying(playbackIntentActive(state))
      setCanSkipNext(state.hasNext)
      setCanSkipPrevious(state.hasPrevious)
      setNowPlaying({
        id: restored.queue.episodeId,
        title: activeTrack.title,
        artist: activeTrack.artist || restored.queue.sourceTitle,
        url: restored.queue.audioUrl,
        scope: 'track',
        artworkUrl: restored.queue.artworkUrls[restored.trackIndex],
      })
    }).catch(() => undefined)
    return () => { cancelled = true }
  }, [catalog])
  useEffect(() => {
    const timer = window.setInterval(async () => {
      if (!nowPlayingRef.current) return
      if (isBoseOutputActive()) {
        // A state request performs three HTTP calls on older SoundTouch
        // speakers. Never start a second poll while the first one is pending:
        // overlapping requests made a healthy speaker look unreachable.
        // The replacement itself also uses that same small HTTP control plane;
        // polling during it can race the second Stop/SetURI/Play sequence.
        if (bosePollPendingRef.current || boseTransferPendingRef.current) return
        bosePollPendingRef.current = true
        const pollGeneration = playbackRequestRef.current
        try {
          const state = await podmixPlayer.boseGetState(boseIp.trim())
          if (pollGeneration !== playbackRequestRef.current) return
          const previousFailures = bosePollFailuresRef.current
          const interruptedSession = previousFailures >= 3 ? loadBoseSession() : undefined
          bosePollFailuresRef.current = 0
          if (!state.playing && interruptedSession?.playing && interruptedSession.item) {
            // The selected route and its last confirmed position survive a
            // Wi-Fi/mini-PC outage. Once the speaker answers again, rebuild
            // the relay instead of falling back to the phone or issuing the
            // unreliable SoundTouch PLAY command.
            await sendToBose(
              interruptedSession.item,
              interruptedSession.contentOffsetSeconds + (interruptedSession.positionSeconds ?? 0),
              interruptedSession.contentOffsetSeconds,
              boseIp.trim(),
              interruptedSession.name,
            )
            setBoseMessage('Connexion revenue · lecture Bose rétablie')
            return
          }
          let relativePosition = syncBoseClock(state.positionSeconds ?? 0, Boolean(state.playing))
          relativePosition = await advanceBoseTrackIfNeeded(relativePosition, Boolean(state.playing))
          if (pollGeneration !== playbackRequestRef.current) return
          setGlobalPlaying(Boolean(state.playing))
          setGlobalPosition(relativePosition)
          if (Date.now() - bosePersistedAtRef.current >= 5000) {
            const localState = await podmixPlayer.getState()
            persistPlaybackState({
              ...localState,
              positionSeconds: relativePosition,
              absolutePositionSeconds: boseContentOffsetRef.current + relativePosition,
              playing: Boolean(state.playing),
              playRequested: Boolean(state.playing),
            })
            persistBoseClock(relativePosition, Boolean(state.playing))
            bosePersistedAtRef.current = Date.now()
          }
        } catch {
          bosePollFailuresRef.current += 1
          if (bosePollFailuresRef.current >= 3) {
            await fallbackUnavailableBoseToPhone()
          } else {
            setBoseMessage('Connexion avec la Bose interrompue · nouvelle tentative')
          }
        } finally {
          bosePollPendingRef.current = false
        }
        return
      }
      if (playerPollPendingRef.current) return
      playerPollPendingRef.current = true
      const pollGeneration = playbackRequestRef.current
      try {
        const state = await podmixPlayer.getState().catch(() => null)
        if (!state) {
          setDownloadMessage('Lecteur momentanément indisponible')
          return
        }
        if (pollGeneration !== playbackRequestRef.current) return
        setGlobalPlaying(playbackIntentActive(state))
        setGlobalPosition(Math.max(0, state.positionSeconds))
        setGlobalDuration(Math.max(0, state.durationSeconds))
        setCanSkipNext(state.hasNext)
        setCanSkipPrevious(state.hasPrevious)
        restoreTrackQueueForMediaId(state.mediaId)
        setActiveMediaId(resolveActiveMediaId(state))
        const trackQueue = trackQueueRef.current
        const favoriteItem = favoriteQueueEntryForState(state)
        const activeTrack = trackQueue && state.queueIndex >= 0 && state.queueIndex < trackQueue.mediaIds.length ? trackQueue.tracks[state.queueIndex] : undefined
        if (favoriteItem) {
          const nextPlaying: NowPlayingItem = {
            id: favoriteItem.episode.id,
            title: favoriteItem.track.title,
            artist: favoriteItem.track.artist,
            url: preferredEpisodePlaybackUrl(favoriteItem.episode.id, favoriteItem.episode.audioUrl),
            scope: 'favorite',
            artworkUrl: trackArtwork(favoriteItem.track, favoriteItem.episode, favoriteItem.source),
          }
          setNowPlaying((current) => sameNowPlaying(current, nextPlaying) ? current : nextPlaying)
        }
        else if (trackQueue && activeTrack) {
          const nextPlaying: NowPlayingItem = {
            id: trackQueue.episodeId,
            title: activeTrack.title,
            artist: activeTrack.artist || trackQueue.sourceTitle,
            url: trackQueue.audioUrl,
            scope: episodePlaybackRef.current === trackQueue.episodeId ? 'episode' : 'track',
            artworkUrl: trackQueue.artworkUrls[state.queueIndex],
          }
          setNowPlaying((current) => sameNowPlaying(current, nextPlaying) ? current : nextPlaying)
        }
        persistPlaybackState(state)
      } finally {
        playerPollPendingRef.current = false
      }
    }, 1000)
    return () => window.clearInterval(timer)
  }, [boseIp])
  useEffect(() => {
    const persistCurrentPosition = () => {
      void podmixPlayer.getState().then((state) => persistPlaybackState(state, true)).catch(() => undefined)
    }
    const onVisibilityChange = () => {
      if (document.visibilityState === 'hidden') persistCurrentPosition()
    }
    window.addEventListener('pagehide', persistCurrentPosition)
    document.addEventListener('visibilitychange', onVisibilityChange)
    let nativeListener: ReturnType<typeof CapacitorApp.addListener> | undefined
    if (podmixPlayer.isNative) {
      nativeListener = CapacitorApp.addListener('appStateChange', ({ isActive }) => {
        if (!isActive) persistCurrentPosition()
      })
    }
    return () => {
      window.removeEventListener('pagehide', persistCurrentPosition)
      document.removeEventListener('visibilitychange', onVisibilityChange)
      void nativeListener?.then((handle) => handle.remove()).catch(() => undefined)
    }
  }, [])
  useEffect(() => {
    if (!podmixPlayer.isNative || !offlineEpisodes.length) return
    let cancelled = false
    const refresh = async () => {
      const results = await Promise.allSettled(
        offlineEpisodesRef.current.map((episode) => podmixPlayer.getDownload(episode.id)),
      )
      const states = results.flatMap((result) => result.status === 'fulfilled' ? [result.value] : [])
      if (cancelled) return
      setOfflineEpisodes((items) => items.map((item) => {
        const state = states.find((candidate) => candidate.id === item.id)
        if (!state || state.status === 'unknown') {
          // One failed status query must not destroy a valid local entry.
          return item
        }
        if (state.status === 'not_found') {
          // Les anciennes versions pouvaient sauvegarder l'affichage « 0 % »
          // sans conserver la requête DownloadManager. Ce n'est pas un
          // téléchargement en cours : le montrer comme erreur relançable
          // permet de repartir proprement, sans masquer le problème.
          return {
            ...item,
            status: 'failed',
            localUri: undefined,
            bytesDownloaded: 0,
            totalBytes: undefined,
          }
        }
        return {
          ...item,
          status: state.status,
          localUri: state.status === 'completed' ? state.localUri : undefined,
          bytesDownloaded: state.bytesDownloaded,
          totalBytes: state.totalBytes,
        }
      }))
    }
    void refresh().catch(() => undefined)
    const timer = window.setInterval(() => { void refresh().catch(() => undefined) }, 2000)
    return () => { cancelled = true; window.clearInterval(timer) }
  }, [offlineEpisodes.length])

  // Le gestionnaire Android continue les téléchargements même si la WebView est
  // relancée. On restaure donc son état ici, afin que la ligne de l'épisode et
  // la section « Hors connexion » restent toujours synchronisées.
  useEffect(() => {
    if (!podmixPlayer.isNative || !catalog.length) return
    let cancelled = false
    void podmixPlayer.listDownloads().then(({ downloads }) => {
      if (cancelled) return
      const episodes = new Map<string, { episode: Episode; source: CatalogSource }>()
      for (const source of catalog) {
        for (const episode of source.episodes) episodes.set(episode.id, { episode, source })
      }
      setOfflineEpisodes((current) => {
        const byId = new Map(current.map((item) => [item.id, item]))
        for (const state of downloads) {
          if (state.status === 'not_found' || state.status === 'unknown') continue
          const match = episodes.get(state.id)
          // Les DJ sets n'ont pas toujours d'URL audio directe : leur URL
          // YouTube/SoundCloud est transformée par le relais Podmix. Ne pas
          // les exclure ici, sinon l'état « hors connexion » disparaît de la
          // liste après un redémarrage de l'application.
          if (!match || (!match.episode.audioUrl && !(match.source.kind === 'dj' && match.episode.sourceUrl))) continue
          const remoteUrl = match.source.kind === 'dj' && match.episode.sourceUrl
            ? liveSetStreamUrl(match.episode.sourceUrl)
            : match.episode.audioUrl
          const previous = byId.get(state.id)
          byId.set(state.id, {
            id: state.id,
            title: match.episode.title,
            artist: match.source.title,
            remoteUrl,
            localUri: state.localUri ?? previous?.localUri,
            status: state.status,
            bytesDownloaded: state.bytesDownloaded,
            totalBytes: state.totalBytes,
            addedAt: previous?.addedAt ?? new Date().toISOString(),
          })
        }
        return [...byId.values()]
      })
    }).catch(() => undefined)
    return () => { cancelled = true }
  }, [catalog])

  const selected = tracks.find((track) => track.id === selectedId) ?? tracks[0] ?? {
    id: 0, time: currentTime, artist: '—', title: 'Aucune transition', confidence: 0, source: 'manual' as const,
  }

  function loadAudio(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file || !waveformRef.current) return
    waveRef.current?.destroy()
    const wave = WaveSurfer.create({
      container: waveformRef.current, waveColor: '#6a6c68', progressColor: '#e99a72',
      cursorColor: '#f8f5ea', cursorWidth: 2, height: 190, barWidth: 2, barGap: 2, barRadius: 2, normalize: true,
    })
    waveRef.current = wave
    wave.loadBlob(file)
    setHasLocalAudio(true)
    wave.on('ready', () => { setDuration(wave.getDuration()); setCurrentTime(0) })
    wave.on('timeupdate', setCurrentTime)
    wave.on('play', () => setIsPlaying(true))
    wave.on('pause', () => setIsPlaying(false))
    wave.on('finish', () => setIsPlaying(false))
    setAudioName(file.name.replace(/\.[^/.]+$/, ''))
    setDetectionError('')
  }

  async function togglePlayback() {
    if (waveRef.current) waveRef.current.playPause()
    else if (studioEpisode) {
      try {
        const state = isPlaying ? await podmixPlayer.pause() : await podmixPlayer.play()
        setIsPlaying(state.playing || (!isPlaying && state.playbackState !== 1))
      } catch (error) {
        setDetectionError(error instanceof Error ? `Lecture impossible : ${error.message}` : 'Lecture impossible')
      }
    }
  }

  async function seek(time: number, id?: number) {
    setCurrentTime(time)
    if (id) setSelectedId(id)
    if (waveRef.current && duration) waveRef.current.seekTo(Math.min(1, time / duration))
    else if (studioEpisode) {
      try {
        await podmixPlayer.seekTo(time)
      } catch (error) {
        setDetectionError(error instanceof Error ? `Positionnement impossible : ${error.message}` : 'Positionnement impossible')
      }
    }
  }

  function addMarker() {
    const nextId = Math.max(0, ...tracks.map((track) => track.id)) + 1
    const next: Track = { id: nextId, time: Math.round(currentTime), artist: 'Nouvel artiste', title: 'Nouveau morceau', confidence: 100, source: 'manual' }
    undoStack.current.push(tracks.map((track) => ({ ...track })))
    setTracks((items) => [...items, next].sort((a, b) => a.time - b.time))
    setSelectedId(nextId)
  }

  function updateSelected(field: 'artist' | 'title', value: string) {
    undoStack.current.push(tracks.map((track) => ({ ...track })))
    setTracks((items) => items.map((track) => track.id === selected.id ? { ...track, [field]: value } : track))
  }

  function deleteSelectedTrack() {
    if (!selected.id) return
    undoStack.current.push(tracks.map((track) => ({ ...track })))
    const remaining = tracks.filter((track) => track.id !== selected.id)
    setTracks(remaining)
    setSelectedId(remaining[0]?.id ?? 0)
  }

  function undoTrackChange() {
    const previous = undoStack.current.pop()
    if (!previous) return
    setTracks(previous)
    if (!previous.some((track) => track.id === selectedId)) setSelectedId(previous[0]?.id ?? 0)
  }

  function exportTracklist(format: 'json' | 'csv' | 'chapters') {
    const content = format === 'json'
      ? JSON.stringify({ title: audioName, tracks }, null, 2)
      : format === 'csv'
        ? ['time_seconds,artist,title,confidence', ...tracks.map((track) => `${track.time},"${track.artist.replaceAll('"', '""')}","${track.title.replaceAll('"', '""')}",${track.confidence}`)].join('\n')
        : tracks.map((track) => `${formatTime(track.time)} ${track.artist} — ${track.title}`).join('\n')
    const mime = format === 'json' ? 'application/json' : format === 'csv' ? 'text/csv' : 'text/plain'
    const url = URL.createObjectURL(new Blob([content], { type: `${mime};charset=utf-8` }))
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${audioName.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}-tracklist.${format === 'chapters' ? 'txt' : format}`
    anchor.click()
    URL.revokeObjectURL(url)
  }

  function persistEpisodeAnalysis(
    target: { sourceId: string; episodeId: string } | undefined,
    job: DetectionJob,
    replaceCurrent = false,
  ) {
    if (!target) return
    const hasNamedTracks = job.tracks.some((track) => !/^Transition \d+$/i.test(track.title))
    setCatalog((sources) => sources.map((source) => source.id !== target.sourceId ? source : {
      ...source,
      episodes: source.episodes.map((episode) => {
        if (episode.id !== target.episodeId) return episode
        // Un ancien job annulé peut encore émettre son dernier événement : il
        // ne doit pas remplacer l'analyse qui vient juste d'être relancée.
        if (!replaceCurrent && episode.analysis?.jobId && episode.analysis.jobId !== job.id) return episode
        return {
          ...episode,
          // Un échec de rafraîchissement ne doit jamais effacer une tracklist lisible.
          ...(hasNamedTracks ? { tracks: job.tracks } : {}),
          analysis: {
            jobId: job.id,
            status: job.status,
            stage: job.stage,
            progress: job.progress,
            updatedAt: job.updatedAt,
            ...(job.error ? { error: job.error } : {}),
          },
        }
      }),
    }))
  }

  function persistEpisodeTracklistUrl(
    target: { sourceId: string; episodeId: string } | undefined,
    sourceUrl: string,
  ) {
    if (!target || !/^https:\/\/www\.1001tracklists\.com\/tracklist\//i.test(sourceUrl)) return
    setCatalog((sources) => sources.map((source) => source.id !== target.sourceId ? source : {
      ...source,
      episodes: source.episodes.map((episode) => episode.id !== target.episodeId ? episode : {
        ...episode,
        webTracklistUrl: sourceUrl,
      }),
    }))
  }

  async function syncTimestampJobStatuses() {
    const pending = catalogRef.current.flatMap((source) => source.episodes
      .filter((episode) => Boolean(episode.analysis?.jobId))
      .map((episode) => ({ sourceId: source.id, episodeId: episode.id, jobId: episode.analysis!.jobId })))
    await Promise.all(pending.map(async (target) => {
      try {
        const job = await getDetectionJob(target.jobId)
        persistEpisodeAnalysis(target, job, true)
        if (['completed', 'failed', 'cancelled'].includes(job.status)) {
          terminalTimestampEpisodes.current.add(target.episodeId)
        }
      } catch {
        // La prochaine reprise ou ouverture d'épisode tentera à nouveau.
      }
    }))
    void scheduleNextTimestamping()
  }

  function watchDetectionJob(
    job: DetectionJob,
    target?: { sourceId: string; episodeId: string },
  ) {
    setCurrentJobId(job.id)
    setDetection(job.status === 'completed' ? 'done' : 'running')
    setProgress(job.progress)
    setDetectionStage(job.stage)
    persistEpisodeAnalysis(target, job, true)
    if (job.status === 'completed') {
      setTracks(job.tracks)
      if (job.tracks[0]) setSelectedId(job.tracks[0].id)
      if (target) void scheduleNextTimestamping()
      return
    }
    observeDetectionJob(job.id, (update) => {
      setProgress(update.progress)
      setDetectionStage(update.stage)
      persistEpisodeAnalysis(target, update)
      if (update.status === 'completed') {
        setDetection('done')
        setTracks(update.tracks)
        if (update.tracks[0]) setSelectedId(update.tracks[0].id)
      }
      if (update.status === 'failed' || update.status === 'cancelled') {
        setDetection('idle')
        setDetectionError(update.error ?? 'Analyse interrompue')
      }
      if (target && ['completed', 'failed', 'cancelled'].includes(update.status)) {
        terminalTimestampEpisodes.current.add(target.episodeId)
        finishAutomaticEpisodeTimestamping(target.episodeId)
        void scheduleNextTimestamping()
      }
    }, () => {
      setDetection('idle')
      setDetectionError('Flux de progression interrompu')
    })
  }

  async function scheduleEpisodeAnalysis(source: CatalogSource, episode: Episode, force = false) {
    if (source.kind === 'show') return undefined
    const target = { sourceId: source.id, episodeId: episode.id }
    try {
      const existing = await findDetectionJob(`episode:${episode.id}`)
      const mustRestart = force
        || forcedTimestampEpisodes.current.has(episode.id)
        || (episode.analysis?.status === 'queued' && !episode.analysis.jobId)
        // Un podcast réimporté n'a pas encore d'état local. Ses anciens jobs
        // terminaux sur le VPS ne doivent pas faire sauter des épisodes.
        || (!episode.analysis && ['completed', 'failed', 'cancelled'].includes(existing?.status ?? ''))
      // Un succès Web valide est durable côté VPS. Lorsqu'un rafraîchissement
      // RSS a momentanément effacé son état local, on le resynchronise au lieu
      // de recréer un job : sinon l'icône oscille vert → orange indéfiniment.
      const reuseExisting = !mustRestart && existing && (
        existing.status !== 'completed' || hasReusableCompletedTimestamps(existing.tracks)
      )
      const job = reuseExisting
        ? existing
        : await createEpisodeAnalysisJob(source, episode, mustRestart || Boolean(existing))
      if (mustRestart) forcedTimestampEpisodes.current.delete(episode.id)
      persistEpisodeAnalysis(target, job, true)
      if (['completed', 'failed', 'cancelled'].includes(job.status)) return job
      if (!observedScheduledJobs.current.has(job.id)) {
        observedScheduledJobs.current.add(job.id)
        observeDetectionJob(job.id, (update) => {
          persistEpisodeAnalysis(target, update)
          if (['completed', 'failed', 'cancelled'].includes(update.status)) {
            observedScheduledJobs.current.delete(job.id)
            terminalTimestampEpisodes.current.add(target.episodeId)
            finishAutomaticEpisodeTimestamping(target.episodeId)
            void scheduleNextTimestamping()
          }
        }, () => {
          // Le job reste durable sur le VPS et sera resynchronisé à la prochaine ouverture.
          observedScheduledJobs.current.delete(job.id)
        })
      }
      return job
    } catch (error) {
      const failedAt = new Date().toISOString()
      setCatalog((sources) => sources.map((item) => item.id !== source.id ? item : {
        ...item,
        episodes: item.episodes.map((entry) => entry.id !== episode.id ? entry : {
          ...entry,
          analysis: {
            jobId: entry.analysis?.jobId ?? '',
            status: 'failed',
            stage: 'Planification impossible',
            progress: 0,
            updatedAt: failedAt,
            error: error instanceof Error ? error.message : 'Serveur indisponible',
          },
        }),
      }))
      return undefined
    }
  }

  async function scheduleNextTimestamping(sources = catalogRef.current) {
    if ((!settings.automaticAnalysis && !manualTimestampQueueActive.current) || timestampQueueBusy.current) return
    timestampQueueBusy.current = true
    try {
      const activeEpisode = sources.filter((source) => source.kind !== 'show').flatMap((source) => source.episodes
        .filter((episode) => Boolean(episode.analysis?.jobId) && analysisIsActive(episode.analysis?.status))
        .map((episode) => ({ source, episode })))[0]
      if (activeEpisode) {
        // Le changement de podcast mémorise la prochaine file, mais ne crée
        // jamais un job supplémentaire pendant qu'un autre est en cours.
        // Si le job actif appartient déjà à la file choisie, on se contente
        // de le rattacher à son suivi après un redémarrage de l'application.
        if (activeEpisode.source.id === activeTimestampSourceId.current) {
          await scheduleEpisodeAnalysis(activeEpisode.source, activeEpisode.episode)
        }
        return
      }
      const queuedEpisodes = (source: CatalogSource) => source.kind === 'show' ? [] : sortTimestampJobs(source.episodes
        .filter((episode) => episode.audioUrl
          && !terminalTimestampEpisodes.current.has(episode.id)
          && (forcedTimestampEpisodes.current.has(episode.id)
            || automaticTimestampEpisodeIds.current.has(episode.id)
            || Boolean(episode.analysis?.jobId) && analysisIsActive(episode.analysis?.status)))
        .map((episode) => ({ source, episode })))
      // Une source garde la main jusqu'à épuisement de sa file. Le verrou est
      // durable afin qu'une fermeture de l'app ne mélange pas les podcasts au
      // redémarrage suivant.
      let source = sources.find((item) => item.id === activeTimestampSourceId.current)
      let sourceQueue = source ? queuedEpisodes(source) : []
      if (!sourceQueue.length) {
        source = sources.find((item) => queuedEpisodes(item).length > 0)
        sourceQueue = source ? queuedEpisodes(source) : []
        activeTimestampSourceId.current = source?.id ?? ''
        if (source) localStorage.setItem('podmix-active-timestamp-source-v1', source.id)
        else localStorage.removeItem('podmix-active-timestamp-source-v1')
      }
      const next = sourceQueue[0]
      if (!next) {
        manualTimestampQueueActive.current = false
        return
      }
      const job = await scheduleEpisodeAnalysis(next.source, next.episode)
      if (job && ['completed', 'failed', 'cancelled'].includes(job.status)) {
        terminalTimestampEpisodes.current.add(next.episode.id)
        finishAutomaticEpisodeTimestamping(next.episode.id)
        window.setTimeout(() => void scheduleNextTimestamping(sources), 0)
      }
    } finally {
      timestampQueueBusy.current = false
    }
  }

  async function startDetection(explicitTarget?: { sourceId: string; episodeId: string }) {
    const target = explicitTarget ?? studioEpisode
    const source = target ? catalogRef.current.find((item) => item.id === target.sourceId) : undefined
    const episode = source?.episodes.find((item) => item.id === target?.episodeId)
    if (episode && source?.kind !== 'show') {
      try {
        const existing = await findDetectionJob(`episode:${episode.id}`)
        const hasNamedTracks = existing?.tracks.some((track) => !/^Transition \d+$/i.test(track.title))
        if (existing && (analysisIsActive(existing.status) || hasNamedTracks)) {
          watchDetectionJob(existing, target)
          return
        }
      } catch {
        // Une indisponibilité de l’index ne doit pas empêcher une nouvelle analyse.
      }
      if (source) {
        setDetection('running')
        setDetectionStage('Planification sur le VPS')
        setDetectionError('')
        try {
          const job = await createEpisodeAnalysisJob(
            source,
            episode,
            Boolean(episode.analysis?.status === 'completed' || detection === 'done'),
          )
          watchDetectionJob(job, target)
        } catch (error) {
          setDetection('idle')
          setDetectionError(error instanceof Error ? error.message : 'Planification distante impossible')
        }
        return
      }
    }
    setDetectionError('La recherche automatique part d’un épisode RSS ou d’un DJ set. Le fichier local reste disponible pour l’édition manuelle.')
  }

  async function resetTimestampingForEpisodes(source: CatalogSource, episodes: Episode[]) {
    const episodeIds = new Set(episodes.filter((episode) => episode.audioUrl).map((episode) => episode.id))
    for (const episode of episodes) {
      if (!episodeIds.has(episode.id)) continue
      try {
        const existing = await findDetectionJob(`episode:${episode.id}`)
        if (existing && analysisIsActive(existing.status)) await cancelDetectionJob(existing.id)
      } catch {
        // La nouvelle planification forcée remplace de toute façon le job connu
        // par le VPS ; une annulation devenue indisponible ne bloque pas l'action.
      }
    }

    const updatedAt = new Date().toISOString()
    for (const episodeId of episodeIds) {
      terminalTimestampEpisodes.current.delete(episodeId)
      forcedTimestampEpisodes.current.add(episodeId)
    }
    return {
      ...source,
      episodes: source.episodes.map((episode) => {
        if (!episodeIds.has(episode.id)) return episode
        return {
          ...episode,
          // Les titres restent disponibles pendant la relance. Seuls les
          // timestamps obtenus par le Web sont remis à zéro ; les repères RSS
          // et manuels demeurent des sources explicites.
          tracks: episode.tracks?.map((track) => (
            track.timestampSource === 'rss' || track.timestampSource === 'manual'
              ? track
              : {
                  ...track,
                  time: 0,
                  timestampSource: 'provisional' as const,
                  timestampStatus: 'pending' as const,
                  timestampScore: 0,
                }
          )),
          analysis: {
            jobId: '',
            status: 'queued' as const,
            stage: 'En attente de relance',
            progress: 0,
            updatedAt,
          },
        }
      }),
    }
  }

  async function refreshEpisodeAnalysis(source: CatalogSource, episode: Episode) {
    if (source.kind === 'show') return
    const target = { sourceId: source.id, episodeId: episode.id }
    setDetection('running')
    setDetectionStage('Réinitialisation de l’épisode')
    setDetectionError('')
    timestampQueueBusy.current = true
    try {
      const resetSource = await resetTimestampingForEpisodes(source, [episode])
      const resetEpisode = resetSource.episodes.find((item) => item.id === episode.id)
      setCatalog((sources) => sources.map((item) => item.id === source.id ? resetSource : item))
      if (!resetEpisode) throw new Error('Épisode introuvable après réinitialisation')
      const job = await scheduleEpisodeAnalysis(resetSource, resetEpisode, true)
      if (!job) throw new Error('Relance impossible')
      persistEpisodeAnalysis(target, job)
      watchDetectionJob(job, target)
    } catch (error) {
      setDetection('idle')
      setDetectionError(error instanceof Error ? error.message : 'Actualisation impossible')
    } finally {
      timestampQueueBusy.current = false
    }
  }

  async function refreshLiveSetEpisode(source: CatalogSource, episode: Episode) {
    const url = episode.sourceUrl || source.feedUrl
    if (!url) {
      setDetectionError('URL du live set introuvable')
      return
    }
    setLiveSetBusyId(episode.id)
    setDetectionError('')
    try {
      const [resolved, tracklist] = await Promise.all([
        resolveLiveSet(url),
        resolveLiveSetTracklist(url, episode.title),
      ])
      const hasExistingTracklist = (episode.tracks?.length ?? 0) >= 2
      const sourceChanged = Boolean(
        episode.liveSetTracklistUrl
        && tracklist.sourceUrl
        && episode.liveSetTracklistUrl !== tracklist.sourceUrl,
      )
      const usableTracklist = tracklist.tracks.length >= 2
      // La mise à jour audio/métadonnées reste possible, mais une liste déjà
      // validée reste intacte si la recherche a dérivé vers un autre set.
      const keepExistingTracklist = hasExistingTracklist && (!usableTracklist || sourceChanged)
      if (keepExistingTracklist) {
        setDownloadMessage('Tracklist existante conservée : la nouvelle source ne correspond pas au set enregistré.')
      }
      const refreshedEpisode: Episode = {
        ...episode,
        title: resolved.title || episode.title,
        description: resolved.description || episode.description,
        duration: resolved.duration ? formatTime(resolved.duration) : episode.duration,
        audioUrl: resolved.audioUrl || episode.audioUrl,
        artworkUrl: resolved.artworkUrl || episode.artworkUrl,
        tracks: keepExistingTracklist ? episode.tracks : liveSetTracksToCatalog(tracklist.tracks),
        liveSetTracklistUrl: keepExistingTracklist ? episode.liveSetTracklistUrl : tracklist.sourceUrl,
        liveSetTracklistOrigin: keepExistingTracklist ? episode.liveSetTracklistOrigin : tracklist.origin,
      }
      const refreshedSource = {
        ...source,
        title: refreshedEpisode.title,
        artworkUrl: refreshedEpisode.artworkUrl || source.artworkUrl,
        description: `${refreshedEpisode.tracks?.length ?? 0} titres · DJ Live Set`,
        episodes: source.episodes.map((item) => item.id === episode.id ? refreshedEpisode : item),
      }
      setCatalog((items) => items.map((item) => item.id === source.id ? refreshedSource : item))
    } catch (error) {
      setDetectionError(error instanceof Error ? error.message : 'Actualisation du live set impossible')
    } finally {
      setLiveSetBusyId('')
    }
  }

  async function submitTracklist() {
    if (!currentJobId || !tracklistText.trim()) {
      setDetectionError('Terminez une analyse puis collez une tracklist')
      return
    }
    setAligning(true)
    setDetectionError('')
    try {
      const job = await alignTracklist(currentJobId, tracklistText)
      setTracks(job.tracks)
      if (job.tracks[0]) setSelectedId(job.tracks[0].id)
      setDetectionStage('Tracklist alignée')
    } catch (error) {
      setDetectionError(error instanceof Error ? error.message : 'Alignement impossible')
    } finally {
      setAligning(false)
    }
  }

  function verifySelected() {
    setTracks((items) => items.map((track) => track.id === selected.id ? { ...track, verified: true, confidence: 100 } : track))
  }

  async function validateSelectedInCatalog() {
    if (!currentJobId || !selected.id) {
      setDetectionError('Aucun job ou titre à vérifier')
      return
    }
    setValidatingCatalog(true)
    setDetectionError('')
    try {
      const result = await validateCatalogTrack(currentJobId, selected.id)
      setTracks((items) => items.map((track) => track.id === result.track.id ? result.track : track))
      if (!result.accepted) setDetectionError('Correspondance catalogue insuffisante — vérification manuelle recommandée')
    } catch (error) {
      setDetectionError(error instanceof Error ? error.message : 'Catalogue indisponible')
    } finally {
      setValidatingCatalog(false)
    }
  }

  async function discoverFromUrl() {
    if (!currentJobId || !sourceUrl.trim()) {
      setDetectionError('Terminez une analyse puis saisissez une URL média')
      return
    }
    setDiscovering(true)
    setDetectionError('')
    try {
      const result = await discoverTracklist(currentJobId, sourceUrl)
      if (!result.tracks.length) {
        setDetectionError(result.message ?? 'Aucune tracklist trouvée dans les métadonnées')
        return
      }
      setTracks(result.tracks)
      setSelectedId(result.tracks[0].id)
      setDetectionStage(`${result.candidateCount} titres découverts`)
    } catch (error) {
      setDetectionError(error instanceof Error ? error.message : 'Découverte impossible')
    } finally {
      setDiscovering(false)
    }
  }

  async function discoverFromEpisodeTitle() {
    if (!currentJobId) {
      setDetectionError('Lancez d’abord la recherche de tracklist')
      return
    }
    setSearchingSource(true)
    setDetectionError('')
    try {
      const candidates = await searchDjSets(audioName)
      if (!candidates.length) throw new Error('Aucune source média trouvée pour ce titre')
      const best = candidates[0]
      setSourceUrl(best.url)
      const result = await discoverTracklist(currentJobId, best.url)
      if (!result.tracks.length) {
        setDetectionStage(`Source trouvée : ${best.title}`)
        setDetectionError('La source a été trouvée, mais ses métadonnées ne contiennent pas de tracklist')
        return
      }
      setTracks(result.tracks)
      setSelectedId(result.tracks[0].id)
      setDetectionStage(`${result.candidateCount} titres découverts depuis ${best.title}`)
    } catch (error) {
      setDetectionError(error instanceof Error ? error.message : 'Recherche de source impossible')
    } finally {
      setSearchingSource(false)
    }
  }

  async function discoverFrom1001() {
    if (!currentJobId || !tl1001Query.trim()) {
      setDetectionError('Terminez une analyse puis saisissez un titre ou une URL de tracklist')
      return
    }
    setDiscovering1001(true)
    setDetectionError('')
    try {
      if (podmixPlayer.isNative) {
        const value = tl1001Query.trim()
        const candidates = /^https:\/\//i.test(value)
          ? [{ url: value, address: undefined }]
          : (await searchTracklistCandidates(value))
              .filter((candidate) => candidate.domain.endsWith('1001tracklists.com'))
        let nativeResult: Awaited<ReturnType<typeof podmixPlayer.fetchTracklist1001>> | null = null
        for (const candidate of candidates.slice(0, 4)) {
          try {
            nativeResult = await podmixPlayer.fetchTracklist1001(candidate.url, candidate.address)
            if (nativeResult.tracks.length >= 3) break
          } catch {
            // Le serveur essaiera ensuite MixesDB puis 1001Tracklists.
          }
        }
        if (nativeResult?.tracks.length) {
          const hasTimestamps = nativeResult.tracks.some((track) => track.providedTime > 0)
          const text = nativeResult.tracks.map((track, index) => {
            const prefix = hasTimestamps
              ? `${String(Math.floor(track.providedTime / 3600)).padStart(2, '0')}:${String(Math.floor(track.providedTime % 3600 / 60)).padStart(2, '0')}:${String(Math.floor(track.providedTime % 60)).padStart(2, '0')}`
              : `${index + 1}.`
            return `${prefix} ${track.artist} — ${track.title}`
          }).join('\n')
          const aligned = await alignTracklist(currentJobId, text, { timestampSource: 'external' })
          const enriched = aligned.tracks.map((track) => ({
            ...track,
            evidence: [`1001Tracklists via Android : ${nativeResult!.sourceUrl}`, ...(track.evidence ?? [])],
          }))
          setTracks(enriched)
          setSelectedId(enriched[0].id)
          setDetectionStage(`${enriched.length} titres importés depuis ${nativeResult.pageTitle || '1001Tracklists'}`)
          return
        }
      }
      const result = await discover1001Tracklist(currentJobId, tl1001Query)
      if (!result.tracks.length) throw new Error(result.message ?? 'Aucune tracklist externe trouvée')
      setTracks(result.tracks)
      setSelectedId(result.tracks[0].id)
      setDetectionStage(`${result.candidateCount} titres importés depuis ${result.source === 'mixesdb' ? 'MixesDB' : '1001Tracklists'}`)
    } catch (error) {
      const message = error instanceof Error ? error.message : ''
      setDetectionError(
        /ERR_NAME_NOT_RESOLVED|SocketTimeoutException|failed to connect/i.test(message)
          ? 'Les sources externes sont actuellement inaccessibles. La tracklist RSS reste disponible ; ses repères sont estimés jusqu’au raffinage audio.'
          : message || 'Sources de tracklists indisponibles',
      )
    } finally {
      setDiscovering1001(false)
    }
  }

  async function runLiveSetSearch(event?: React.FormEvent) {
    event?.preventDefault()
    const query = liveSetQuery.trim()
    if (query.length < 2) return
    setLiveSetSearching(true)
    setLiveSetError('')
    try {
      setLiveSetResults(await searchLiveSets(query, liveSetResultLimit))
    } catch (error) {
      setLiveSetError(error instanceof Error ? error.message : 'Recherche de live sets indisponible')
    } finally {
      setLiveSetSearching(false)
    }
  }

  function liveSetCatalogId(item: Pick<LiveSetSearchResult, 'id' | 'provider'>) {
    return `live-set:${item.provider}:${item.id}`
  }

  function liveSetTracksToCatalog(tracks: LiveSetDetails['tracks'] = []): Track[] {
    const validTracks = tracks.filter((track, index) => {
      // 0:00 ne peut représenter que le début réel du set. Une valeur 0 au
      // milieu est une ancienne sous-ligne de mashup, jamais un cue valable.
      if (index === 0 || (track.time ?? 0) > 0) return true
      return !tracks.some((candidate) => (candidate.time ?? 0) > 0)
    })
    return validTracks.map((track, index) => {
      const provided = track.timestampStatus === 'provided' && track.time !== null
      return {
        id: index + 1,
        time: Math.max(0, track.time ?? 0),
        artist: track.artist,
        title: track.title,
        confidence: provided ? 100 : 0,
        timestampSource: provided ? track.timestampSource === 'youtube' ? 'youtube' : 'external' : 'provisional',
        timestampStatus: provided ? 'provided' : 'pending',
        source: 'manual',
        verified: provided,
        evidence: provided ? ['Tracklist live set'] : ['Tracklist sans repère source'],
      }
    })
  }

  function removeInvalidLiveSetZeroCues(tracks: Track[] | undefined): Track[] | undefined {
    if (!tracks?.length || !tracks.some((track) => track.time > 0)) return tracks
    const cleaned = tracks.filter((track, index) => index === 0 || track.time > 0)
    return cleaned.length === tracks.length ? tracks : cleaned
  }

  async function saveLiveSet(item: LiveSetSearchResult) {
    const sourceId = liveSetCatalogId(item)
    setLiveSetBusyId(item.id)
    setLiveSetError('')
    try {
      const [resolved, tracklist] = await Promise.all([
        resolveLiveSet(item.url),
        resolveLiveSetTracklist(item.url, item.title),
      ])
      const episode: Episode = {
        id: `${sourceId}:episode`,
        title: resolved.title || item.title,
        description: resolved.description || `Live set ${item.provider === 'youtube' ? 'YouTube' : 'SoundCloud'} · ${item.channel}`,
        publishedAt: item.publishedAt,
        duration: item.duration ? formatTime(item.duration) : '',
        audioUrl: resolved.audioUrl || '',
        sourceUrl: item.url,
        artworkUrl: resolved.artworkUrl || item.artworkUrl,
        tracks: liveSetTracksToCatalog(tracklist.tracks),
        liveSetTracklistUrl: tracklist.sourceUrl,
        liveSetTracklistOrigin: tracklist.origin,
      }
      const source: CatalogSource = {
        id: sourceId,
        kind: 'dj',
        title: episode.title,
        description: `${episode.tracks?.length ?? 0} titres · ${item.provider === 'youtube' ? 'YouTube' : 'SoundCloud'}`,
        artworkUrl: episode.artworkUrl || item.artworkUrl,
        feedUrl: item.url,
        episodes: [episode],
      }
      setCatalog((items) => [source, ...items.filter((candidate) => candidate.id !== sourceId)])
      setShowAddSource(false)
      setActiveView('home')
      setSelectedSourceId('')
      setSelectedEpisodeId('')
    } catch (error) {
      setLiveSetError(error instanceof Error ? error.message : 'Import du live set impossible')
    } finally {
      setLiveSetBusyId('')
    }
  }

  function removeLiveSet(id: string) {
    setSavedLiveSets((items) => items.filter((item) => item.id !== id))
  }

  function updateSavedLiveSet(id: string, patch: Partial<LiveSetDetails>) {
    setSavedLiveSets((items) => items.map((item) => item.id === id ? { ...item, ...patch } : item))
  }

  async function playLiveSet(item: LiveSetDetails, startAt = 0) {
    const requestId = beginPlaybackRequest()
    setLiveSetBusyId(item.id)
    try {
      // Les URL CDN YouTube/SoundCloud expirent : une résolution neuve évite
      // de conserver un lien audio périmé dans la sélection locale.
      const resolved = { ...item, ...await resolveLiveSet(item.url) }
      if (!isCurrentPlaybackRequest(requestId)) return
      updateSavedLiveSet(item.id, resolved)
      await playEpisode(item.id, resolved.title, resolved.channel || 'DJ live set', resolved.audioUrl ?? '', resolved.artworkUrl, startAt, 'liveSet', requestId)
    } catch (error) {
      setLiveSetError(error instanceof Error ? error.message : 'Lecture du live set impossible')
    } finally {
      setLiveSetBusyId('')
    }
  }

  async function loadLiveSetTracklist(item: LiveSetDetails, text = '') {
    setLiveSetBusyId(item.id)
    try {
      const result = await resolveLiveSetTracklist(item.url, item.title, text)
      updateSavedLiveSet(item.id, { tracks: result.tracks, tracklistOrigin: result.origin, tracklistUrl: result.sourceUrl })
      setLiveSetTracklistText('')
    } catch (error) {
      setLiveSetError(error instanceof Error ? error.message : 'Tracklist DJ indisponible')
    } finally {
      setLiveSetBusyId('')
    }
  }

  const displayedLiveSets = liveSetResults
    .filter((item) => liveSetProvider === 'all' || item.provider === liveSetProvider)
    .sort((left, right) => {
      if (liveSetSort === 'popular') return right.viewCount - left.viewCount
      if (liveSetSort === 'recent') return String(right.publishedAt).localeCompare(String(left.publishedAt))
      return right.score - left.score || right.viewCount - left.viewCount
    })

  const liveSetLimitControl = <div className="live-set-limit" aria-label="Nombre de résultats DJ à rechercher">
    <span>Résultats</span>
    {[12, 24, 36, 50].map((limit) => <button type="button" key={limit} className={liveSetResultLimit === limit ? 'active' : ''} onClick={() => setLiveSetResultLimit(limit)}>{limit}</button>)}
  </div>

  const viewLabels: Record<AppView, string> = {
    home: 'Accueil', resume: 'Reprendre l’écoute', favorites: 'Favoris', history: 'Historique', studio: 'Analyses', settings: 'Réglages', liveSets: 'DJ Live Sets', djLibrary: 'DJ sets',
  }

  function navigateTo(view: AppView) {
    setActiveView(view)
    setSelectedSourceId('')
    setSelectedEpisodeId('')
  }

  function openCatalogSource(source: CatalogSource, episodeId = '') {
    setCatalog((items) => items.map((item) => item.id === source.id && item.unseenEpisodeIds?.length
      ? { ...item, unseenEpisodeIds: [] }
      : item))
    setSelectedSourceId(source.id)
    setSelectedEpisodeId(episodeId)
  }

  function openCatalogEpisode(source: CatalogSource, episode: Episode) {
    setCatalog((items) => items.map((item) => item.id === source.id
      ? {
          ...item,
          unseenEpisodeIds: [],
          newEpisodeIds: item.newEpisodeIds?.filter((id) => id !== episode.id),
        }
      : item))
    setSelectedSourceId(source.id)
    setSelectedEpisodeId(episode.id)
  }

  async function addFeed() {
    if (!feedUrl.trim()) return
    setAddingFeed(true); setFeedError('')
    try {
      const source = await importRssFeed(feedUrl.trim(), sourceMode === 'show' ? 'show' : 'podcast', sourceMode === 'show' ? settings.maxShowEpisodes : settings.maxPodcastEpisodes)
      const isNewPodcast = !catalogRef.current.some((item) => item.id === source.id)
      setCatalog((items) => [source, ...items.filter((item) => item.id !== source.id)])
      if (source.kind !== 'show' && isNewPodcast && settings.automaticAnalysis) {
        enqueueNewEpisodesForTimestamping(source.episodes)
        activeTimestampSourceId.current = source.id
        localStorage.setItem('podmix-active-timestamp-source-v1', source.id)
        void scheduleNextTimestamping([source, ...catalogRef.current.filter((item) => item.id !== source.id)])
      }
      establishRssBaseline(source.id)
      setFeedUrl(''); setShowAddSource(false); setSelectedSourceId('')
    } catch (error) {
      setFeedError(error instanceof Error ? error.message : 'Flux indisponible')
    } finally {
      setAddingFeed(false)
    }
  }

  function persistPlaybackState(
    state: Awaited<ReturnType<typeof podmixPlayer.getState>>,
    force = false,
  ) {
    const current = nowPlayingRef.current
    if (!current || !state.mediaId) return
    // A station has no durable playback position. Persisting its elapsed
    // counter lets a later phone/Android Auto session revive stale buffered
    // audio instead of reconnecting to the live stream.
    if (current.scope === 'radio') return
    const trackQueue = trackQueueRef.current
    const favoriteItem = favoriteQueueEntryForState(state)
    const activeTrack = trackQueue && state.queueIndex >= 0 && state.queueIndex < trackQueue.mediaIds.length ? trackQueue.tracks[state.queueIndex] : undefined
    const historyItem = favoriteItem
      ? {
          id: favoriteItem.episode.id,
          title: favoriteItem.episode.title,
          artist: favoriteItem.source.title,
          url: preferredEpisodePlaybackUrl(favoriteItem.episode.id, favoriteItem.episode.audioUrl),
          position: favoriteItem.track.time + state.positionSeconds,
        }
      : trackQueue && activeTrack
      ? {
          id: trackQueue.episodeId,
          title: trackQueue.episodeTitle,
          artist: trackQueue.sourceTitle,
          url: trackQueue.audioUrl,
          // The native continuous-episode player exposes both the virtual
          // track position and the real episode position. Persist the latter:
          // adding the first timestamp used to jump progress forward during
          // an intro and could mark untouched episodes as partly played.
          position: Math.max(0, state.absolutePositionSeconds ?? (activeTrack.time + state.positionSeconds)),
        }
      : {
          ...current,
          // Never save under a prefixed native id, and prefer the absolute
          // episode position over the track-relative one when available.
          id: normalizeEpisodeMediaId(current.id),
          position: Math.max(0, state.absolutePositionSeconds ?? state.positionSeconds),
        }
    const persistBranch = favoriteItem ? 'favorite' : trackQueue && activeTrack ? 'trackQueue' : 'fallback'
    const persistenceNow = Date.now()
    const previousPersistence = lastPersistedPlaybackRef.current
    if (!force
      && previousPersistence.id === historyItem.id
      && persistenceNow - previousPersistence.at < 5_000
      && Math.abs(previousPersistence.position - historyItem.position) < 5) {
      return
    }
    logResumeDebug({
      branch: persistBranch,
      force,
      savedId: historyItem.id,
      savedPosition: historyItem.position,
      mediaId: state.mediaId,
      nowPlayingId: current.id,
      queueIndex: state.queueIndex,
      statePosition: state.positionSeconds,
      absolutePosition: state.absolutePositionSeconds ?? null,
      playbackState: state.playbackState,
    })
    lastPersistedPlaybackRef.current = {
      id: historyItem.id,
      position: historyItem.position,
      at: persistenceNow,
    }
    const catalogDuration = catalogRef.current
      .flatMap((source) => source.episodes)
      .find((episode) => episode.id === historyItem.id)?.duration
    const previousDuration = historyRef.current.find((item) => item.id === historyItem.id)?.duration ?? 0
    const mediaDuration = trackQueue || favoriteItem ? 0 : state.durationSeconds
    const historyDuration = Math.max(previousDuration, parseDuration(catalogDuration ?? ''), mediaDuration)
    // La reprise ne contient que les écoutes inachevées. L'historique détaillé
    // reste disponible séparément, mais un épisode, podcast ou DJ set terminé
    // est retiré de cette liste dès que la fin est atteinte.
    // À la fin réelle renvoyée par Media3, le flux est terminé même lorsqu'un
    // RSS annonce une durée imprécise. Sans ce signal explicite, Android Auto
    // pouvait conserver les dernières secondes comme une reprise.
    const playbackEnded = state.playbackState === 4
    if (playbackEnded || (historyDuration > 0 && historyItem.position / historyDuration >= 0.98)) {
      markEpisodeCompleted(historyItem.id)
      const next = historyRef.current.filter((item) => item.id !== historyItem.id)
      historyRef.current = next
      savePlaybackHistory(next)
      setHistory(next)
      const completedItem = { ...historyItem, position: historyDuration, duration: historyDuration, playedAt: new Date().toISOString() }
      setListeningSessions((sessions) => {
        const updated = updateListeningSessions(sessions, completedItem)
        saveListeningSessions(updated)
        return updated
      })
      return
    }
    const next = [
      { ...historyItem, ...(historyDuration > 0 ? { duration: historyDuration } : {}), playedAt: new Date().toISOString() },
      ...historyRef.current.filter((item) => item.id !== historyItem.id),
    ].slice(0, 1_000)
    historyRef.current = next
    savePlaybackHistory(next)
    setHistory(next)
    setListeningSessions((sessions) => {
      const updated = updateListeningSessions(sessions, next[0])
      saveListeningSessions(updated)
      return updated
    })
  }

  function knownSourceDuration(item: NowPlayingItem) {
    const episodes = catalogRef.current.flatMap((source) => source.episodes)
    const catalogDuration = episodes
      .filter((episode) => episode.id === item.id || episode.audioUrl === item.url)
      .reduce((duration, episode) => Math.max(duration, parseDuration(episode.duration)), 0)
    const historyDuration = historyRef.current
      .filter((entry) => entry.id === item.id || entry.url === item.url)
      .reduce((duration, entry) => Math.max(duration, entry.duration ?? 0), 0)
    return Math.max(catalogDuration, historyDuration)
  }

  // A Cast session can be started outside the app's output picker (system
  // picker, notification, another launch). Re-read the native state before each
  // playback so a new episode follows the live cast session instead of
  // starting on the phone while the old one keeps playing on the speaker.
  async function syncCastOutput() {
    if (activeOutputRef.current.kind !== 'phone' || isBoseOutputActive()) return
    try {
      const castState = await podmixPlayer.getCastState()
      if (!castState.connected) return
      const cast = { kind: 'cast' as const, id: 'cast', name: castState.deviceName || 'Google Cast' }
      activeOutputRef.current = cast
      setActiveOutput(cast)
    } catch { /* stay on the phone */ }
  }

  function autoplayOnCurrentOutput() {
    // A remote renderer owns playback. Preparing Media3 with autoplay would
    // start a second queue locally and let it advance while the remote output
    // buffers or switches media.
    return activeOutputRef.current.kind === 'phone' && !isBoseOutputActive()
  }

  async function advanceBoseTrackIfNeeded(relativePosition: number, playing: boolean) {
    const trackQueue = trackQueueRef.current
    if (!trackQueue || boseTrackTransitionRef.current) return relativePosition
    const absolutePosition = boseContentOffsetRef.current + Math.max(0, relativePosition)
    const targetIndex = trackQueue.tracks.reduce(
      (activeIndex, track, index) => absolutePosition >= track.time ? index : activeIndex,
      0,
    )
    const localState = await podmixPlayer.getState()
    if (targetIndex <= localState.queueIndex || targetIndex >= trackQueue.tracks.length) return relativePosition
    boseTrackTransitionRef.current = true
    try {
      let nextState = localState
      while (nextState.queueIndex < targetIndex && nextState.hasNext) {
        nextState = await podmixPlayer.next()
      }
      const track = trackQueue.tracks[nextState.queueIndex]
      if (!track) return relativePosition
      const nextTrack = trackQueue.tracks[nextState.queueIndex + 1]
      const nextPosition = Math.max(0, absolutePosition - track.time)
      const nextDuration = nextTrack?.time > track.time
        ? nextTrack.time - track.time
        : Math.max(0, nextState.durationSeconds)
      const nextItem: NowPlayingItem = {
        id: trackQueue.episodeId,
        title: track.title,
        artist: track.artist,
        url: trackQueue.audioUrl,
        artworkUrl: trackQueue.artworkUrls[nextState.queueIndex],
      }
      boseContentOffsetRef.current = track.time
      resetBoseClock(nextPosition, playing, boseClockRef.current.remotePositionSeconds)
      setActiveMediaId(trackQueue.mediaIds[nextState.queueIndex])
      setNowPlaying(nextItem)
      setGlobalDuration(nextDuration)
      persistBoseClock(nextPosition, playing, nextItem, nextDuration)
      return nextPosition
    } finally {
      boseTrackTransitionRef.current = false
    }
  }

  async function seekGlobal(position: number) {
    try {
      if (isBoseOutputActive() && nowPlaying?.url) {
        await sendToBose(
          nowPlaying,
          boseContentOffsetRef.current + Math.max(0, position),
          boseContentOffsetRef.current,
        )
        setGlobalPosition(Math.max(0, position))
        return
      }
      const state = await podmixPlayer.seekTo(Math.max(0, position))
      setGlobalPosition(Math.max(0, state.positionSeconds))
      setGlobalDuration(Math.max(0, state.durationSeconds))
      setActiveMediaId(resolveActiveMediaId(state))
      persistPlaybackState(state, true)
    } catch (error) {
      setDownloadMessage(error instanceof Error ? `Positionnement impossible : ${error.message}` : 'Positionnement impossible')
    }
  }

  async function toggleCurrentPlayback() {
    if (!nowPlaying) return
    beginPlaybackRequest()
    try {
      if (isBoseOutputActive()) {
        const ip = await connectBose()
        const remote = await podmixPlayer.boseGetState(ip)
        const position = syncBoseClock(remote.positionSeconds ?? 0, false)
        if (remote.playing) {
          await podmixPlayer.boseKey(ip, 'PAUSE')
          resetBoseClock(position, false, remote.positionSeconds ?? 0)
          persistBoseClock(position, false)
          setGlobalPosition(position)
          setGlobalPlaying(false)
          return
        }
        // Old SoundTouch firmware often closes the HTTP body when paused.
        // A bare PLAY then reports PLAY_STATE while audio and position remain
        // frozen.  Resume through a fresh relay and seek to the saved point.
        await sendToBose(
          nowPlaying,
          boseContentOffsetRef.current + position,
          boseContentOffsetRef.current,
          ip,
          activeOutputRef.current.kind === 'bose' ? activeOutputRef.current.name : undefined,
        )
        return
      }
      const currentState = await podmixPlayer.getState()
      const state = playbackIntentActive(currentState)
        ? await podmixPlayer.pause()
        : await podmixPlayer.play()
      setGlobalPlaying(playbackIntentActive(state))
    } catch (error) {
      setDownloadMessage(error instanceof Error ? `Commande de lecture impossible : ${error.message}` : 'Commande de lecture impossible')
    }
  }

  async function playEpisode(id: string, title: string, artist: string, url: string, artworkUrl?: string, resumePosition = 0, scope: NowPlayingItem['scope'] = 'episode', existingRequestId?: number) {
    if (!url) return
    const requestId = existingRequestId ?? beginPlaybackRequest()
    await syncCastOutput()
    // Les raccourcis « Reprendre », l'historique et certaines commandes
    // Android Auto arrivent directement ici.  Auparavant ils remplaçaient la
    // file de morceaux par un unique épisode, même quand une tracklist était
    // déjà connue.  Le lecteur de voiture ne pouvait alors afficher ni le
    // titre courant, ni Suivant, ni le favori.  Reprend la même file que le
    // bouton Lire de la page de l'épisode.
    if (scope === 'episode') {
      const source = catalog.find((candidate) =>
        candidate.episodes.some((episode) => episode.id === id),
      )
      const episode = source?.episodes.find((candidate) => candidate.id === id)
      if (source && episode?.tracks?.length) {
        await playFromSource(source, id, resumePosition)
        return
      }
    }
    const localUrl = offlineEpisodesRef.current.find((item) => item.id === id && item.status === 'completed' && item.localUri)?.localUri
    // Les entrées « Reprendre » et les raccourcis passent directement ici,
    // sans passer par playFromSource. Elles doivent donc bénéficier de la
    // même priorité au fichier local.
    const playbackUrl = localUrl || url
    try {
      setDownloadMessage('')
      if (nowPlaying?.id === id && nowPlaying.url === playbackUrl && nowPlaying.scope === scope) {
        const completed = globalDuration > 0 && globalPosition / globalDuration >= 0.98
        if (!completed) {
          await toggleCurrentPlayback()
          return
        }
      }
      trackQueueRef.current = undefined
      favoriteQueueRef.current = undefined
      episodePlaybackRef.current = undefined
      const knownDuration = Math.max(0, knownSourceDuration({ id, title, artist, url: playbackUrl, artworkUrl, scope }))
      const playbackPosition = knownDuration > 0 && resumePosition / knownDuration >= 0.98
        ? 0
        : Math.max(0, resumePosition)
      if (!isCurrentPlaybackRequest(requestId)) return
      await resetRepeatForNewQueue()
      const state = await podmixPlayer.setQueue(
        [{ id, url: playbackUrl, title, artist, artworkUrl, podmixSourceId: catalog.find((candidate) => candidate.episodes.some((episode) => episode.id === id))?.id, podmixEpisodeId: id, live: scope === 'radio' }],
        0,
        autoplayOnCurrentOutput(),
        playbackPosition,
      )
      if (!isCurrentPlaybackRequest(requestId)) return
      setGlobalPosition(Math.max(0, state.positionSeconds))
      setGlobalDuration(Math.max(0, state.durationSeconds))
      setActiveMediaId(resolveActiveMediaId(state))
      const item: NowPlayingItem = { id, title, artist, url: playbackUrl, artworkUrl, scope }
      setNowPlaying(item); setGlobalPlaying(playbackIntentActive(state))
      if (isBoseOutputActive()) await sendToBose(item, playbackPosition)
      else if (activeOutputRef.current.kind === 'cast') {
        await sendPreparedItemToCast(item, playbackPosition)
      }
      if (scope !== 'radio') {
        pushHistoryItem({ id, title, artist, url: playbackUrl, position: playbackPosition, duration: state.durationSeconds || undefined, playedAt: new Date().toISOString() })
      }
    } catch (error) {
      setDownloadMessage(error instanceof Error ? `Lecture impossible : ${error.message}` : 'Lecture impossible')
    }
  }

  async function playFromSource(source: CatalogSource, episodeId: string, startPosition?: number) {
    const requestId = beginPlaybackRequest()
    await syncCastOutput()
    // La page d'une source est aussi utilisable en avion : ne jamais repartir
    // sur l'URL RSS/YouTube si DownloadManager a bien produit le fichier local.
    // Cela vaut également pour la file de lecture continue et les morceaux.
    const offlineById = new Map(
      offlineEpisodesRef.current
        .filter((item) => item.status === 'completed' && item.localUri)
        .map((item) => [item.id, item.localUri!]),
    )
    let playbackSource: CatalogSource = {
      ...source,
      episodes: source.episodes.map((episode) => {
        const localUri = offlineById.get(episode.id)
        return localUri ? { ...episode, audioUrl: localUri } : episode
      }),
    }
    const candidate = playbackSource.episodes.find((episode) => episode.id === episodeId)
    // La résolution d'un set DJ exige le réseau. Elle est inutile — et
    // bloquante hors connexion — lorsque l'épisode existe localement.
    if (source.kind === 'dj' && candidate?.sourceUrl && !offlineById.has(candidate.id)) {
      try {
        const resolved = await resolveLiveSet(candidate.sourceUrl)
        if (!isCurrentPlaybackRequest(requestId)) return
        const fresh: Episode = {
          ...candidate,
          title: resolved.title || candidate.title,
          description: resolved.description || candidate.description,
          duration: resolved.duration ? formatTime(resolved.duration) : candidate.duration,
          audioUrl: resolved.audioUrl || candidate.audioUrl,
          artworkUrl: resolved.artworkUrl || candidate.artworkUrl,
        }
        playbackSource = {
          ...source,
          episodes: source.episodes.map((episode) => episode.id === episodeId ? mergeEpisode(episode, { ...fresh, id: episode.id }) : episode),
        }
        setCatalog((items) => items.map((item) => item.id === source.id ? playbackSource : item))
      } catch (error) {
        setDownloadMessage(error instanceof Error ? error.message : 'Actualisation du flux DJ impossible')
      }
    }
    const playable = playbackSource.episodes.filter((episode) => episode.audioUrl)
    const index = playable.findIndex((episode) => episode.id === episodeId)
    if (index < 0) return
    const episode = playable[index]
    if (nowPlaying?.id === episode.id && nowPlaying.url === episode.audioUrl && nowPlaying.scope === 'episode' && startPosition === undefined) {
      const completed = globalDuration > 0 && globalPosition / globalDuration >= 0.98
      if (!completed) {
        await toggleCurrentPlayback()
        return
      }
    }
    const resume = savedResumeForEpisode(episode).position
    const playbackPosition = Math.max(0, startPosition ?? resume)
    const queue = settings.continuousPlayback && source.kind !== 'dj' ? playable : [episode]
    const queueIndex = settings.continuousPlayback && source.kind !== 'dj' ? index : 0
    try {
      if (!isCurrentPlaybackRequest(requestId)) return
      if (episode.tracks?.length) {
        const trackIndex = episode.tracks.reduce(
          (selectedIndex, track, index) => playbackPosition >= track.time ? index : selectedIndex,
          0,
        )
        await playTrackFromSource(source, episode, trackIndex, playbackPosition, true, requestId)
        return
      }
      trackQueueRef.current = undefined
      favoriteQueueRef.current = undefined
      episodePlaybackRef.current = undefined
      setDownloadMessage('')
      await resetRepeatForNewQueue()
      if (!isCurrentPlaybackRequest(requestId)) return
      const state = await podmixPlayer.setQueue(
        queue.map((item) => ({
          id: item.id,
          url: item.audioUrl,
          title: item.title,
          artist: source.title,
          artworkUrl: item.artworkUrl || source.artworkUrl,
          podmixSourceId: source.id,
          podmixEpisodeId: item.id,
        })),
        queueIndex,
        autoplayOnCurrentOutput(),
        playbackPosition,
      )
      if (!isCurrentPlaybackRequest(requestId)) return
      setGlobalPosition(Math.max(0, state.positionSeconds))
      setGlobalDuration(Math.max(0, state.durationSeconds))
      setActiveMediaId(resolveActiveMediaId(state))
      const item: NowPlayingItem = { id: episode.id, title: episode.title, artist: source.title, url: episode.audioUrl, artworkUrl: episode.artworkUrl || source.artworkUrl, scope: 'episode' }
      setNowPlaying(item)
      setGlobalPlaying(playbackIntentActive(state))
      if (isBoseOutputActive()) {
        await sendToBose(item, playbackPosition)
      } else if (activeOutputRef.current.kind === 'cast') {
        await sendPreparedItemToCast(item, playbackPosition)
      }
      pushHistoryItem({ id: episode.id, title: episode.title, artist: source.title, url: episode.audioUrl, position: playbackPosition, duration: Math.max(parseDuration(episode.duration), state.durationSeconds) || undefined, playedAt: new Date().toISOString() })
    } catch (error) {
      setDownloadMessage(error instanceof Error ? `Lecture impossible : ${error.message}` : 'Lecture impossible')
    }
  }

  async function playSource(source: CatalogSource) {
    const firstPlayableEpisode = source.episodes.find((episode) =>
      Boolean(episode.audioUrl || (source.kind === 'dj' && episode.sourceUrl)),
    )
    if (!firstPlayableEpisode) {
      setDownloadMessage('Aucun épisode lisible dans cette source')
      return
    }
    // Le bouton « Lire » de la page d'une source doit reprendre la dernière
    // émission/épisode interrompu de cette source. Il forçait auparavant
    // 00:00, ce qui rendait les émissions impossibles à reprendre même si la
    // position avait bien été persistée pendant l'écoute.
    const resumableEpisode = source.episodes
      .map((episode) => ({ episode, resume: savedResumeForEpisode(episode) }))
      .find(({ resume }) => resume.position > 1)?.episode
    await playFromSource(source, resumableEpisode?.id ?? firstPlayableEpisode.id)
  }

  async function playTrackFromSource(source: CatalogSource, episode: Episode, trackIndex: number, startAbsolutePosition?: number, episodeMode = false, existingRequestId?: number) {
    const tracklist = episode.tracks ?? []
    const selectedTrack = tracklist[trackIndex]
    // Les rangées de morceaux doivent utiliser le même fichier local que
    // « Lire » au niveau de l'épisode. Sans cela, le bouton de morceau
    // repartait sur l'URL réseau après un téléchargement.
    const localUri = offlineEpisodesRef.current.find((item) => item.id === episode.id && item.status === 'completed' && item.localUri)?.localUri
    const playbackUrl = localUri || episode.audioUrl
    if (!playbackUrl || !selectedTrack) return
    const requestId = existingRequestId ?? beginPlaybackRequest()
    await syncCastOutput()
    // « Lire » au niveau de l'épisode doit inclure l'introduction, même si
    // le premier repère de tracklist commence plus tard. En revanche, un
    // appui direct sur un morceau conserve bien le début de ce morceau.
    const trackStartTime = episodeMode && trackIndex === 0 ? 0 : selectedTrack.time
    const playbackPosition = startAbsolutePosition === undefined
      ? 0
      : Math.max(0, startAbsolutePosition - trackStartTime)
    const queue = tracklist.map((track, index) => {
      const nextTime = tracklist[index + 1]?.time
      // Use the original episode URL and let the player clip the current
      // track. The segment proxy is not available on every configured API.
      const trackUrl = playbackUrl
      return {
        id: favoriteTrackKey(episode.id, track, index),
        url: trackUrl,
        title: track.title,
        artist: track.artist,
        artworkUrl: trackArtwork(track, episode, source),
        podmixSourceId: source.id,
        podmixEpisodeId: episode.id,
        startPositionSeconds: episodeMode && index === 0 ? 0 : Math.max(0, track.time),
        ...(nextTime > track.time ? { endPositionSeconds: nextTime } : {}),
      }
    })
    favoriteQueueRef.current = undefined
    episodePlaybackRef.current = episodeMode ? episode.id : undefined
    trackQueueRef.current = {
      episodeId: episode.id,
      episodeTitle: episode.title,
      sourceTitle: source.title,
      audioUrl: playbackUrl,
      tracks: tracklist,
      mediaIds: queue.map((item) => item.id),
      artworkUrls: queue.map((item) => item.artworkUrl),
    }
    try {
      setDownloadMessage('')
      if (!isCurrentPlaybackRequest(requestId)) return
      await resetRepeatForNewQueue()
      if (!isCurrentPlaybackRequest(requestId)) return
      const state = podmixPlayer.isNative
        ? await podmixPlayer.setQueue([{
            id: `episode::${episode.id}`,
            url: playbackUrl,
            title: episode.title,
            artist: source.title,
            artworkUrl: episode.artworkUrl || source.artworkUrl,
            podmixSourceId: source.id,
            podmixEpisodeId: episode.id,
            continuousEpisode: true,
          }], 0, autoplayOnCurrentOutput(), trackStartTime + playbackPosition)
        : await podmixPlayer.setQueue(queue, trackIndex, autoplayOnCurrentOutput(), playbackPosition)
      if (!isCurrentPlaybackRequest(requestId)) return
      setNowPlaying({
        id: episode.id,
        title: episodeMode ? episode.title : selectedTrack.title,
        artist: episodeMode ? source.title : selectedTrack.artist,
        url: playbackUrl,
        scope: episodeMode ? 'episode' : 'track',
        artworkUrl: trackArtwork(selectedTrack, episode, source),
      })
      setActiveMediaId(resolveActiveMediaId(state))
      setGlobalPlaying(playbackIntentActive(state))
      setGlobalPosition(Math.max(0, state.positionSeconds))
      setGlobalDuration(
        tracklist[trackIndex + 1]?.time > trackStartTime
          ? tracklist[trackIndex + 1].time - trackStartTime
          : Math.max(0, parseDuration(episode.duration) - trackStartTime),
      )
      if (isBoseOutputActive()) {
        await sendToBose({
          id: episode.id,
          title: selectedTrack.title,
          artist: selectedTrack.artist,
          url: playbackUrl,
          scope: 'track',
          artworkUrl: trackArtwork(selectedTrack, episode, source),
        }, trackStartTime, trackStartTime)
      } else if (activeOutputRef.current.kind === 'cast') {
        await sendPreparedItemToCast({
          id: episode.id,
          title: episodeMode ? episode.title : selectedTrack.title,
          artist: episodeMode ? source.title : selectedTrack.artist,
          url: playbackUrl,
          scope: episodeMode ? 'episode' : 'track',
          artworkUrl: trackArtwork(selectedTrack, episode, source),
        }, trackStartTime + playbackPosition, trackStartTime, tracklist[trackIndex + 1]?.time)
      }
      pushHistoryItem({
        id: episode.id,
        title: episode.title,
        artist: source.title,
        url: playbackUrl,
        position: Math.max(0, state.absolutePositionSeconds ?? (trackStartTime + state.positionSeconds)),
        duration: parseDuration(episode.duration) || undefined,
        playedAt: new Date().toISOString(),
      })
    } catch (error) {
      trackQueueRef.current = undefined
      setDownloadMessage(error instanceof Error ? `Lecture du titre impossible : ${error.message}` : 'Lecture du titre impossible')
    }
  }

  async function playFavoriteTracks(startIndex: number) {
    const requestId = beginPlaybackRequest()
    await syncCastOutput()
    const requestedEntry = favoriteTrackEntries[startIndex]
    const offlineById = new Map(
      offlineEpisodesRef.current
        .filter((item) => item.status === 'completed' && item.localUri)
        .map((item) => [item.id, item.localUri!]),
    )
    const playbackUrlFor = (entry: FavoriteTrackEntry) => offlineById.get(entry.episode.id) || entry.episode.audioUrl
    const entries = favoriteTrackEntries.filter((entry) => Boolean(playbackUrlFor(entry)))
    const selectedIndex = requestedEntry
      ? entries.findIndex((entry) => entry.key === requestedEntry.key)
      : 0
    const selectedEntry = entries[Math.max(0, selectedIndex)]
    if (!selectedEntry) return
    const queue = entries.map((entry) => {
      const tracks = entry.episode.tracks ?? []
      const nextTime = tracks[entry.trackIndex + 1]?.time
      const url = playbackUrlFor(entry)
      return {
        id: entry.key,
        url,
        title: entry.track.title,
        artist: entry.track.artist || entry.source.title,
        artworkUrl: trackArtwork(entry.track, entry.episode, entry.source),
        trackNavigation: true,
        startPositionSeconds: Math.max(0, entry.track.time),
        ...(nextTime !== undefined && nextTime > entry.track.time
          ? { endPositionSeconds: nextTime }
          : {}),
      }
    })
    trackQueueRef.current = undefined
    favoriteQueueRef.current = entries
    episodePlaybackRef.current = undefined
    try {
      setDownloadMessage('')
      if (!isCurrentPlaybackRequest(requestId)) return
      await resetRepeatForNewQueue()
      if (!isCurrentPlaybackRequest(requestId)) return
      const state = await podmixPlayer.setQueue(queue, Math.max(0, selectedIndex), autoplayOnCurrentOutput())
      if (!isCurrentPlaybackRequest(requestId)) return
      setNowPlaying({
        id: selectedEntry.episode.id,
        title: selectedEntry.track.title,
        artist: selectedEntry.track.artist || selectedEntry.source.title,
        url: playbackUrlFor(selectedEntry),
        scope: 'favorite',
        artworkUrl: trackArtwork(selectedEntry.track, selectedEntry.episode, selectedEntry.source),
      })
      setActiveMediaId(resolveActiveMediaId(state))
      setGlobalPlaying(playbackIntentActive(state))
      setGlobalPosition(0)
      const selectedTracks = selectedEntry.episode.tracks ?? []
      const nextSelectedTime = selectedTracks[selectedEntry.trackIndex + 1]?.time
      setGlobalDuration(
        nextSelectedTime > selectedEntry.track.time
          ? nextSelectedTime - selectedEntry.track.time
          : Math.max(0, parseDuration(selectedEntry.episode.duration) - selectedEntry.track.time),
      )
      if (isBoseOutputActive()) {
        await sendToBose({
          id: selectedEntry.episode.id,
          title: selectedEntry.track.title,
          artist: selectedEntry.track.artist || selectedEntry.source.title,
          url: playbackUrlFor(selectedEntry),
          scope: 'favorite',
          artworkUrl: trackArtwork(selectedEntry.track, selectedEntry.episode, selectedEntry.source),
        }, selectedEntry.track.time, selectedEntry.track.time)
      } else if (activeOutputRef.current.kind === 'cast') {
        await sendPreparedItemToCast({
          id: selectedEntry.episode.id,
          title: selectedEntry.track.title,
          artist: selectedEntry.track.artist || selectedEntry.source.title,
          url: playbackUrlFor(selectedEntry),
          scope: 'favorite',
          artworkUrl: trackArtwork(selectedEntry.track, selectedEntry.episode, selectedEntry.source),
        }, selectedEntry.track.time, selectedEntry.track.time, nextSelectedTime)
      }
    } catch (error) {
      favoriteQueueRef.current = undefined
      setDownloadMessage(error instanceof Error ? `Lecture des favoris impossible : ${error.message}` : 'Lecture des favoris impossible')
    }
  }

  async function shareFavoriteTrack(entry: FavoriteTrackEntry) {
    if (sharingFavoriteKey) return
    if (entry.source.kind === 'radio') {
      setDownloadMessage('Les radios en direct ne peuvent pas être partagées comme passage')
      return
    }
    const tracks = entry.episode.tracks ?? []
    const nextTime = tracks[entry.trackIndex + 1]?.time
    const episodeDuration = parseDuration(entry.episode.duration)
    const endSeconds = nextTime && nextTime > entry.track.time
      ? nextTime
      : episodeDuration > entry.track.time + 1 ? episodeDuration : undefined
    const sourceUrl = entry.episode.sourceUrl || entry.episode.audioUrl
    if (!sourceUrl.startsWith('https://')) {
      setDownloadMessage('Cette source ne fournit pas de lien HTTPS partageable')
      return
    }
    setSharingFavoriteKey(entry.key)
    try {
      const shared = await createSharePage({
        sourceKind: entry.source.kind,
        sourceTitle: entry.source.title,
        episodeTitle: entry.episode.title,
        artist: entry.track.artist || entry.source.title,
        trackTitle: entry.track.title,
        sourceUrl,
        // Le lien public RSS reste lu directement chez l’éditeur. Les DJ
        // sets n’envoient jamais le relais Podmix au destinataire.
        audioUrl: entry.source.kind === 'dj' ? undefined : entry.episode.audioUrl,
        artworkUrl: trackArtwork(entry.track, entry.episode, entry.source),
        spotifyUrl: entry.track.spotifyUrl,
        deezerUrl: entry.track.deezerUrl,
        startSeconds: entry.track.time,
        endSeconds,
      })
      const message = `🎧 ${entry.track.artist || entry.source.title} — ${entry.track.title}\nEntendu dans ${entry.source.title} à ${formatTime(entry.track.time)}`
      try {
        const shareOptions = { title: `${entry.track.artist || entry.source.title} — ${entry.track.title}`, text: message, url: shared.shareUrl, dialogTitle: 'Partager ce passage' }
        if (Capacitor.isNativePlatform()) {
          await Share.share(shareOptions)
        } else if (navigator.share) {
          await navigator.share(shareOptions)
        } else {
          throw new Error('Partage web indisponible')
        }
        setDownloadMessage('Lien de partage prêt')
      } catch {
        if (navigator.clipboard?.writeText) {
          await navigator.clipboard.writeText(`${message}\n${shared.shareUrl}`)
          setDownloadMessage('Partage indisponible : lien copié')
        } else {
          setDownloadMessage(`Lien prêt : ${shared.shareUrl}`)
        }
      }
    } catch (error) {
      setDownloadMessage(error instanceof Error ? `Partage impossible : ${error.message}` : 'Partage impossible')
    } finally {
      setSharingFavoriteKey('')
    }
  }

  async function cycleRepeatMode() {
    const nextMode = ((repeatMode + 1) % 3) as 0 | 1 | 2
    setRepeatMode(nextMode)
    try {
      await podmixPlayer.setRepeatMode(nextMode)
    } catch {
      setRepeatMode(repeatMode)
    }
  }

  async function skip(direction: 'next' | 'previous') {
    if (skipPendingRef.current) return
    // Le transport global ne navigue jamais entre épisodes. Sans tracklist,
    // il n'y a simplement aucun morceau vers lequel se déplacer. Sur Android,
    // le service conserve toutefois sa tracklist quand la WebView est recréée :
    // il doit donc toujours recevoir la commande et reconstruire l'état écran.
    if (!podmixPlayer.isNative && nowPlaying?.scope === 'episode' && !trackQueueRef.current) return
    beginPlaybackRequest()
    skipPendingRef.current = true
    setSkipPendingDirection(direction)
    try {
      const state = direction === 'next' ? await podmixPlayer.next() : await podmixPlayer.previous()
      restoreTrackQueueForMediaId(state.mediaId)
      setActiveMediaId(resolveActiveMediaId(state))
      setGlobalPlaying(playbackIntentActive(state))
      setGlobalPosition(Math.max(0, state.positionSeconds))
      setGlobalDuration(Math.max(0, state.durationSeconds))
      setCanSkipNext(state.hasNext)
      setCanSkipPrevious(state.hasPrevious)
      if (state.title) {
        const favoriteItem = favoriteQueueEntryForState(state)
        const trackQueue = trackQueueRef.current
        const activeTrack = trackQueue && state.queueIndex >= 0 && state.queueIndex < trackQueue.mediaIds.length ? trackQueue.tracks[state.queueIndex] : undefined
        const queued = catalog.flatMap((source) => source.episodes.map((episode) => ({ ...episode, artist: source.title }))).find((episode) => episode.id === state.mediaId)
        const nextPlaying = {
          id: favoriteItem?.episode.id ?? (activeTrack ? trackQueue!.episodeId : state.mediaId) ?? queued?.id ?? `${state.artist}:${state.title}`,
          title: favoriteItem?.track.title ?? activeTrack?.title ?? state.title,
          artist: favoriteItem?.track.artist ?? activeTrack?.artist ?? state.artist,
          url: favoriteItem
            ? preferredEpisodePlaybackUrl(favoriteItem.episode.id, favoriteItem.episode.audioUrl)
            : trackQueue?.audioUrl ?? queued?.audioUrl ?? nowPlaying?.url ?? '',
          scope: favoriteItem ? 'favorite' as const : activeTrack ? (episodePlaybackRef.current ? 'episode' as const : 'track' as const) : 'episode' as const,
          artworkUrl: favoriteItem
            ? trackArtwork(favoriteItem.track, favoriteItem.episode, favoriteItem.source)
            : activeTrack
              ? trackQueue?.artworkUrls[state.queueIndex]
              : queued?.artworkUrl,
        }
        setNowPlaying(nextPlaying)
        if (isBoseOutputActive() && nextPlaying.url) {
          const contentOffset = favoriteItem?.track.time ?? activeTrack?.time ?? 0
          const absolutePosition = contentOffset + state.positionSeconds
          await sendToBose(nextPlaying, absolutePosition, contentOffset)
        }
      }
    } catch (error) {
      setDownloadMessage(error instanceof Error ? `Changement de morceau impossible : ${error.message}` : 'Changement de morceau impossible')
    } finally {
      skipPendingRef.current = false
      setSkipPendingDirection(null)
    }
  }

  async function downloadEpisode(id: string, title: string, url: string, artist = nowPlaying?.artist ?? 'Podmix') {
    if (!url) return
    try {
      const state = await podmixPlayer.download(id, url, title)
      setOfflineEpisodes((items) => [{
        id,
        title,
        artist,
        remoteUrl: url,
        localUri: state.localUri,
        status: state.status === 'not_found' || state.status === 'unknown' ? 'queued' : state.status,
        bytesDownloaded: state.bytesDownloaded,
        totalBytes: state.totalBytes,
        addedAt: new Date().toISOString(),
      }, ...items.filter((item) => item.id !== id)])
      setDownloadMessage(state.status === 'completed' ? 'Déjà disponible hors connexion' : 'Téléchargement lancé')
      
      // If download completed and episode has tracks, extract them
      if (state.status === 'completed' && state.localUri) {
        const episode = catalog.flatMap(s => s.episodes).find(e => e.id === id)
        if (episode && episode.tracks && episode.tracks.length > 0) {
          try {
            await podmixPlayer.extractTracks({
              episodeId: id,
              audioPath: state.localUri,
              tracks: episode.tracks.map((track, index) => ({
                id: String(track.id || index),
                start: track.time,
                end: episode.tracks?.[index + 1]?.time ?? -1
              }))
            })
            console.log(`Extracted ${episode.tracks.length} tracks for episode ${id}`)
          } catch (extractError) {
            console.error('Failed to extract tracks:', extractError)
            // Non-fatal: episode is still playable with clipping
          }
        }
      }
    } catch (error) {
      setDownloadMessage(error instanceof Error ? error.message : 'Téléchargement impossible')
    }
  }

  async function downloadSourceEpisode(source: CatalogSource, episode: Episode) {
    let downloadUrl = episode.audioUrl
    if (source.kind === 'dj' && episode.sourceUrl) {
      // DownloadManager ne doit jamais recevoir l'URL CDN temporaire de
      // YouTube/SoundCloud : elle expire souvent avant la fin du fichier.
      downloadUrl = liveSetStreamUrl(episode.sourceUrl)
    }
    await downloadEpisode(episode.id, episode.title, downloadUrl, source.title)
  }

  async function playOffline(episode: OfflineEpisode) {
    const url = episode.localUri || episode.remoteUrl
    const sourceEntry = catalogRef.current
      .flatMap((source) => source.episodes.map((entry) => ({ source, entry })))
      .find(({ entry }) => entry.id === episode.id)
    try {
      // Use exactly the same queue builder as the online episode page. It
      // restores the saved position, track metadata and next/previous
      // controls while replacing the source URL with the downloaded file.
      if (sourceEntry) {
        await playFromSource(sourceEntry.source, sourceEntry.entry.id)
        return
      }
      const resumePosition = historyRef.current.find((item) => item.id === episode.id)?.position ?? 0
      await playEpisode(
        episode.id,
        episode.title,
        episode.artist,
        url,
        undefined,
        resumePosition,
        'episode',
      )
    } catch (error) {
      setDownloadMessage(error instanceof Error ? `Lecture hors connexion impossible : ${error.message}` : 'Lecture hors connexion impossible')
    }
  }

  async function removeOffline(episode: OfflineEpisode) {
    await podmixPlayer.removeDownload(episode.id)
    setOfflineEpisodes((items) => items.filter((item) => item.id !== episode.id))
    setDownloadMessage('Téléchargement supprimé')
  }

  async function refreshOutputDevices() {
    setDiscoveringOutputs(true)
    setCastMessage('')
    try {
      if (!podmixPlayer.isNative) {
        setOutputDevices([{
          id: 'phone',
          kind: 'phone',
          name: 'Ce navigateur',
          description: 'Lecture locale',
          connected: true,
        }])
        return
      }
      // A native discovery callback can occasionally remain pending after a
      // network reconnect. Never let it keep the output sheet (and its manual
      // Bose IP fallback) unavailable indefinitely.
      const limit = <T,>(promise: Promise<T>, milliseconds: number, message: string): Promise<T> => (
        Promise.race([
          promise,
          new Promise<never>((_, reject) => window.setTimeout(
            () => reject(new Error(message)),
            milliseconds,
          )),
        ])
      )
      const [castResult, boseResult, castStateResult] = await Promise.allSettled([
        limit(podmixPlayer.discoverCastDevices(), 5_000, 'Recherche Google Cast trop longue'),
        limit(podmixPlayer.boseDiscover(), 10_000, 'Recherche Bose trop longue'),
        limit(podmixPlayer.getCastState(), 5_000, 'État Google Cast indisponible'),
      ])
      const castState = castStateResult.status === 'fulfilled' ? castStateResult.value : { connected: false }
      const casts: OutputDevice[] = castResult.status === 'fulfilled'
        ? castResult.value.devices.map((device) => ({
            ...device,
            kind: 'cast' as const,
            connected: Boolean(device.connected || (castState.connected && device.name === castState.deviceName)),
          }))
        : []
      const boseDevices: Array<Extract<OutputDevice, { kind: 'bose' }>> = boseResult.status === 'fulfilled'
        ? boseResult.value.devices.map((device) => ({
            ...device,
            kind: 'bose' as const,
            connected: boseActiveRef.current && device.ip === boseIp,
          }))
        : []
      // A SoundTouch can refuse a subnet scan while still being reachable at
      // the address already used by Podmix. Keep that known speaker visible.
      const rememberedBoseIp = boseIp.trim()
      if (rememberedBoseIp && !boseDevices.some((device) => device.ip === rememberedBoseIp)) {
        try {
          const state = await podmixPlayer.boseGetState(rememberedBoseIp)
          boseDevices.push({
            id: rememberedBoseIp,
            ip: rememberedBoseIp,
            name: state.name || 'Bose SoundTouch',
            type: 'SoundTouch',
            kind: 'bose',
            connected: boseActiveRef.current,
          })
        } catch {
          // The saved address may belong to an old DHCP lease; keep scanning.
        }
      }
      const remoteConnected = castState.connected || boseActiveRef.current
      setOutputDevices([{
        id: 'phone',
        kind: 'phone',
        name: 'Ce téléphone',
        description: 'Haut-parleur ou casque connecté',
        connected: !remoteConnected,
      }, ...casts, ...boseDevices])
      if (castState.connected) {
        const cast = casts.find((device) => device.connected)
        setActiveOutput({ kind: 'cast', id: cast?.id ?? 'cast', name: castState.deviceName ?? cast?.name ?? 'Google Cast' })
      } else if (!boseActiveRef.current) {
        setActiveOutput({ kind: 'phone', id: 'phone', name: 'Ce téléphone' })
      }
      if (castResult.status === 'rejected' && boseResult.status === 'rejected') {
        setCastMessage('Aucun appareil détecté. Vérifiez le Wi-Fi.')
      }
    } finally {
      setDiscoveringOutputs(false)
    }
  }

  function openOutputPicker() {
    setShowOutputPicker(true)
    void refreshOutputDevices()
    if (boseActiveRef.current) void connectBose(true)
  }

  async function chooseCastDevice() {
    setShowOutputPicker(false)
    try {
      const result = await podmixPlayer.openCastPicker()
      if (result.connected === false) {
        setActiveOutput({ kind: 'phone', id: 'phone', name: 'Ce téléphone' })
        setCastMessage('Lecture revenue sur ce téléphone')
      }
    } catch (error) {
      setCastMessage(error instanceof Error ? error.message : 'Cast indisponible')
    }
  }

  async function sendPreparedItemToCast(
    item: NowPlayingItem,
    absolutePositionSeconds: number,
    positionOffsetSeconds = 0,
    endPositionSeconds?: number,
  ) {
    try {
      const castState = await podmixPlayer.getCastState()
      if (!castState.connected) throw new Error('Appareil Cast déconnecté')
      const live = item.scope === 'radio'
      const result = await podmixPlayer.cast({
        url: item.url,
        title: item.title,
        artist: item.artist,
        artworkUrl: item.artworkUrl,
        ...(item.scope === 'radio'
          ? {
              contentType: catalogRef.current.find((source) => source.id === item.id)?.streamContentType ?? 'audio/mpeg',
              live: true,
            }
          : {}),
        // A radio receiver must open the current stream head. Reusing the
        // phone's elapsed counter asks some receivers for an old buffered
        // position and makes a supposedly live station minutes late.
        positionSeconds: live ? 0 : Math.max(0, absolutePositionSeconds),
        positionOffsetSeconds: live ? 0 : Math.max(0, positionOffsetSeconds),
        ...(!live && endPositionSeconds !== undefined ? { endPositionSeconds } : {}),
      })
      boseActiveRef.current = false
      setGlobalPlaying(true)
      setCastMessage(`Lecture sur ${result.deviceName ?? 'Chromecast'}`)
      return true
    } catch (error) {
      // Never leave a freshly selected queue paused behind a stale Cast
      // route. The explicit selection still wins and resumes on the phone.
      const phone = { kind: 'phone' as const, id: 'phone', name: 'Ce téléphone' }
      activeOutputRef.current = phone
      setActiveOutput(phone)
      const localState = await podmixPlayer.play().catch(() => null)
      if (localState) {
        setGlobalPlaying(playbackIntentActive(localState))
        setGlobalPosition(Math.max(0, localState.positionSeconds))
        setGlobalDuration(Math.max(0, localState.durationSeconds))
      }
      setCastMessage(error instanceof Error
        ? `${error.message} · lecture reprise sur ce téléphone`
        : 'Cast indisponible · lecture reprise sur ce téléphone')
      return false
    }
  }

  async function castNowPlaying() {
    if (!nowPlaying?.url) {
      setCastMessage('Aucun média à diffuser')
      return
    }
    try {
      const castState = await podmixPlayer.getCastState()
      if (!castState.connected) {
        throw new Error('Appareil Cast déconnecté')
      }
      const playerState = await podmixPlayer.getState()
      const favoriteItem = favoriteQueueEntryForState(playerState)
      const trackQueue = trackQueueRef.current
      const trackIndex = trackQueue?.mediaIds.indexOf(playerState.mediaId) ?? -1
      const activeTrack = trackIndex >= 0 ? trackQueue?.tracks[trackIndex] : undefined
      const nextTrack = trackIndex >= 0 ? trackQueue?.tracks[trackIndex + 1] : undefined
      const positionOffsetSeconds = favoriteItem?.track.time ?? activeTrack?.time ?? 0
      const favoriteNextTrack = favoriteItem?.episode.tracks?.[favoriteItem.trackIndex + 1]
      const endPositionSeconds = favoriteNextTrack?.time ?? nextTrack?.time
      const live = nowPlaying.scope === 'radio'
      const result = await podmixPlayer.cast({
        url: nowPlaying.url,
        title: nowPlaying.title,
        artist: nowPlaying.artist,
        artworkUrl: nowPlaying.artworkUrl,
        ...(nowPlaying.scope === 'radio'
          ? {
              contentType: catalog.find((source) => source.id === nowPlaying.id)?.streamContentType ?? 'audio/mpeg',
              live: true,
            }
          : {}),
        positionSeconds: live ? 0 : positionOffsetSeconds + playerState.positionSeconds,
        positionOffsetSeconds: live ? 0 : positionOffsetSeconds,
        ...(!live && endPositionSeconds !== undefined ? { endPositionSeconds } : {}),
      })
      boseActiveRef.current = false
      setGlobalPlaying(true)
      setCastMessage(`Lecture sur ${result.deviceName ?? 'Chromecast'}`)
    } catch (error) {
      setCastMessage(error instanceof Error ? error.message : 'Diffusion impossible')
      throw error
    }
  }

  async function discoverBose(): Promise<string> {
    setBoseMessage('Recherche automatique sur le réseau local…')
    const result = await podmixPlayer.boseDiscover()
    const device = result.devices[0]
    if (!device) throw new Error('Aucune Bose SoundTouch détectée sur le réseau local')
    setBoseIp(device.ip)
    const state = await podmixPlayer.boseGetState(device.ip)
    setBoseVolume(state.volume ?? 30)
    setBoseMessage(`${state.name || device.name || 'SoundTouch'} détectée automatiquement`)
    return device.ip
  }

  async function connectBose(silent = false, syncVolume = true): Promise<string> {
    try {
      const savedIp = preferredBoseIp() || boseIp.trim()
      if (savedIp) {
        try {
          const state = await podmixPlayer.boseGetState(savedIp)
          if (syncVolume) setBoseVolume(state.volume ?? 30)
          setBoseMessage(`${state.name || 'SoundTouch'} détectée automatiquement`)
          return savedIp
        } catch {
          // L'adresse DHCP a pu changer : un balayage local prend le relais.
        }
      }
      return await discoverBose()
    } catch (error) {
      setBoseMessage(error instanceof Error ? error.message : 'Enceinte inaccessible')
      if (!silent) throw error
      return ''
    }
  }

  async function restorePhoneAsDefaultOutput() {
    // Keep the remembered address for a future explicit selection, but never
    // persist the Bose as the active playback route.
    localStorage.removeItem('podmix-bose-session-v1')
    localStorage.removeItem(BOSE_OUTPUT_SELECTION_KEY)
    boseActiveRef.current = false
    const phone = { kind: 'phone' as const, id: 'phone', name: 'Ce téléphone' }
    activeOutputRef.current = phone
    setActiveOutput(phone)
  }

  async function restoreCastOutputAfterLaunch(expectedPlaybackGeneration: number) {
    try {
      const castState = await podmixPlayer.getCastState()
      if (!castState.connected || playbackRequestRef.current !== expectedPlaybackGeneration) return false
      const state = await podmixPlayer.getState()
      if (playbackRequestRef.current !== expectedPlaybackGeneration) return false
      const cast = {
        kind: 'cast' as const,
        id: 'cast',
        name: castState.deviceName || 'Google Cast',
      }
      activeOutputRef.current = cast
      setActiveOutput(cast)
      setGlobalPosition(Math.max(0, state.positionSeconds))
      setGlobalDuration(Math.max(0, state.durationSeconds))
      setGlobalPlaying(playbackIntentActive(state))
      setActiveMediaId(resolveActiveMediaId(state))
      setCastMessage(`${cast.name} reconnecté`)
      return true
    } catch {
      return false
    }
  }

  async function restoreOutputAfterLaunch() {
    const expectedPlaybackGeneration = playbackRequestRef.current
    // SoundTouch does not expose a platform-owned cast session. Any saved
    // route is therefore stale after a process relaunch and must be discarded.
    localStorage.removeItem('podmix-bose-session-v1')
    localStorage.removeItem(BOSE_OUTPUT_SELECTION_KEY)
    boseActiveRef.current = false
    bosePollFailuresRef.current = 0
    if (!await restoreCastOutputAfterLaunch(expectedPlaybackGeneration)
      && playbackRequestRef.current === expectedPlaybackGeneration) {
      await restorePhoneAsDefaultOutput()
    }
  }

  async function sendToBose(
    item: NowPlayingItem,
    absolutePositionSeconds: number,
    contentOffsetSeconds = 0,
    targetIp?: string,
    targetName?: string,
  ) {
    const transferableItem = boseTransferItem(item)
    const transferGeneration = ++boseSendGenerationRef.current
    const queued = boseSendQueueRef.current
      .catch(() => undefined)
      .then(async () => {
        if (transferGeneration !== boseSendGenerationRef.current) return
        boseTransferPendingRef.current = true
        try {
          return await sendToBoseNow(
            transferableItem,
            absolutePositionSeconds,
            contentOffsetSeconds,
            targetIp,
            targetName,
            transferGeneration,
          )
        } finally {
          if (transferGeneration === boseSendGenerationRef.current) {
            boseTransferPendingRef.current = false
          }
        }
      })
    const resilient = queued.catch(async (error) => {
      if (transferGeneration !== boseSendGenerationRef.current) return
      if (!isBoseOutputActive()) throw error
      // Bose remains the chosen output until the user explicitly selects the
      // phone.  Falling back here used to leave the old episode on Bose while
      // the new one started locally.
      setBoseMessage(error instanceof Error
        ? `Bose indisponible · ${error.message}`
        : 'Bose indisponible · le téléphone n’a pas été utilisé')
      throw error
    })
    boseSendQueueRef.current = resilient.catch(() => undefined)
    return resilient
  }

  async function sendToBoseNow(
    item: NowPlayingItem,
    absolutePositionSeconds: number,
    contentOffsetSeconds = 0,
    targetIp?: string,
    targetName?: string,
    transferGeneration = boseSendGenerationRef.current,
  ) {
    const live = item.scope === 'radio'
    const requestedAbsolutePosition = live ? 0 : Math.max(0, absolutePositionSeconds)
    const requestedContentOffset = live ? 0 : Math.max(0, contentOffsetSeconds)
    const ip = targetIp || await connectBose()
    if (transferGeneration !== boseSendGenerationRef.current) return
    setBoseIp(ip)
    setBoseMessage('Vérification du flux pour l’enceinte…')
    // A SoundTouch can keep an HTTP episode connection alive even after a
    // SetAVTransportURI replacement.  For finite content, always give it a
    // fresh Podmix relay URL (one unique token per selection): this makes an
    // episode/DJ-set/track replacement unambiguous. Live radios retain the
    // direct path when possible, as their stream must stay continuous.
    const canTryDirect = item.scope === 'radio'
      && /^http:\/\//i.test(item.url)
      && !/\.m3u8(?:$|[?#])/i.test(item.url)
    let streamStart = 0
    let direct = false
    if (canTryDirect) {
      try {
        await podmixPlayer.bosePlay(ip, item.url, item.title, Math.floor(requestedAbsolutePosition))
        await new Promise((resolve) => window.setTimeout(resolve, 1200))
        if (transferGeneration !== boseSendGenerationRef.current) return
        const state = await podmixPlayer.boseGetState(ip)
        const reportedPosition = Math.max(0, state.positionSeconds ?? 0)
        direct = Boolean(state.playing) && (
          live
          || requestedAbsolutePosition <= 2
          || Math.abs(reportedPosition - requestedAbsolutePosition) <= 20
        )
      } catch {
        direct = false
      }
    }
    if (!direct) {
      setBoseMessage('Adaptation du flux pour l’enceinte…')
      // Never start an old SoundTouch in the middle of an MP3 byte stream.
      // Its decoder accepts the URL but can remain at 0:00 forever when the
      // approximate byte offset lands between MPEG frames.  Give it a clean
      // relay from byte zero, then use the AVTransport Seek command once the
      // stream is open.  If a particular source does not support seeking, the
      // native layer safely falls back to the beginning instead of blocking.
      const relay = await createBoseCastSession(item.url, item.title, 0, 0)
      if (transferGeneration !== boseSendGenerationRef.current) return
      // Les SoundTouch anciennes n'acceptent pas le TLS de nombreux flux radio.
      // Quand le mini-PC est sur le même Wi-Fi, son relais HTTP local est donc
      // prioritaire ; les autres enceintes gardent l'URL publique HTTPS.
      await podmixPlayer.bosePlay(
        ip,
        relay.lanRelayUrl || relay.relayUrl,
        item.title,
        Math.floor(requestedAbsolutePosition),
      )
      streamStart = relay.startSeconds
    }
    if (transferGeneration !== boseSendGenerationRef.current) return
    const localState = await podmixPlayer.getState()
    // When the user selects a discovered Bose, targetIp is already known and
    // connectBose() is intentionally skipped. Read the real device volume
    // after the confirmed transfer so the dock never shows the default 30%
    // while the speaker is actually at another level.
    try {
      const remoteState = await podmixPlayer.boseGetState(ip)
      if (transferGeneration !== boseSendGenerationRef.current) return
      if (remoteState.volume !== undefined) setBoseVolume(remoteState.volume)
    } catch {
      // Playback has already been confirmed by bosePlay. A transient volume
      // read must not turn a successful transfer into a failure.
    }
    boseStartPositionRef.current = streamStart
    boseContentOffsetRef.current = requestedContentOffset
    bosePersistedAtRef.current = 0
    bosePollFailuresRef.current = 0
    const relativePosition = Math.max(0, requestedAbsolutePosition - requestedContentOffset)
    resetBoseClock(relativePosition, true)
    await podmixPlayer.pause()
    setGlobalPosition(relativePosition)
    setGlobalPlaying(true)
    const name = targetName || activeOutput.name || 'Bose SoundTouch'
    selectBoseOutput(ip, name)
    localStorage.setItem('podmix-bose-session-v1', JSON.stringify({
      ip,
      name,
      item,
      contentOffsetSeconds: requestedContentOffset,
      streamStartSeconds: streamStart,
      durationSeconds: Math.max(0, localState.durationSeconds),
      positionSeconds: relativePosition,
      playing: true,
      savedAt: Date.now(),
    } satisfies StoredBoseSession))
    setBoseMessage(`${direct ? 'Flux direct' : 'Flux adapté'} · ${name}`)
  }

  async function playOnBose(target?: BoseDevice) {
    const item = nowPlayingRef.current
    if (!item?.url) throw new Error('Aucun média à envoyer')
    const playerState = await podmixPlayer.getState()
    const favoriteItem = favoriteQueueEntryForState(playerState)
    const trackQueue = trackQueueRef.current
    const trackIndex = trackQueue?.mediaIds.indexOf(playerState.mediaId) ?? -1
    const activeTrack = trackIndex >= 0 ? trackQueue?.tracks[trackIndex] : undefined
    const contentOffset = favoriteItem?.track.time ?? activeTrack?.time ?? 0
    const absolutePosition = contentOffset + playerState.positionSeconds
    await sendToBose(item, absolutePosition, contentOffset, target?.ip, target?.name)
  }

  async function fallbackUnavailableBoseToPhone() {
    const stored = loadBoseSession()
    const relativePosition = Math.max(0, currentBoseClockPosition())
    const shouldResume = Boolean(stored?.playing || boseClockRef.current.playing)
    boseSendGenerationRef.current += 1
    bosePollFailuresRef.current = 0
    boseActiveRef.current = false
    localStorage.removeItem(BOSE_OUTPUT_SELECTION_KEY)
    localStorage.removeItem('podmix-bose-session-v1')
    resetBoseClock(relativePosition, false)
    const phone = { kind: 'phone' as const, id: 'phone', name: 'Ce téléphone' }
    activeOutputRef.current = phone
    setActiveOutput(phone)
    try {
      const positioned = await podmixPlayer.seekTo(relativePosition)
      const localState = shouldResume ? await podmixPlayer.play() : await podmixPlayer.pause()
      setGlobalPosition(Math.max(0, positioned.positionSeconds))
      setGlobalDuration(Math.max(0, positioned.durationSeconds))
      setGlobalPlaying(playbackIntentActive(localState))
      persistPlaybackState({ ...localState, positionSeconds: relativePosition }, true)
      setCastMessage(shouldResume
        ? 'Bose hors de portée · lecture reprise sur ce téléphone'
        : 'Bose hors de portée · sortie revenue sur ce téléphone')
    } catch (error) {
      setGlobalPlaying(false)
      setCastMessage(error instanceof Error
        ? `Bose hors de portée · retour téléphone incomplet : ${error.message}`
        : 'Bose hors de portée · retour téléphone incomplet')
    }
    setBoseMessage('')
  }

  async function returnPlaybackToPhone() {
    boseSendGenerationRef.current += 1
    localStorage.removeItem(BOSE_OUTPUT_SELECTION_KEY)
    if (boseActiveRef.current || activeOutputRef.current.kind === 'bose') {
      let relativePosition = currentBoseClockPosition()
      let shouldResume = globalPlaying
      try {
        const state = await podmixPlayer.boseGetState(boseIp.trim())
        relativePosition = syncBoseClock(state.positionSeconds ?? 0, Boolean(state.playing))
        shouldResume = Boolean(state.playing)
      } catch {
        // Hors de portée, la Bose ne peut plus répondre. La file Media3 est
        // néanmoins encore prête : on la reprend immédiatement en local.
      }
      await podmixPlayer.boseKey(boseIp.trim(), 'STOP').catch(() => undefined)
      boseActiveRef.current = false
      resetBoseClock(relativePosition, false)
      localStorage.removeItem('podmix-bose-session-v1')
      try {
        const localState = await podmixPlayer.seekTo(relativePosition)
        const resumed = shouldResume ? await podmixPlayer.play() : await podmixPlayer.pause()
        setGlobalPosition(relativePosition)
        setGlobalDuration(Math.max(0, localState.durationSeconds))
        setGlobalPlaying(playbackIntentActive(resumed))
        persistPlaybackState({ ...resumed, positionSeconds: relativePosition }, true)
      } catch (error) {
        setBoseMessage(error instanceof Error ? error.message : 'Retour sur téléphone impossible')
      }
    } else {
      const castState = await podmixPlayer.getCastState()
      if (castState.connected) {
        const state = await podmixPlayer.disconnectCast()
        setGlobalPosition(Math.max(0, state.positionSeconds))
        setGlobalDuration(Math.max(0, state.durationSeconds))
        setGlobalPlaying(playbackIntentActive(state))
        persistPlaybackState(state, true)
      }
    }
    localStorage.removeItem('podmix-bose-session-v1')
    boseActiveRef.current = false
    const phone = { kind: 'phone' as const, id: 'phone', name: 'Ce téléphone' }
    activeOutputRef.current = phone
    setActiveOutput(phone)
    setCastMessage('Lecture sur ce téléphone')
    setBoseMessage('')
  }

  async function selectOutputDevice(device: OutputDevice) {
    // Invalidate any launch-time output restoration still in flight.  A late
    // restore must never overwrite the output the user just selected.
    beginPlaybackRequest()
    setConnectingOutputId(device.kind === 'bose' ? device.ip : device.id)
    try {
      if (device.kind === 'phone') {
        await returnPlaybackToPhone()
      } else if (device.kind === 'bose') {
        // Google Cast is unrelated to SoundTouch.  On some Android devices,
        // asking Play services for its state can remain pending and used to
        // prevent the Bose selection from ever reaching the speaker.
        try {
          const castState = await Promise.race([
            podmixPlayer.getCastState(),
            new Promise<{ connected: false }>((resolve) => window.setTimeout(
              () => resolve({ connected: false }),
              2_000,
            )),
          ])
          if (castState.connected) await podmixPlayer.disconnectCast()
        } catch {
          // Bose playback does not depend on Google Cast being available.
        }
        await playOnBose(device)
      } else {
        if (boseActiveRef.current) await returnPlaybackToPhone()
        localStorage.removeItem('podmix-bose-session-v1')
        const result = await podmixPlayer.connectCastDevice(device.id)
        await castNowPlaying()
        try {
          const volumeState = await podmixPlayer.getCastVolume()
          setCastVolume(Math.round(volumeState.volume * 100))
        } catch {}
        setActiveOutput({ kind: 'cast', id: device.id, name: result.deviceName ?? device.name })
      }
      setShowOutputPicker(false)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Transfert impossible'
      if (device.kind === 'bose') {
        console.error('[Podmix Bose] Transfert impossible', error)
        setBoseMessage(`Connexion impossible : ${message}`)
      }
      else setCastMessage(message)
    } finally {
      setConnectingOutputId('')
    }
  }

  function manualBoseDevice(): OutputDevice | undefined {
    const ip = boseIp.trim()
    if (!/^192\.168\.\d{1,3}\.\d{1,3}$|^10\.\d{1,3}\.\d{1,3}\.\d{1,3}$|^172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}$/.test(ip)) return undefined
    return { id: ip, ip, name: 'Bose SoundTouch', type: 'Adresse manuelle', kind: 'bose', connected: false }
  }

  async function changeBoseVolume(value: number) {
    setBoseVolume(value)
    boseVolumePendingRef.current = value
    if (boseVolumeSendingRef.current) return
    boseVolumeSendingRef.current = true
    try {
      const ip = await connectBose(false, false)
      while (boseVolumePendingRef.current !== null) {
        const nextVolume = boseVolumePendingRef.current
        boseVolumePendingRef.current = null
        await podmixPlayer.boseSetVolume(ip, nextVolume)
        if (boseVolumePendingRef.current === null) {
          setBoseVolume(nextVolume)
          setBoseMessage(`Volume ${nextVolume}%`)
        }
      }
    } catch (error) {
      boseVolumePendingRef.current = null
      setBoseMessage(error instanceof Error ? error.message : 'Volume non modifié')
    } finally {
      boseVolumeSendingRef.current = false
    }
  }

  async function changeCastVolume(value: number) {
    setCastVolume(value)
    try {
      await podmixPlayer.setCastVolume({ volume: value / 100 })
    } catch (error) {
      setCastMessage(error instanceof Error ? error.message : 'Volume Cast impossible')
    }
  }

  async function changePlaybackVolume(value: number) {
    if (activeOutput.kind === 'cast') {
      await changeCastVolume(value)
      return
    }
    if (activeOutput.kind === 'bose') {
      await changeBoseVolume(value)
      return
    }
    setPlayerVolume(value)
    try {
      await podmixPlayer.setVolume({ volume: value / 100 })
    } catch (error) {
      setCastMessage(error instanceof Error ? error.message : 'Volume du lecteur impossible')
    }
  }

  const playbackVolume = activeOutput.kind === 'cast' ? castVolume : activeOutput.kind === 'bose' ? boseVolume : playerVolume
  const playbackVolumeLabel = activeOutput.kind === 'phone' ? 'Téléphone' : activeOutput.name

  function changeSetting<K extends keyof AppSettings>(key: K, value: AppSettings[K]) {
    setSettings((current) => ({ ...current, [key]: value }))
  }

  function exportBackup() {
    const payload = {
      format: 'podmix-next-backup',
      version: 1,
      createdAt: new Date().toISOString(),
      catalog,
      favoriteTrackIds,
      history,
      listeningSessions,
      offlineEpisodes,
      settings,
      studio: { audioName, tracks },
    }
    const url = URL.createObjectURL(new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }))
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `podmix-backup-${new Date().toISOString().slice(0, 10)}.json`
    anchor.click()
    URL.revokeObjectURL(url)
    setBackupMessage('Sauvegarde exportée')
  }

  async function importBackup(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file) return
    try {
      const payload = JSON.parse(await file.text()) as {
        format?: string; catalog?: CatalogSource[]; favoriteSourceIds?: string[]; favoriteTrackIds?: Array<string | number>
        history?: HistoryItem[]; listeningSessions?: ListeningSession[]; offlineEpisodes?: OfflineEpisode[]; settings?: AppSettings
        studio?: { audioName?: string; tracks?: Track[] }
      }
      if (payload.format !== 'podmix-next-backup' || !Array.isArray(payload.catalog)) throw new Error('Format de sauvegarde invalide')
      setCatalog((current) => [...payload.catalog!, ...current.filter((source) => !payload.catalog!.some((imported) => imported.id === source.id))])
      setFavoriteTrackIds((payload.favoriteTrackIds ?? []).map(String))
      setHistory(payload.history ?? [])
      setListeningSessions(payload.listeningSessions ?? [])
      setOfflineEpisodes(payload.offlineEpisodes ?? [])
      if (payload.settings) setSettings(payload.settings)
      if (payload.studio?.tracks?.length) {
        setTracks(payload.studio.tracks)
        setAudioName(payload.studio.audioName ?? 'Session migrée')
        setSelectedId(payload.studio.tracks[0].id)
      }
      setBackupMessage(`${payload.catalog.length} source(s) importée(s)`)
    } catch (error) {
      setBackupMessage(error instanceof Error ? error.message : 'Import impossible')
    } finally {
      event.target.value = ''
    }
  }

  async function addPodcastResult(result: PodcastSearchResult) {
    setFeedUrl(result.feedUrl)
    setAddingFeed(true); setFeedError('')
    try {
      const source = await importRssFeed(result.feedUrl, sourceMode === 'show' ? 'show' : 'podcast', sourceMode === 'show' ? settings.maxShowEpisodes : settings.maxPodcastEpisodes)
      const isNewPodcast = !catalogRef.current.some((item) => item.id === source.id)
      setCatalog((items) => [source, ...items.filter((item) => item.id !== source.id)])
      if (source.kind !== 'show' && isNewPodcast && settings.automaticAnalysis) {
        enqueueNewEpisodesForTimestamping(source.episodes)
        activeTimestampSourceId.current = source.id
        localStorage.setItem('podmix-active-timestamp-source-v1', source.id)
        void scheduleNextTimestamping([source, ...catalogRef.current.filter((item) => item.id !== source.id)])
      }
      establishRssBaseline(source.id)
      setPodcastResults([]); setShowAddSource(false); setSelectedSourceId(source.id); setActiveView('home')
    } catch (error) {
      setFeedError(error instanceof Error ? error.message : 'Podcast indisponible')
    } finally {
      setAddingFeed(false)
    }
  }

  async function refreshSelectedSource() {
    if (!selectedSource?.feedUrl) return
    if (selectedSource.kind === 'dj') {
      const episode = selectedSource.episodes[0]
      if (episode) await refreshLiveSetEpisode(selectedSource, episode)
      return
    }
    setRefreshingSource(true); setFeedError('')
    let feedError = ''
    try {
      let refreshedSource = selectedSource
      try {
        const refreshed = await importRssFeed(selectedSource.feedUrl, selectedSource.kind === 'show' ? 'show' : 'podcast', selectedSource.kind === 'show' ? settings.maxShowEpisodes : settings.maxPodcastEpisodes)
        const newlyArrived = newEpisodesFromFeed(selectedSource, refreshed)
        const merged = mergeCatalogSource(selectedSource, refreshed)
        const visibleEpisodeIds = new Set(merged.episodes.map((episode) => episode.id))
        refreshedSource = newlyArrived.length
          ? { ...merged, newEpisodeIds: [...new Set([...(merged.newEpisodeIds ?? []), ...newlyArrived.map((episode) => episode.id)])].filter((id) => visibleEpisodeIds.has(id)) }
          : merged
      } catch (error) {
        // Le bouton Actualiser doit aussi pouvoir relancer les timestamps quand
        // le flux RSS est momentanément indisponible.
        feedError = error instanceof Error ? error.message : 'Actualisation du flux impossible'
      }

      if (selectedSource.kind === 'show') {
        setCatalog((items) => items.map((source) => source.id === selectedSource.id ? stripShowTimestamping(refreshedSource) : source))
        if (feedError) setFeedError(feedError)
        return
      }
      timestampQueueBusy.current = true
      let resetSource: CatalogSource
      try {
        resetSource = await resetTimestampingForEpisodes(refreshedSource, refreshedSource.episodes)
        setCatalog((items) => items.map((source) => source.id === selectedSource.id ? resetSource : source))
      } finally {
        timestampQueueBusy.current = false
      }
      manualTimestampQueueActive.current = true
      activeTimestampSourceId.current = resetSource!.id
      localStorage.setItem('podmix-active-timestamp-source-v1', resetSource!.id)
      await scheduleNextTimestamping([resetSource!, ...catalogRef.current.filter((item) => item.id !== resetSource!.id)])
      const count = resetSource!.episodes.filter((episode) => episode.audioUrl).length
      setDownloadMessage(`${count} épisode${count > 1 ? 's' : ''} réinitialisé${count > 1 ? 's' : ''} et relancé${count > 1 ? 's' : ''}`)
      if (feedError) setFeedError(`${feedError} · La relance des timestamps a bien démarré.`)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Actualisation impossible'
      setFeedError(message)
      setDownloadMessage(message)
    } finally {
      setRefreshingSource(false)
    }
  }

  async function removeSelectedSource() {
    if (!selectedSource) return
    await Promise.all(selectedSource.episodes.map(async (episode) => {
      try {
        const job = await findDetectionJob(`episode:${episode.id}`)
        if (job && analysisIsActive(job.status)) await cancelDetectionJob(job.id)
      } catch {
        // La suppression locale doit rester possible même si le VPS est hors ligne.
      }
    }))
    if (activeTimestampSourceId.current === selectedSource.id) {
      activeTimestampSourceId.current = ''
      localStorage.removeItem('podmix-active-timestamp-source-v1')
    }
    setCatalog((items) => items.filter((source) => source.id !== selectedSource.id))
    setSelectedSourceId('')
    setSelectedEpisodeId('')
  }

  async function openEpisodeInStudio(sourceOverride?: CatalogSource, episodeOverride?: Episode) {
    const source = sourceOverride ?? selectedSource
    const episode = episodeOverride ?? selectedEpisode
    if (!episode || !source) return
    setSelectedSourceId(source.id)
    setSelectedEpisodeId(episode.id)
    trackQueueRef.current = undefined
    favoriteQueueRef.current = undefined
    waveRef.current?.destroy()
    waveRef.current = null
    setHasLocalAudio(false)
    setCurrentJobId('')
    setCurrentTime(0)
    setDuration(parseDuration(episode.duration))
    setAudioName(episode.title)
    const target = { sourceId: source.id, episodeId: episode.id }
    setStudioEpisode(target)
    studioEpisodeRef.current = target
    if (episode.tracks?.length) {
      setTracks(episode.tracks)
      setSelectedId(episode.tracks[0].id)
    } else {
      setTracks([])
      setSelectedId(0)
    }
    setActiveView('studio')
    if (!episode.audioUrl) {
      setDetectionError('Cet épisode ne contient aucune URL audio')
      return
    }
    try {
      await resetRepeatForNewQueue()
      const state = await podmixPlayer.setQueue([{
        id: episode.id,
        url: episode.audioUrl,
        title: episode.title,
        artist: source.title,
        artworkUrl: episode.artworkUrl || source.artworkUrl,
      }], 0, false)
      setNowPlaying({ id: episode.id, title: episode.title, artist: source.title, url: episode.audioUrl, artworkUrl: episode.artworkUrl || source.artworkUrl })
      if (state.durationSeconds > 0) setDuration(state.durationSeconds)
      setDetectionError('')
      if (analysisIsActive(episode.analysis?.status) && episode.analysis?.jobId) {
        try {
          watchDetectionJob(await getDetectionJob(episode.analysis.jobId), target)
        } catch {
          void startDetection(target)
        }
      } else if (source.kind !== 'show' && settings.automaticAnalysis && needsTimestamping(episode.tracks, episode.analysis)) {
        void startDetection(target)
      }
    } catch (error) {
      setDetectionError(error instanceof Error ? `Chargement audio impossible : ${error.message}` : 'Chargement audio impossible')
    }
  }

  function publishTracklist() {
    if (studioEpisode) {
      setCatalog((sources) => sources.map((source) => source.id !== studioEpisode.sourceId ? source : {
        ...source,
        episodes: source.episodes.map((episode) => episode.id === studioEpisode.episodeId ? { ...episode, tracks } : episode),
      }))
      setPublishMessage('Tracklist enregistrée dans l’épisode')
    } else {
      void saveSession(audioName, tracks)
      setPublishMessage('Session Studio enregistrée')
    }
    window.setTimeout(() => setPublishMessage(''), 2500)
  }

  function addRadio(source: CatalogSource) {
    setCatalog((items) => [source, ...items.filter((item) => item.id !== source.id)])
    setShowAddSource(false); setActiveView('home'); setRadioResults([])
  }

  function toggleTrackFavorite(id: string) {
    setFavoriteTrackIds((items) => items.includes(id) ? items.filter((item) => item !== id) : [...items, id])
  }

  async function saveAndTestServer() {
    setServerMessage('Connexion…')
    try {
      const saved = setApiUrl(serverUrl)
      setServerUrlState(saved)
      const health = await testApi()
      setServerMessage(`Connecté · ${health.mode}`)
    } catch (error) {
      setServerMessage(error instanceof Error ? error.message : 'Serveur inaccessible')
    }
  }

  const selectedSource = catalog.find((source) => source.id === selectedSourceId)
  const selectedEpisode = selectedSource?.episodes.find((episode) => episode.id === selectedEpisodeId)
  const selectedEpisodeOffline = selectedEpisode
    ? offlineEpisodes.find((episode) => episode.id === selectedEpisode.id)
    : undefined
  const selectedEpisodeIsOffline = selectedEpisodeOffline?.status === 'completed'
  const selectedEpisodeResumePosition = selectedEpisode
    ? savedResumeForEpisode(selectedEpisode).position
    : 0
  const selectedEpisodeTimestampStatus = selectedEpisode ? episodeTimestampStatus(selectedEpisode) : undefined
  const playerFavorite = (() => {
    const queue = trackQueueRef.current
    const queuedTrackIndex = queue?.mediaIds.indexOf(activeMediaId) ?? -1
    if (queue && queuedTrackIndex >= 0) {
      const track = queue.tracks[queuedTrackIndex]
      return track ? { key: queue.mediaIds[queuedTrackIndex], title: track.title } : undefined
    }
    if (!nowPlaying || nowPlaying.scope === 'radio') return undefined
    const episode = catalog.flatMap((source) => source.episodes).find((item) => item.id === nowPlaying.id)
    const tracks = episode?.tracks ?? []
    if (!episode || !tracks.length) return undefined
    const trackIndex = tracks.reduce((index, track, candidate) => globalPosition >= track.time ? candidate : index, 0)
    const track = tracks[trackIndex]
    return track ? { key: favoriteTrackKey(episode.id, track, trackIndex), title: track.title } : undefined
  })()
  const playerFavoriteActive = Boolean(playerFavorite && favoriteTrackIds.includes(playerFavorite.key))
  const playbackHistoryById = new Map(history.map((item) => [item.id, item]))
  const playbackStatusFor = (episode: Episode) => {
    // « Lu » is a durable historical fact. Starting a second play or selecting
    // one of its tracks may create a new resume point, but must never turn the
    // episode back into an unread one.
    if (completedEpisodeIds.includes(episode.id)) {
      return { kind: 'done', label: 'Lu', percent: 100 }
    }
    const isCurrentEpisode = nowPlaying?.id === episode.id && (activeMediaId === episode.id || trackQueueRef.current?.episodeId === episode.id)
    const currentQueue = trackQueueRef.current?.episodeId === episode.id ? trackQueueRef.current : undefined
    const currentTrackIndex = currentQueue ? currentQueue.mediaIds.indexOf(activeMediaId) : -1
    const liveQueuePosition = currentTrackIndex >= 0
      ? currentQueue!.tracks[currentTrackIndex].time + globalPosition
      : 0
    const duplicateEpisodeId = (selectedSource?.episodes.filter((candidate) => candidate.id === episode.id).length ?? 0) > 1
    const historyItem = duplicateEpisodeId
      ? history.find((item) => item.id === episode.id && (item.url === episode.audioUrl || item.title === episode.title))
      : playbackHistoryById.get(episode.id)
    const position = currentQueue
      ? Math.max(historyItem?.position ?? 0, liveQueuePosition)
      : isCurrentEpisode ? globalPosition : historyItem?.position ?? 0
    // A queued track reports its own duration (for example 3:48), not the
    // episode duration. Keep the RSS/history duration so episode progress can
    // still be calculated while navigating between tracks.
    const knownEpisodeDuration = Math.max(
      parseDuration(episode.duration),
      historyItem?.duration ?? 0,
    )
    const liveEpisodeDuration = currentQueue
      ? knownEpisodeDuration
      : isCurrentEpisode ? globalDuration : 0
    const status = episodePlaybackStatus(episode, position, liveEpisodeDuration)
    return status
  }
  const resumeEpisodes = history.flatMap((item) => {
    const source = catalog.find((candidate) => candidate.episodes.some((episode) => episode.id === item.id))
    const episode = source?.episodes.find((candidate) => candidate.id === item.id)
    if (!source || !episode || !['podcast', 'show', 'dj'].includes(source.kind)) return []
    const isCurrentWholeEpisode = nowPlaying?.id === item.id && nowPlaying.scope === 'episode'
    const position = isCurrentWholeEpisode ? Math.max(item.position, globalPosition) : item.position
    const episodeDuration = Math.max(parseDuration(episode.duration), item.duration ?? 0)
    if (episodeDuration > 0 && position / episodeDuration >= 0.98) return []
    return [{
      item: {
        ...item,
        title: episode.title,
        artist: source.title,
        url: episode.audioUrl,
        position,
      },
      source,
      episode,
    }]
  }).slice(0, 5)

  function openResumeEpisode(source: CatalogSource, episode: Episode) {
    setSelectedSourceId(source.id)
    setSelectedEpisodeId(episode.id)
    setActiveView('home')
  }

  function handleMiniPlayerTap() {
    if (!nowPlaying) return
    const source = catalog.find((item) => item.episodes.some((episode) => episode.id === nowPlaying.id))
    const episode = source?.episodes.find((item) => item.id === nowPlaying.id)
    // Le geste vers le haut conserve l'accès direct au grand lecteur. Un tap,
    // lui, ramène d'abord au contexte de l'épisode afin de ne pas perdre le
    // fil lorsqu'on navigue ailleurs dans Podmix.
    if (!source || !episode) {
      setFullPlayerOpen(true)
      return
    }
    const alreadyOnEpisode = selectedSourceId === source.id && selectedEpisodeId === episode.id
    if (alreadyOnEpisode) {
      setFullPlayerOpen(true)
      return
    }
    setFullPlayerOpen(false)
    openResumeEpisode(source, episode)
  }
  const podcasts = catalog.filter((source) => source.kind === 'podcast')
  const shows = catalog.filter((source) => source.kind === 'show')
  const radios = catalog.filter((source) => source.kind === 'radio')
  const djSets = catalog.filter((source) => source.kind === 'dj')
  function openAddSource() {
    setSourceMode('rss')
    setShowAddSource(true)
  }
  const normalizedQuery = globalQuery.trim().toLocaleLowerCase('fr')
  const globalResults = normalizedQuery.length < 2 ? [] : catalog.flatMap((source) => {
    const sourceText = `${source.title} ${source.description} ${source.kind}`.toLocaleLowerCase('fr')
    const sourceMatch = sourceText.includes(normalizedQuery)
    const episodes = source.episodes
      .filter((episode) => `${episode.title} ${episode.description}`.toLocaleLowerCase('fr').includes(normalizedQuery))
      .map((episode) => ({ type: 'episode' as const, source, episode }))
    return [...(sourceMatch ? [{ type: 'source' as const, source }] : []), ...episodes]
  }).slice(0, 30)
  const favoriteTrackEntries: FavoriteTrackEntry[] = catalog.flatMap((source) => source.episodes.flatMap((episode) =>
    (episode.tracks ?? []).flatMap((track, trackIndex) => {
      const key = favoriteTrackKey(episode.id, track, trackIndex)
      return favoriteTrackIds.includes(key) || favoriteTrackIds.includes(String(track.id))
        ? [{ key, source, episode, track, trackIndex }]
        : []
    }),
  )).sort((left, right) =>
    left.source.title.localeCompare(right.source.title, 'fr')
    || left.episode.title.localeCompare(right.episode.title, 'fr')
    || left.trackIndex - right.trackIndex,
  )
  const studioTrackIndex = studioEpisode ? tracks.findIndex((track) => track.id === selected.id) : -1
  const studioSource = studioEpisode ? catalog.find((source) => source.id === studioEpisode.sourceId) : undefined
  const studioCatalogEpisode = studioSource?.episodes.find((episode) => episode.id === studioEpisode?.episodeId)
  const studioArtwork = studioSource && studioCatalogEpisode
    ? trackArtwork(selected, studioCatalogEpisode, studioSource)
    : selected.artworkUrl || ''
  const studioFavoriteKey = studioEpisode && studioTrackIndex >= 0
    ? favoriteTrackKey(studioEpisode.episodeId, selected, studioTrackIndex)
    : ''
  const analyzedEpisodes = catalog.flatMap((source) => source.episodes
    .filter((episode) => episode.analysis)
    .map((episode) => ({ source, episode })))
  const activeAnalyses = analyzedEpisodes.filter(({ episode }) =>
    analysisIsActive(episode.analysis?.status))
  const completedAnalyses = analyzedEpisodes.filter(({ episode }) =>
    episode.analysis?.status === 'completed')
  const failedAnalyses = analyzedEpisodes.filter(({ episode }) =>
    episode.analysis?.status === 'failed')
  const visibleListeningSessions = searchListeningSessions(listeningSessions, historyQuery)

  function deleteListeningSession(sessionId: string) {
    setListeningSessions((sessions) => {
      const next = sessions.filter((session) => session.sessionId !== sessionId)
      saveListeningSessions(next)
      return next
    })
  }

  function clearListeningHistory() {
    setListeningSessions([])
    saveListeningSessions([])
    historyRef.current = []
    setHistory([])
  }

  function exportListeningHistory() {
    const jsonl = listeningSessions.map((session) => JSON.stringify(session)).join('\n')
    const url = URL.createObjectURL(new Blob([jsonl], { type: 'application/x-ndjson' }))
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `podmix-historique-${new Date().toISOString().slice(0, 10)}.jsonl`
    anchor.click()
    URL.revokeObjectURL(url)
  }

  function renderDjSetList(sources: CatalogSource[]) {
    return <div className="dj-set-list">
      {sources.map((source) => {
        const episode = source.episodes[0]
        const timestamp = episode ? episodeTimestampStatus(episode) : undefined
        const trackCount = episode?.tracks?.length ?? 0
        const offline = episode ? offlineEpisodes.find((item) => item.id === episode.id) : undefined
        const downloadState = offline?.status ?? 'idle'
        const downloadPercent = offline?.totalBytes && offline.totalBytes > 0
          ? Math.min(100, Math.round(((offline.bytesDownloaded ?? 0) / offline.totalBytes) * 100))
          : 0
        const canDownload = Boolean(episode?.audioUrl || episode?.sourceUrl)
        const downloadLabel = downloadState === 'completed'
          ? 'Disponible hors connexion · toucher pour supprimer'
          : downloadState === 'failed'
            ? 'Téléchargement échoué · toucher pour relancer'
            : ['downloading', 'queued', 'paused'].includes(downloadState)
              ? `Téléchargement ${downloadPercent}%`
              : 'Télécharger pour écouter hors connexion'
        const openSet = () => {
          setSelectedSourceId(source.id)
          if (episode) setSelectedEpisodeId(episode.id)
        }
        return <article className="dj-set-row" key={source.id}>
          <button className="dj-set-open" type="button" onClick={openSet} aria-label={`Ouvrir le set ${source.title}`}>
            <span className="dj-set-art"><CachedArtwork artworkUrl={source.artworkUrl} cachedArtworkUrl={cachedHomeArtwork[source.id]} title={source.title} Icon={Disc3} size={22} /></span>
            <span className="dj-set-copy"><strong className="dj-set-title-window"><span className="dj-set-title-scroll">{source.title}</span></strong><small>{episode?.duration || 'Durée inconnue'} · {trackCount ? `${trackCount} titres` : 'Tracklist à trouver'}</small></span>
            {timestamp && <span className={`dj-set-timestamp ${timestamp.tone}`}>{timestamp.shortLabel}</span>}
          </button>
          <button className={`episode-download ${downloadState}`} style={{ '--download-progress': `${downloadPercent}%` } as CSSProperties} onClick={() => { if (episode && downloadState !== 'completed') void downloadSourceEpisode(source, episode) }} disabled={!episode || !canDownload || downloadState === 'completed'} aria-label={downloadState === 'completed' ? 'Disponible hors connexion' : downloadLabel} title={downloadState === 'completed' ? 'Disponible hors connexion' : downloadLabel}>{downloadState === 'failed' ? '!' : ['downloading', 'queued', 'paused'].includes(downloadState) ? `${downloadPercent}%` : <Download size={16} />}</button>
          {downloadState === 'completed' && offline && <button className="episode-download remove-download" onClick={() => void removeOffline(offline)} aria-label="Supprimer le téléchargement" title="Supprimer le téléchargement"><Trash2 size={15} /></button>}
          <button className="dj-set-chevron" type="button" onClick={openSet} aria-label={`Ouvrir le set ${source.title}`}><ChevronRight size={18} /></button>
        </article>
      })}
    </div>
  }

  function renderCatalogSection(sectionId: HomeSectionId, title: string, Icon: typeof Mic2, sources: CatalogSource[]) {
    return <section key={sectionId} className={`home-section home-${sectionId} ${dragSection === sectionId ? 'dragging' : ''}`}>
      <div className="section-heading" onPointerDown={(e) => { if (e.pointerType === 'touch' || e.pointerType === 'pen') handleSectionDragStart(sectionId, e.clientY) }} onTouchStart={(e) => handleSectionDragStart(sectionId, e.touches[0].clientY)}><div><Icon size={17} /><h2>{title}</h2></div></div>
      {sectionId === 'djSets' ? <>
        <div className="dj-home-mini-grid" aria-label="DJ sets récents">
          {sources.slice(0, 10).map((source) => <button key={source.id} className="dj-home-mini-card" type="button" onClick={() => openCatalogSource(source, source.episodes[0]?.id)} title={source.title} aria-label={`Ouvrir ${source.title}`}>
            <CachedArtwork artworkUrl={source.artworkUrl} cachedArtworkUrl={cachedHomeArtwork[source.id]} title={source.title} Icon={Disc3} size={14} />
          </button>)}
          {!sources.length && <button className="dj-home-empty" type="button" onClick={() => setShowAddSource(true)}><Disc3 size={17} /><span>Ajouter un DJ set</span></button>}
        </div>
        <button className="dj-home-library-link" type="button" onClick={() => navigateTo('djLibrary')} aria-label={`Ouvrir les ${sources.length} DJ sets`}>
          <Disc3 size={16} /><span><strong>Tous les DJ sets</strong><small>{sources.length} disponible{sources.length > 1 ? 's' : ''}</small></span><ChevronRight size={17} />
        </button>
      </> : <div className="catalog-grid home-catalog-grid">{sources.slice(0, 4).map((source) => {
        const SourceIcon = source.kind === 'radio' ? Radio : source.kind === 'dj' ? Disc3 : source.kind === 'show' ? AudioLines : Mic2
        const color = source.kind === 'radio' ? '#9bb8dc' : source.kind === 'show' ? '#a8d7bd' : source.kind === 'dj' ? '#edcf92' : '#e99a72'
        const firstEpisode = source.episodes.find((episode) => episode.audioUrl)
        const isRadio = source.kind === 'radio'
        const openSource = () => {
          if (isRadio) return
          openCatalogSource(source)
          // Un DJ set est un épisode autonome : ouvrir immédiatement sa page
          // de set (lecteur + tracklist), comme la fiche d'un épisode.
          if (source.kind === 'dj' && source.episodes.length === 1) {
            openCatalogSource(source, source.episodes[0].id)
          }
        }
        const playSource = async () => {
          if (isRadio && source.streamUrl) {
            await playEpisode(source.id, source.title, 'Radio en direct', source.streamUrl, source.artworkUrl, 0, 'radio')
          } else if (firstEpisode) {
            await playFromSource(source, firstEpisode.id)
          } else {
            setDownloadMessage('Aucun épisode audio disponible dans cette source')
          }
        }
        const unseenEpisodeCount = (source.kind === 'podcast' || source.kind === 'show') ? source.unseenEpisodeIds?.length ?? 0 : 0
        return <article className="media-card home-source-card" key={source.id} aria-label={source.title} onClick={openSource}>
          {isRadio
            ? <div
                className="media-art radio-art"
                style={{ '--card-accent': color } as React.CSSProperties}
                role="button"
                tabIndex={0}
                aria-label={`Lire ${source.title}`}
                onClick={() => void playSource()}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault()
                    void playSource()
                  }
                }}
              ><CachedArtwork artworkUrl={source.artworkUrl} cachedArtworkUrl={cachedHomeArtwork[source.id]} title={source.title} Icon={SourceIcon} /></div>
            : <div className="media-art" style={{ '--card-accent': color } as React.CSSProperties}><CachedArtwork artworkUrl={source.artworkUrl} cachedArtworkUrl={cachedHomeArtwork[source.id]} title={source.title} Icon={SourceIcon} /></div>}
          {unseenEpisodeCount > 0 && <span className="new-episodes-badge" aria-label={`${unseenEpisodeCount} nouvel${unseenEpisodeCount > 1 ? 's' : ''} épisode${unseenEpisodeCount > 1 ? 's' : ''}`}>{unseenEpisodeCount > 99 ? '99+' : unseenEpisodeCount}</span>}
        </article>
      })}</div>}
    </section>
  }

  return (
    <div className="app-shell">
      <main>
        <header className="topbar">
          <div className="brand"><div className="brand-mark"><img src={podmixInfinityLogo} alt="" /></div><span className="brand-wordmark">PODMIX</span></div>
          <div className="top-actions">
            <button className="search" aria-label="Rechercher" onClick={() => setShowSearch(true)}><Search size={18} /></button>
            <button aria-label="Ajouter une source" onClick={() => setShowAddSource(true)}><Plus size={20} /></button>
            <button aria-label="Reprendre l’écoute" className={activeView === 'resume' ? 'active' : ''} onClick={() => navigateTo(activeView === 'resume' ? 'home' : 'resume')}><ListMusic size={19} /></button>
            <button aria-label="Voir l’historique" className={activeView === 'history' ? 'active' : ''} onClick={() => navigateTo(activeView === 'history' ? 'home' : 'history')}><Clock3 size={18} /></button>
            <button aria-label="Voir les favoris" className={activeView === 'favorites' ? 'active' : ''} onClick={() => navigateTo(activeView === 'favorites' ? 'home' : 'favorites')}><Heart size={18} fill={activeView === 'favorites' ? 'currentColor' : 'none'} /></button>
            <button aria-label="Réglages" className={activeView === 'settings' ? 'active' : ''} onClick={() => navigateTo(activeView === 'settings' ? 'home' : 'settings')}><Settings2 size={18} /></button>
          </div>
        </header>

        {activeView !== 'studio' && <section className="workspace catalog-view">
          {!selectedSource && activeView !== 'home' && activeView !== 'resume' && activeView !== 'favorites' && activeView !== 'liveSets' && activeView !== 'djLibrary' && <div className="catalog-hero">
            <span className="eyebrow"><i /> Collection personnelle</span>
            <h1>{viewLabels[activeView]}</h1>
            <p>{activeView === 'settings' ? 'Lecture, automatisation, stockage et appareils.' : activeView === 'history' ? 'Vos sessions d’écoute, conservées localement et consultables.' : 'Tous vos podcasts, émissions, radios et DJ sets au même endroit.'}</p>
          </div>}
          {activeView === 'liveSets' ? <section className="live-sets-view">
            <div className="live-sets-intro">
              <span className="eyebrow"><i /> Recherche indépendante</span>
              <h2>Trouvez des DJ live sets.</h2>
              <p>YouTube et SoundCloud sont interrogés sans créer de flux RSS, ni lancer l’analyse des podcasts.</p>
            </div>
            <form className="live-sets-search" onSubmit={(event) => void runLiveSetSearch(event)}>
              <Search size={21} />
              <input value={liveSetQuery} onChange={(event) => setLiveSetQuery(event.target.value)} placeholder="Tom Roland 2026, Daxon, Cercle…" autoFocus />
              <button type="submit" disabled={liveSetSearching || liveSetQuery.trim().length < 2}>{liveSetSearching ? <><LoaderCircle className="spinning" size={15} /> Recherche…</> : 'Rechercher'}</button>
            </form>
            {liveSetSearching && <span className="live-search-status" role="status"><LoaderCircle className="spinning" size={14} /> Recherche des DJ sets…</span>}
            <div className="live-sets-toolbar">
              <div className="live-sets-chips" aria-label="Filtrer les plateformes">
                {(['all', 'youtube', 'soundcloud'] as const).map((provider) => <button key={provider} className={liveSetProvider === provider ? 'active' : ''} onClick={() => setLiveSetProvider(provider)}>{provider === 'all' ? 'Toutes' : provider === 'youtube' ? 'YouTube' : 'SoundCloud'}</button>)}
              </div>
              <div className="live-sets-chips" aria-label="Trier les résultats">
                {(['relevance', 'recent', 'popular'] as const).map((sort) => <button key={sort} className={liveSetSort === sort ? 'active' : ''} onClick={() => setLiveSetSort(sort)}>{sort === 'relevance' ? 'Pertinence' : sort === 'recent' ? 'Récent' : 'Populaire'}</button>)}
              </div>
              {liveSetLimitControl}
            </div>
            {liveSetQuery.trim().length >= 2 && !liveSetSearching && <div className="live-set-result-count" role="status">{displayedLiveSets.length} résultat{displayedLiveSets.length > 1 ? 's' : ''} affiché{displayedLiveSets.length > 1 ? 's' : ''} · recherche réglée sur {liveSetResultLimit}</div>}
            {liveSetError && <div className="form-error">{liveSetError}</div>}
            {!liveSetSearching && liveSetQuery.trim().length >= 2 && !liveSetResults.length && !liveSetError && <div className="live-sets-empty">Aucun set public trouvé. Essayez le nom du DJ, puis une année ou « live set ».</div>}
            <div className="live-sets-grid">
              {displayedLiveSets.map((item) => {
                const saved = savedLiveSets.some((candidate) => candidate.id === item.id)
                return <article className="live-set-card" key={item.id}>
                  <div className="live-set-art"><CachedArtwork artworkUrl={item.artworkUrl} title={item.title} Icon={Disc3} size={30} /><span className={item.provider}>{item.provider === 'youtube' ? 'YT' : 'SC'}</span></div>
                  <div className="live-set-copy"><h3>{item.title}</h3><p>{item.channel || 'Artiste non précisé'}</p><small>{item.duration ? formatTime(item.duration) : 'Durée inconnue'}{item.viewCount ? ` · ${new Intl.NumberFormat('fr-FR', { notation: 'compact' }).format(item.viewCount)} vues` : ''}</small></div>
                  <div className="live-set-actions"><a href={item.url} target="_blank" rel="noreferrer" aria-label={`Ouvrir ${item.title}`}><ExternalLink size={15} /></a><button className={saved ? 'saved' : ''} onClick={() => saved ? removeLiveSet(item.id) : saveLiveSet(item)}>{saved ? <Check size={15} /> : <Plus size={15} />}{saved ? 'Gardé' : 'Garder'}</button></div>
                </article>
              })}
            </div>
            {savedLiveSets.length > 0 && <section className="saved-live-sets">
              <div className="section-heading"><div><Disc3 size={17} /><h2>Ma sélection DJ</h2></div><span>{savedLiveSets.length} set{savedLiveSets.length > 1 ? 's' : ''}</span></div>
              {savedLiveSets.map((item) => <div className={`saved-live-set ${activeLiveSetId === item.id ? 'active' : ''}`} key={item.id}><button className="saved-live-set-open" onClick={() => setActiveLiveSetId(item.id)}><span>{item.provider === 'youtube' ? 'YouTube' : 'SoundCloud'}</span><strong>{item.title}</strong></button><button className="saved-live-set-play" onClick={() => void playLiveSet(item)} disabled={liveSetBusyId === item.id} aria-label={`Lire ${item.title}`}>{liveSetBusyId === item.id ? <LoaderCircle className="spinning" size={14} /> : <Play size={14} fill="currentColor" />}</button><a href={item.url} target="_blank" rel="noreferrer"><ExternalLink size={14} /></a><button onClick={() => removeLiveSet(item.id)} aria-label={`Retirer ${item.title}`}><Trash2 size={14} /></button></div>)}
              {savedLiveSets.filter((item) => item.id === activeLiveSetId).map((item) => <div className="live-set-detail" key={`${item.id}:detail`}>
                <div className="live-set-detail-head"><div><span>Tracklist DJ · {item.tracklistOrigin || 'non recherchée'}</span><h3>{item.title}</h3></div><button onClick={() => void loadLiveSetTracklist(item)} disabled={liveSetBusyId === item.id}>{liveSetBusyId === item.id ? 'Recherche…' : item.tracks?.length ? 'Actualiser' : 'Trouver la tracklist'}</button></div>
                {item.tracks?.length ? <div className="live-set-tracks">{item.tracks.map((track, index) => <button key={`${track.id}:${index}`} disabled={track.time === null} onClick={() => void playLiveSet(item, track.time ?? 0)}><time>{track.time === null ? '—' : formatTime(track.time)}</time><strong>{track.title}<small>{track.artist}</small></strong><i>{track.timestampStatus === 'provided' ? 'Repère source' : 'Sans repère'}</i></button>)}</div> : <div className="live-set-manual"><p>La recherche examine séparément la description, les chapitres et les commentaires, puis les sources DJ externes. Rien n’est envoyé aux jobs podcasts.</p><textarea value={liveSetTracklistText} onChange={(event) => setLiveSetTracklistText(event.target.value)} placeholder={'00:00 Artiste — Titre\n04:32 Artiste — Titre'} /><button onClick={() => void loadLiveSetTracklist(item, liveSetTracklistText)} disabled={!liveSetTracklistText.trim() || liveSetBusyId === item.id}>Importer la tracklist collée</button></div>}
              </div>)}
            </section>}
          </section> : activeView === 'settings' ? <div className="settings-grid">
            <section><h2>Lecture</h2><label><span>Lecture continue<small>Enchaîner automatiquement les épisodes</small></span><input type="checkbox" checked={settings.continuousPlayback} onChange={(event) => changeSetting('continuousPlayback', event.target.checked)} /></label><label><span>Qualité mobile<small>Réduire la consommation de données</small></span><input type="checkbox" checked={settings.mobileQuality} onChange={(event) => changeSetting('mobileQuality', event.target.checked)} /></label></section>
            <section><h2>Détection</h2><label><span>Analyse automatique<small>Rechercher une tracklist après import</small></span><input type="checkbox" checked={settings.automaticAnalysis} onChange={(event) => changeSetting('automaticAnalysis', event.target.checked)} /></label><label><span>Validation MusicBrainz<small>Confirmer artiste et titre</small></span><input type="checkbox" checked={settings.musicBrainzValidation} onChange={(event) => changeSetting('musicBrainzValidation', event.target.checked)} /></label></section>
            <section><h2>Limites du catalogue</h2><label className="limit-setting"><span>Podcasts<small>{settings.maxPodcastEpisodes} épisodes par source</small></span><input type="range" min="10" max="500" step="10" value={settings.maxPodcastEpisodes} onChange={(event) => changeSetting('maxPodcastEpisodes', Number(event.target.value))} /></label><label className="limit-setting"><span>Émissions<small>{settings.maxShowEpisodes} épisodes par source</small></span><input type="range" min="10" max="500" step="10" value={settings.maxShowEpisodes} onChange={(event) => changeSetting('maxShowEpisodes', Number(event.target.value))} /></label><label className="limit-setting"><span>DJ sets<small>{settings.maxDjEpisodes} sets par DJ</small></span><input type="range" min="10" max="200" step="10" value={settings.maxDjEpisodes} onChange={(event) => changeSetting('maxDjEpisodes', Number(event.target.value))} /></label></section>
            <section><h2>Appareils</h2><button className="device-row" onClick={openOutputPicker}><Cast size={18} /><span>Sortie audio<small>{activeOutput.name}</small></span><ChevronDown size={16} /></button></section>
            <section><h2>Données</h2><p className="settings-copy">Exportez une sauvegarde ou importez le JSON produit par le convertisseur Room.</p><div className="backup-actions"><button onClick={exportBackup}><Download size={15} /> Exporter</button><button onClick={() => backupInputRef.current?.click()}><CloudUpload size={15} /> Importer</button><input ref={backupInputRef} type="file" accept="application/json,.json" onChange={importBackup} hidden /></div>{backupMessage && <small className="backup-message">{backupMessage}</small>}</section>
            <section><h2>Serveur Podmix</h2><p className="settings-copy">Adresse du moteur de détection et des annuaires. Sur un téléphone, utilisez l’adresse HTTPS de votre serveur ou son IP locale en debug.</p><div className="server-settings"><input type="url" value={serverUrl} onChange={(event) => setServerUrlState(event.target.value)} placeholder="https://podmix.example.com" /><button onClick={saveAndTestServer}>Enregistrer et tester</button></div>{serverMessage && <small className="backup-message">{serverMessage}</small>}</section>
            <section><h2>Mises à jour</h2><p className="settings-copy">Version installée : <strong>{appInfo.versionName}</strong><small>Code de version : {appInfo.versionCode || '—'}</small><small>Dernière mise à jour : {formatUpdateDate(appInfo.lastUpdateTime)}</small></p><p className="settings-copy">{updateMessage}</p>{availableUpdate && (updateProgress ? (
              <div className="device-row" style={{ opacity: 0.7 }}>
                <Download size={18} />
                <span style={{ flex: 1 }}>
                  {updateProgress.status === 'installing' ? 'Installation…' : 'Téléchargement…'}
                  <small>{updateProgress.status === 'downloading' ? `${Math.round(updateProgress.progress * 100)}%` : updateProgress.status}</small>
                  <div style={{ width: '100%', height: 4, background: '#ddd', borderRadius: 2, marginTop: 6, overflow: 'hidden' }}>
                    <div style={{ width: `${updateProgress.progress * 100}%`, height: '100%', background: '#667eea', borderRadius: 2, transition: 'width 0.3s' }} />
                  </div>
                </span>
              </div>
            ) : (
              <button className="device-row" onClick={() => { downloadAndUpdate(availableUpdate, setUpdateProgress).catch((err) => { setUpdateMessage(`Erreur : ${err.message}`); setUpdateProgress(null) }) }}><Download size={18} /><span>Installer la version {availableUpdate.versionName}<small>{availableUpdate.notes.join(' · ')}</small></span><ExternalLink size={16} /></button>
            ))}</section>
          </div> : activeView === 'resume' ? <section className="resume-library">
            <div className="section-heading"><div><ListMusic size={18} /><h2>Reprendre l’écoute</h2></div><span>{resumeEpisodes.length} épisode{resumeEpisodes.length > 1 ? 's' : ''}</span></div>
            {resumeEpisodes.length ? <div className="episode-list">{resumeEpisodes.map(({ item, source, episode }) => {
              const pct = (item.duration ?? 0) > 0 ? Math.min(100, Math.round((item.position / (item.duration ?? 1)) * 100)) : 0
              const bars = Math.min(5, Math.max(0, Math.round(pct / 20)))
              return <article className={`episode-item resume-episode ${nowPlaying?.id === item.id ? 'playing' : ''}`} key={item.id}>
                <button className="episode-play" aria-label={`${globalPlaying && nowPlaying?.id === item.id && nowPlaying.scope === 'episode' ? 'Mettre en pause' : 'Reprendre'} ${item.title}`} onClick={() => playEpisode(item.id, item.title, item.artist, item.url, episode.artworkUrl || source.artworkUrl, item.position)}>{globalPlaying && nowPlaying?.id === item.id && nowPlaying.scope === 'episode' ? <Pause size={16} /> : <Play size={16} fill="currentColor" />}</button>
                <button className="episode-info" aria-label={`Ouvrir ${item.title}`} onClick={() => openResumeEpisode(source, episode)}><div className="resume-info-row"><h3>{item.title}</h3><div className="resume-bars">{[0,1,2,3,4].map((i) => <span key={i} className={`read-bar ${i < bars ? 'filled' : ''}`} />)}</div></div><p>{source.title} · repris à {formatTime(item.position)}</p></button>
                <ChevronRight size={16} />
              </article>
            })}</div> : <div className="empty-state catalog-empty"><ListMusic size={28} /><strong>Rien à reprendre</strong><span>Les épisodes commencés apparaîtront ici.</span></div>}
          </section> : activeView === 'favorites' ? <section className="favorites-view">
            {favoriteTrackEntries.length > 0 && <section className="recent-list favorite-tracks">
              <div className="section-heading"><div><Heart size={17} fill="currentColor" /><h2>Morceaux favoris</h2></div><div className="favorite-heading-actions"><button className="favorite-links-refresh" onClick={() => { linkLookupAttempted.current.clear(); setLinkLookupRefresh((value) => value + 1) }} aria-label="Rafraîchir les liens Spotify" title="Rafraîchir les liens Spotify et Deezer"><RotateCcw size={14} /></button><button className="play-all-favorites" onClick={() => void playFavoriteTracks(0)}><Play size={14} fill="currentColor" /> Tout lire</button><span>{favoriteTrackEntries.length}</span></div></div>
              <div className="episode-list favorite-simple-list">{favoriteTrackEntries.map((entry, index) => <article className={`episode-item favorite-track-item ${activeMediaId === entry.key ? 'playing' : ''}`} key={entry.key}>
                <button className="episode-play track-cover-button" onClick={() => void playFavoriteTracks(index)} aria-label={`Lire ${entry.track.title}`}><CachedArtwork artworkUrl={trackArtwork(entry.track, entry.episode, entry.source)} title={entry.track.title} Icon={Mic2} size={16} /><Play className="track-cover-play" size={14} fill="currentColor" /></button>
                <button className="favorite-track-copy" onClick={() => void playFavoriteTracks(index)} aria-label={`Lire ${entry.track.title}`}><h3>{entry.track.title}</h3><p>{entry.track.artist}</p></button>
                <div className="favorite-track-actions">{favoriteServiceControl('deezer', entry.track.deezerUrl, entry.track.artist, entry.track.title)}{favoriteServiceControl('spotify', entry.track.spotifyUrl, entry.track.artist, entry.track.title)}<button className="favorite-share" onClick={() => void shareFavoriteTrack(entry)} disabled={sharingFavoriteKey === entry.key} aria-label={`Partager ${entry.track.title}`} title="Partager ce passage">{sharingFavoriteKey === entry.key ? <LoaderCircle className="spinning" size={15} /> : <Share2 size={15} />}</button><button className="episode-download" onClick={() => toggleTrackFavorite(entry.key)} aria-label={`Retirer ${entry.track.title} des favoris`} title="Retirer des favoris"><Heart size={16} fill="currentColor" /></button></div>
              </article>)}</div>
            </section>}
            {!favoriteTrackEntries.length && <div className="empty-state catalog-empty"><Heart size={28} /><strong>Aucun favori</strong><span>Ajoutez des morceaux à vos favoris pour les retrouver ici.</span></div>}
          </section> : activeView === 'history' ? <section className="listening-history">
            <div className="history-toolbar">
              <label><Search size={15} /><input aria-label="Rechercher dans l’historique" value={historyQuery} onChange={(event) => setHistoryQuery(event.target.value)} placeholder="Titre ou podcast" /></label>
              <span>{visibleListeningSessions.length} session{visibleListeningSessions.length > 1 ? 's' : ''}</span>
              <button onClick={exportListeningHistory} disabled={!listeningSessions.length}><Download size={14} /> Exporter JSONL</button>
              <button className="danger" onClick={clearListeningHistory} disabled={!listeningSessions.length}><Trash2 size={14} /> Tout effacer</button>
            </div>
            {visibleListeningSessions.length ? <div className="history-list">{visibleListeningSessions.map((session) => <article className="history-item" key={session.sessionId}>
              <button className="episode-play" aria-label={`Reprendre ${session.title}`} onClick={() => void playEpisode(session.id, session.title, session.artist, session.url, undefined, session.position)}><Play size={15} fill="currentColor" /></button>
              <div><h3>{session.title}</h3><p>{session.artist || 'Source inconnue'} · repris à {formatTime(session.position)}</p><time>{new Intl.DateTimeFormat('fr-FR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(session.updatedAt))}</time></div>
              <button className="history-delete" aria-label={`Supprimer ${session.title} de l’historique`} onClick={() => deleteListeningSession(session.sessionId)}><Trash2 size={15} /></button>
            </article>)}</div> : <div className="empty-state catalog-empty"><Clock3 size={28} /><strong>Aucune écoute trouvée</strong><span>{historyQuery ? 'Modifiez votre recherche.' : 'Les prochaines lectures apparaîtront ici.'}</span></div>}
          </section> : <>
            {activeView === 'djLibrary' && !selectedSource && <section className="dj-library-view">
              <button className="back-button" onClick={() => navigateTo('home')}>← Retour à l’accueil</button>
              <div className="dj-library-heading"><span className="eyebrow"><i /> Bibliothèque</span><h1>DJ sets</h1><p>{djSets.length} set{djSets.length > 1 ? 's' : ''} enregistrés</p></div>
              {renderDjSetList(djSets)}
            </section>}
            {activeView === 'home' && !selectedSource && homeSectionOrder.map((sectionId) => {
              if (sectionId === 'resume' && resumeEpisodes.length > 0) {
                return <section key="resume" className={`recent-list home-section home-resume ${resumeExpanded ? 'expanded' : 'collapsed'} ${dragSection === 'resume' ? 'dragging' : ''}`}>
                  <div className="section-heading" onPointerDown={(e) => { if (e.pointerType === 'touch' || e.pointerType === 'pen') handleSectionDragStart('resume', e.clientY) }} onTouchStart={(e) => handleSectionDragStart('resume', e.touches[0].clientY)}><button className="resume-toggle" type="button" aria-expanded={resumeExpanded} onPointerDown={(event) => event.stopPropagation()} onTouchStart={(event) => event.stopPropagation()} onClick={() => setResumeExpanded((expanded) => !expanded)}><Clock3 size={17} /><h2>Reprendre l’écoute</h2></button><div className="section-heading-actions"><button className="resume-expand-icon" type="button" aria-label={resumeExpanded ? 'Replier Reprendre l’écoute' : 'Déplier Reprendre l’écoute'} onPointerDown={(event) => event.stopPropagation()} onTouchStart={(event) => event.stopPropagation()} onClick={() => setResumeExpanded((expanded) => !expanded)}><ChevronDown size={18} /></button></div></div>
                  {resumeExpanded && <div className="episode-list">{resumeEpisodes.map(({ item, source, episode }) => {
                    const pct = (item.duration ?? 0) > 0 ? Math.min(100, Math.round((item.position / (item.duration ?? 1)) * 100)) : 0
                    const bars = Math.min(5, Math.max(0, Math.round(pct / 20)))
                    return <article className={`episode-item resume-episode ${nowPlaying?.id === item.id ? 'playing' : ''}`} key={item.id}>
                      <button className="episode-play" aria-label={`${globalPlaying && nowPlaying?.id === item.id && nowPlaying.scope === 'episode' ? 'Mettre en pause' : 'Reprendre'} ${item.title}`} onClick={() => playEpisode(item.id, item.title, item.artist, item.url, episode.artworkUrl || source.artworkUrl, item.position)}>{globalPlaying && nowPlaying?.id === item.id && nowPlaying.scope === 'episode' ? <Pause size={16} /> : <Play size={16} fill="currentColor" />}</button>
                      <button className="episode-info" aria-label={`Ouvrir ${item.title}`} onClick={() => openResumeEpisode(source, episode)}><div className="resume-info-row"><h3>{item.title}</h3><div className="resume-bars">{[0,1,2,3,4].map((i) => <span key={i} className={`read-bar ${i < bars ? 'filled' : ''}`} />)}</div></div></button>
                      <ChevronRight size={16} />
                    </article>
                  })}</div>}
                </section>
              }
              if (sectionId === 'podcasts' && podcasts.length > 0) return renderCatalogSection('podcasts', 'Podcasts', Mic2, podcasts)
              if (sectionId === 'shows' && shows.length > 0) return renderCatalogSection('shows', 'Émissions', AudioLines, shows)
              if (sectionId === 'radios' && radios.length > 0) return renderCatalogSection('radios', 'Radios', Radio, radios)
              if (sectionId === 'djSets' && djSets.length > 0) return renderCatalogSection('djSets', 'DJ sets', Disc3, djSets)
              if (sectionId === 'offline' && offlineEpisodes.length > 0) {
                return <section key="offline" className={`home-section home-offline offline-home ${offlineExpanded ? 'expanded' : 'collapsed'} ${dragSection === 'offline' ? 'dragging' : ''}`}>
                  <div className="section-heading" onPointerDown={(e) => { if (e.pointerType === 'touch' || e.pointerType === 'pen') handleSectionDragStart('offline', e.clientY) }} onTouchStart={(e) => handleSectionDragStart('offline', e.touches[0].clientY)}><button className="resume-toggle" type="button" aria-expanded={offlineExpanded} onPointerDown={(event) => event.stopPropagation()} onTouchStart={(event) => event.stopPropagation()} onClick={() => setOfflineExpanded((expanded) => !expanded)}><Download size={17} /><h2>Hors connexion</h2></button><div className="section-heading-actions"><button className="resume-expand-icon" type="button" aria-label={offlineExpanded ? 'Replier Hors connexion' : 'Déplier Hors connexion'} onPointerDown={(event) => event.stopPropagation()} onTouchStart={(event) => event.stopPropagation()} onClick={() => setOfflineExpanded((expanded) => !expanded)}><ChevronDown size={18} /></button></div></div>
                  {offlineExpanded && <div className="episode-list">
                    {offlineEpisodes.map((episode) => {
                      const progressValue = episode.totalBytes && episode.totalBytes > 0 ? Math.round(((episode.bytesDownloaded ?? 0) / episode.totalBytes) * 100) : 0
                      return <article className={`episode-item offline-item ${nowPlaying?.id === episode.id ? 'playing' : ''}`} key={episode.id}>
                        <button className="episode-play" onClick={() => playOffline(episode)} disabled={episode.status !== 'completed'}>
                          {nowPlaying?.id === episode.id && globalPlaying ? <Pause size={16} /> : <Play size={16} fill="currentColor" />}
                        </button>
                        <div><h3>{episode.title}</h3><p>{episode.artist}</p><span className={`offline-download-status ${episode.status}`}>{episode.status === 'completed' ? <><Check size={12} /> Disponible{episode.bytesDownloaded ? ` · ${(episode.bytesDownloaded / 1_048_576).toFixed(1)} Mo` : ''}</> : episode.status === 'failed' ? <>! Téléchargement échoué</> : <><LoaderCircle className="spinning" size={12} /> Téléchargement {progressValue}%</>}</span>{episode.status !== 'completed' && episode.status !== 'failed' && <div className="download-progress"><i style={{ width: `${progressValue}%` }} /></div>}</div>
                        <button className="episode-download remove-download" onClick={() => removeOffline(episode)} aria-label="Supprimer"><Trash2 size={16} /></button>
                      </article>
                    })}
                  </div>}
                </section>
              }
              return null
            })}
            {activeView === 'home' && !selectedSource && !podcasts.length && !shows.length && !radios.length && !djSets.length && <div className="empty-state catalog-empty"><Library size={28} /><strong>Votre bibliothèque est vide</strong><span>Ajoutez un podcast, une émission, une radio ou un DJ set pour commencer.</span><button onClick={openAddSource}><Plus size={15} /> Ajouter une source</button></div>}
            {selectedSource && <section className={`source-detail ${selectedSource.kind === 'show' ? 'show-source' : ''}`}>
              <button className="back-button" onClick={() => {
                if (selectedSource.kind === 'dj') {
                  setSelectedEpisodeId('')
                  setSelectedSourceId('')
                  setActiveView('djLibrary')
                  return
                }
                if (selectedEpisode) setSelectedEpisodeId('')
                else setSelectedSourceId('')
              }}>← Retour {selectedSource.kind === 'dj' ? 'aux DJ sets' : selectedEpisode ? `à ${selectedSource.title}` : 'à l’accueil'}</button>
              {selectedEpisode ? <div className="episode-detail">
                <div className="source-heading"><CachedArtwork artworkUrl={selectedEpisode.artworkUrl || selectedSource.artworkUrl} cachedArtworkUrl={cachedHomeArtwork[selectedSource.id]} title={selectedEpisode.title} Icon={AudioLines} size={38} /><div><span>{selectedSource.title}</span><h2>{selectedEpisode.title}</h2>{selectedSource.kind === 'show' && selectedEpisode.description && <p className="episode-description">{descriptionToText(selectedEpisode.description)}</p>}{selectedSource.kind !== 'show' && <span className={`episode-timestamp-status ${selectedEpisodeTimestampStatus?.tone ?? 'idle'}`}>Origine des repères · {selectedEpisodeTimestampStatus?.label ?? 'À vérifier'}</span>}<div className="source-actions"><button onClick={() => playFromSource(selectedSource, selectedEpisode.id)} disabled={!selectedEpisode.audioUrl && !(selectedSource.kind === 'dj' && selectedEpisode.sourceUrl)}>{nowPlaying?.id === selectedEpisode.id && nowPlaying.scope === 'episode' && globalPlaying ? <Pause size={14} /> : <Play size={14} fill="currentColor" />} {selectedEpisodeResumePosition > 1 ? `Reprendre · ${formatTime(selectedEpisodeResumePosition)}` : 'Lire depuis le début'}</button>{selectedEpisodeResumePosition > 1 && <button onClick={() => playFromSource(selectedSource, selectedEpisode.id, 0)} disabled={!selectedEpisode.audioUrl && !(selectedSource.kind === 'dj' && selectedEpisode.sourceUrl)}><RotateCcw size={14} /> Depuis le début</button>}{selectedSource.kind !== 'show' && <button onClick={() => void (selectedSource.kind === 'dj' ? refreshLiveSetEpisode(selectedSource, selectedEpisode) : refreshEpisodeAnalysis(selectedSource, selectedEpisode))} disabled={selectedSource.kind === 'dj' && liveSetBusyId === selectedEpisode.id}><RotateCcw size={14} /> {selectedSource.kind === 'dj' && liveSetBusyId === selectedEpisode.id ? 'Actualisation…' : 'Actualiser'}</button>}<button className={selectedEpisodeIsOffline ? 'offline-ready' : ''} onClick={() => selectedEpisodeIsOffline && selectedEpisodeOffline ? void removeOffline(selectedEpisodeOffline) : void downloadSourceEpisode(selectedSource, selectedEpisode)} disabled={!selectedEpisode.audioUrl && !(selectedSource.kind === 'dj' && selectedEpisode.sourceUrl)}><Download size={14} /> {selectedEpisodeIsOffline ? 'Disponible' : 'Hors connexion'}</button>{selectedSource.kind === 'dj' && selectedEpisode.sourceUrl && /(?:youtube\.com|youtu\.be)/i.test(selectedEpisode.sourceUrl) && <a className="source-youtube-link" href={selectedEpisode.sourceUrl} target="_blank" rel="noreferrer" aria-label="Ouvrir ce DJ set sur YouTube" title="Ouvrir sur YouTube"><Youtube size={16} /> YouTube</a>}</div></div></div>
                {selectedSource.kind === 'podcast' && <section className={`episode-analysis-card ${selectedEpisodeTimestampStatus?.tone ?? 'idle'}`}>
                  <span className="analysis-card-icon"><WandSparkles size={20} /></span>
                  <div>
                    <small>Analyse de la tracklist</small>
                    <strong>{selectedEpisode.analysis ? analysisLabel(selectedEpisode) : 'Pas encore lancée'}</strong>
                    <p>{selectedEpisode.analysis?.status === 'completed'
                      ? `${selectedEpisode.tracks?.length ?? 0} morceau${(selectedEpisode.tracks?.length ?? 0) > 1 ? 'x' : ''} identifié${(selectedEpisode.tracks?.length ?? 0) > 1 ? 's' : ''}. Vous pouvez vérifier ou corriger les repères.`
                      : selectedEpisode.analysis?.status === 'failed'
                        ? selectedEpisode.analysis.error || 'Le traitement a échoué. Vous pouvez le relancer.'
                        : selectedEpisode.analysis
                          ? 'Le VPS continue le traitement même si vous fermez l’application.'
                          : 'Podmix cherchera la tracklist et calculera ses repères sur le VPS.'}</p>
                    {analysisIsActive(selectedEpisode.analysis?.status) && <span className="analysis-card-progress"><i style={{ width: `${selectedEpisode.analysis?.progress ?? 0}%` }} /></span>}
                  </div>
                  <button onClick={() => void refreshEpisodeAnalysis(selectedSource, selectedEpisode)}><RotateCcw size={14} /> Actualiser</button>
                </section>}
                {selectedSource.kind !== 'show' && <div className="episode-tracklist"><div className="section-heading"><div><Disc3 size={17} /><h2>Tracklist</h2></div><span>{selectedEpisode.tracks?.length ?? 0} titres{usesEstimatedPositions(selectedEpisode.tracks) ? ' · repères estimés' : ''}</span></div>
                  {(selectedEpisode.tracks ?? []).map((track, index, tracklist) => {
                    const mediaId = favoriteTrackKey(selectedEpisode.id, track, index)
                    const isTrackQueue = tracklist.some((item, itemIndex) => activeMediaId === favoriteTrackKey(selectedEpisode.id, item, itemIndex))
                    const activeEpisodeTrackIndex = tracklist.reduce(
                      (activeIndex, item, itemIndex) => globalPosition >= item.time ? itemIndex : activeIndex,
                      0,
                    )
                    const active = (
                      activeMediaId === mediaId
                      || (!isTrackQueue && nowPlaying?.id === selectedEpisode.id && index === activeEpisodeTrackIndex)
                    )
                    const playing = globalPlaying && active
                    const favorite = favoriteTrackIds.includes(mediaId)
                    const savedPosition = historyRef.current.find((item) => item.id === selectedEpisode.id)?.position ?? 0
                    const resumeTrackIndex = tracklist.reduce(
                      (resumeIndex, item, itemIndex) => savedPosition >= item.time ? itemIndex : resumeIndex,
                      0,
                    )
                    const isResume = !active && savedPosition > 1 && index === resumeTrackIndex
                    return <div key={track.id} className={`episode-track-row ${active ? 'playing' : ''} ${isResume ? 'resume' : ''}`}>
                      <button className={`episode-track ${active ? 'playing' : ''}`} onClick={() => playTrackFromSource(selectedSource, selectedEpisode, index)}><span className="track-time"><time>{formatTime(track.time)}</time></span><strong><span>{track.title}</span><small>{track.artist}</small></strong>{playing ? <Pause size={14} fill="currentColor" /> : <Play size={14} />}</button>
                      <button className={`episode-track-favorite ${favorite ? 'active' : ''}`} onClick={() => toggleTrackFavorite(mediaId)} aria-label={`${favorite ? 'Retirer' : 'Ajouter'} ${track.title} ${favorite ? 'des' : 'aux'} favoris`} title={favorite ? 'Retirer des favoris' : 'Ajouter aux favoris'}><Heart size={15} fill={favorite ? 'currentColor' : 'none'} /></button>
                    </div>
                  })}
                  {!selectedEpisode.tracks?.length && <div className="empty-state"><AudioLines size={24} /><strong>Aucune tracklist enregistrée</strong><span>Lancez l’analyse pour rechercher les titres et calculer leurs repères.</span></div>}
                </div>}
              </div> : <>
                <div className="source-heading"><CachedArtwork artworkUrl={selectedSource.artworkUrl} cachedArtworkUrl={cachedHomeArtwork[selectedSource.id]} title={selectedSource.title} Icon={Mic2} size={38} /><div><span>{sourceKindLabel(selectedSource)}{selectedSource.musical ? ` · musicale` : ""}</span><h2>{selectedSource.title}</h2>{selectedSource.kind === "radio" && selectedSource.description && <p>{selectedSource.description}</p>}{selectedSource.kind !== "radio" && <span className="episode-count-badge">{selectedSource.episodes.length} épisodes</span>}<div className="source-actions">{selectedSource.kind !== 'radio' && <button onClick={() => void playSource(selectedSource)} disabled={!selectedSource.episodes.some((episode) => episode.audioUrl || (selectedSource.kind === 'dj' && episode.sourceUrl))}><Play size={14} fill="currentColor" /> Lire</button>}{selectedSource.feedUrl && <button onClick={refreshSelectedSource} disabled={refreshingSource}><RotateCcw size={14} /> {refreshingSource ? "Actualisation…" : "Actualiser"}</button>}<button onClick={removeSelectedSource}><Trash2 size={14} /> Supprimer</button></div></div></div>
                {selectedSource.kind === 'radio' && selectedSource.streamUrl && <button className="listen-live" onClick={() => playEpisode(selectedSource.id, selectedSource.title, 'Radio en direct', selectedSource.streamUrl!, selectedSource.artworkUrl, 0, 'radio')}>{nowPlaying?.id === selectedSource.id && globalPlaying ? <Pause size={17} /> : <Play size={17} fill="currentColor" />} Écouter en direct</button>}
                {selectedSource.kind !== 'radio' && <div className="episode-list">
                  {selectedSource.episodes.map((episode) => {
                    const isNewEpisode = selectedSource.newEpisodeIds?.includes(episode.id) ?? false
                    const canDownload = Boolean(episode.audioUrl || (selectedSource.kind === 'dj' && episode.sourceUrl))
                    const playbackStatus = playbackStatusFor(episode)
                    const timestampStatus = selectedSource.kind === 'show' ? undefined : episodeTimestampStatus(episode)
                    const offline = offlineEpisodes.find((item) => item.id === episode.id)
                    const downloadPercent = offline?.totalBytes && offline.totalBytes > 0
                      ? Math.min(100, Math.round(((offline.bytesDownloaded ?? 0) / offline.totalBytes) * 100))
                      : 0
                    const downloadState = offline?.status ?? 'idle'
                    const downloadLabel = downloadState === 'completed'
                      ? 'Disponible hors connexion · toucher pour supprimer'
                      : downloadState === 'failed'
                        ? 'Téléchargement échoué · toucher pour relancer'
                        : ['downloading', 'queued', 'paused'].includes(downloadState)
                          ? `Téléchargement ${downloadPercent}%`
                          : 'Télécharger pour écouter hors connexion'
                    const progressBars = Math.min(5, Math.max(0, Math.round(playbackStatus.percent / 20)))
                    const barClass = playbackStatus.kind === 'done' ? 'done' : 'filled'
                    return <article className={`episode-item ${nowPlaying?.id === episode.id ? 'playing' : ''} ${playbackStatus.kind === 'done' ? 'done' : ''} ${isNewEpisode ? 'is-new' : ''}`} key={episode.id}>
                      <button className="episode-info" onClick={() => openCatalogEpisode(selectedSource, episode)}>
                        <div className="episode-info-row">
                          <button className={`episode-play-inline ${nowPlaying?.id === episode.id && nowPlaying.scope === 'episode' && globalPlaying ? 'playing' : ''}`} onClick={(e) => { e.stopPropagation(); playFromSource(selectedSource, episode.id) }} disabled={!episode.audioUrl}>{nowPlaying?.id === episode.id && nowPlaying.scope === 'episode' && globalPlaying ? <Pause size={14} /> : <Play size={14} fill="currentColor" />}</button>
                          <div style={{minWidth:0}}><h3>{episode.title}</h3>{isNewEpisode && <span className="episode-new-label">Nouveau</span>}<p>{formatEpisodeDate(episode.publishedAt) || episode.description.replace(/<[^>]+>/g, '').slice(0, 130)}</p>
                          <span className={`episode-read-state ${playbackStatus.kind}`} aria-label={`État de lecture : ${playbackStatus.label}`}>
                            {[0,1,2,3,4].map((i) => <span key={i} className={`read-bar ${i < progressBars ? barClass : ''}`} />)}
                          </span>{selectedSource.kind !== 'show' && analysisIsActive(episode.analysis?.status) && <span className="episode-analysis-progress"><i style={{ width: `${episode.analysis?.progress ?? 0}%` }} /></span>}
                          </div>
                        </div>
                      </button>
                      {timestampStatus && <button className={`episode-timestamp-indicator ${timestampStatus.tone}`} onClick={(event) => { event.stopPropagation(); void openEpisodeInStudio(selectedSource, episode) }} aria-label={`Origine des repères : ${timestampStatus.label}`} title={`Origine des repères : ${timestampStatus.label}`}>{timestampStatus.shortLabel}</button>}
                      <span>{episode.duration}</span>
                      <button className={`episode-download ${downloadState}`} style={{ '--download-progress': `${downloadPercent}%` } as CSSProperties} onClick={() => { if (downloadState !== 'completed') void downloadSourceEpisode(selectedSource, episode) }} disabled={!canDownload || downloadState === 'completed'} aria-label={downloadState === 'completed' ? 'Disponible hors connexion' : downloadLabel} title={downloadState === 'completed' ? 'Disponible hors connexion' : downloadLabel}>{downloadState === 'failed' ? '!' : ['downloading', 'queued', 'paused'].includes(downloadState) ? `${downloadPercent}%` : <Download size={16} />}</button>
                      {downloadState === 'completed' && offline && <button className="episode-download remove-download" onClick={() => void removeOffline(offline)} aria-label="Supprimer le téléchargement" title="Supprimer le téléchargement"><Trash2 size={15} /></button>}
                    </article>
                  })}
                  {!selectedSource.episodes.length && <div className="empty-state">Aucun épisode audio trouvé dans ce flux.</div>}
                </div>}
              </>}
            </section>}
          </>}
        </section>}
        {activeView === 'studio' && <section className="workspace">
          {!studioEpisode && !hasLocalAudio && !tracks.length ? <div className="analysis-hub">
            <div className="analysis-hub-heading">
              <div><span className="eyebrow"><i /> Recherches et résultats</span><h1>Tracklists</h1><p>Suivez les recherches RSS, Web et IA, puis ouvrez celles qui demandent une vérification.</p></div>
              <label className="upload-button"><CloudUpload size={17} /> Ouvrir un audio local<input type="file" accept=".wav,.mp3,.flac,.ogg,.oga,.aac,.m4a,.mp4,audio/*" onChange={loadAudio} /></label>
            </div>
            <div className="analysis-summary">
              <div><strong>{activeAnalyses.length}</strong><span>En cours</span></div>
              <div><strong>{completedAnalyses.length}</strong><span>Terminées</span></div>
              <div><strong>{failedAnalyses.length}</strong><span>À revoir</span></div>
            </div>
            <section className="analysis-list">
              <div className="section-heading"><div><WandSparkles size={17} /><h2>Épisodes analysés</h2></div><span>{analyzedEpisodes.length}</span></div>
              {analyzedEpisodes.map(({ source, episode }) => <button className="analysis-list-item" key={`${source.id}:${episode.id}`} onClick={() => {
                setSelectedSourceId(source.id)
                setSelectedEpisodeId(episode.id)
                setActiveView('home')
              }}>
                <span className={`analysis-state ${episode.analysis?.status}`}><AudioLines size={17} /></span>
                <span><strong>{episode.title}</strong><small>{source.title} · {analysisLabel(episode)}</small></span>
                {analysisIsActive(episode.analysis?.status) && <span className="analysis-list-progress"><i style={{ width: `${episode.analysis?.progress ?? 0}%` }} /></span>}
                <ChevronRight size={17} />
              </button>)}
              {!analyzedEpisodes.length && <div className="empty-state"><WandSparkles size={26} /><strong>Aucune analyse pour le moment</strong><span>Ouvrez une émission ou un DJ set dans l’accueil, puis choisissez « Analyser ».</span><button onClick={() => navigateTo('home')}>Ouvrir l’accueil</button></div>}
            </section>
          </div> : <>
          <div className="project-head">
            <div><span className="eyebrow"><i /> Analyse ouverte</span><h1>{audioName}</h1><p>{studioEpisode ? 'Vérifiez les titres proposés et leurs repères avant d’enregistrer.' : 'Analyse d’un fichier audio local.'}</p></div>
            <div className="project-actions">
              <label className="upload-button"><CloudUpload size={17} /> Ouvrir un audio local<input type="file" accept=".wav,.mp3,.flac,.ogg,.oga,.aac,.m4a,.mp4,audio/*" onChange={loadAudio} /></label>
              <button className="publish" onClick={publishTracklist}><Check size={17} /> Enregistrer la tracklist</button>{publishMessage && <small className="publish-message">{publishMessage}</small>}
            </div>
          </div>

          <div className="studio-grid">
            <section className="timeline-panel">
              <div className="panel-tools">
                <div className="transport"><button className="play" onClick={() => void togglePlayback()} aria-label={isPlaying ? 'Pause' : 'Lecture'}>{isPlaying ? <Pause fill="currentColor" /> : <Play fill="currentColor" />}</button><div className="clock"><strong>{formatTime(currentTime)}</strong><span> / {formatTime(duration)}</span></div></div>
                <div className="zoom"><span>Vue</span><button>−</button><i /><button>+</button></div>
                <button className="add-marker" onClick={addMarker}><Plus size={16} /> Marqueur</button>
              </div>
              <div className="ruler">{[0, 10, 20, 30, 40, 50].map((minute) => <span key={minute}>{minute}:00</span>)}</div>
              <div className="wave-stage" onClick={(event) => {
                if (waveRef.current) return
                const rect = event.currentTarget.getBoundingClientRect()
                void seek(((event.clientX - rect.left) / rect.width) * duration)
              }}>
                {!hasLocalAudio && <div className="demo-wave" aria-hidden="true">{Array.from({ length: 155 }, (_, index) => <i key={index} style={{ height: `${16 + ((index * 29) % 74)}%` }} />)}</div>}
                <div ref={waveformRef} className="real-wave" />
                <div className="played-mask" style={{ width: `${(currentTime / duration) * 100}%` }} />
                {tracks.map((track, index) => <button key={track.id} className={`marker ${track.id === selectedId ? 'selected' : ''}`} style={{ left: `${Math.min(98, (track.time / Math.max(1, duration)) * 100)}%` }} onClick={(event) => { event.stopPropagation(); void seek(track.time, track.id) }} aria-label={`Aller à ${track.title}`}><span>{String(index + 1).padStart(2, '0')}</span></button>)}
                <div className="playhead" style={{ left: `${Math.min(100, (currentTime / duration) * 100)}%` }}><i /></div>
              </div>
              <div className="wave-caption"><span><i className="legend orange" /> Position de lecture</span><span><i className="legend yellow" /> Confiance faible</span><span className="shortcut"><Command size={13} /> clic pour naviguer</span></div>
              <div className="detection-bar">
                <div className="detection-icon"><WandSparkles size={20} /></div>
                <div><strong>{detection === 'running' ? 'Recherche des timestamps…' : detection === 'done' ? 'Recherche terminée' : detectionError || 'Timestamping assisté'}</strong><p>{detection === 'running' ? `${detectionStage} · ${progress}%` : detection === 'done' ? tracks.length ? `${tracks.length} morceau${tracks.length > 1 ? 'x' : ''} trouvé${tracks.length > 1 ? 's' : ''}${usesEstimatedPositions(tracks) ? ' · timestamps encore absents' : ''}.` : 'Aucune tracklist publiée trouvée.' : detectionError ? 'Le timestamping manuel reste disponible hors ligne.' : 'Rechercher dans le RSS, le Web et les commentaires.'}</p>{detection === 'running' && <div className="progress"><i style={{ width: `${progress}%` }} /></div>}</div>
                <button onClick={() => startDetection()} disabled={detection === 'running'}><Sparkles size={16} /> {detection === 'done' ? 'Relancer' : 'Rechercher'}</button>
                <button className="catalog-button" onClick={discoverFromEpisodeTitle} disabled={searchingSource || !currentJobId}>{searchingSource ? 'Scraping Web…' : 'Scraper le Web'}</button>
              </div>
            </section>

            <aside className="inspector">
              <div className="inspector-head"><div><span>Marqueur {String(selected.id).padStart(2, '0')}</span><strong>{formatTime(selected.time)}</strong></div><button><MoreHorizontal /></button></div>
              <div className="inspector-track-artwork"><CachedArtwork artworkUrl={studioArtwork} title={selected.title} Icon={Disc3} size={28} /><span>{selected.artworkUrl ? 'Pochette du morceau' : 'Logo du podcast'}</span></div>
              <label>Artiste<input value={selected.artist} onChange={(e) => updateSelected('artist', e.target.value)} /></label>
              <label>Titre<input value={selected.title} onChange={(e) => updateSelected('title', e.target.value)} /></label>
              <div className="inspector-tip"><Gauge size={17} /><p><strong>Preuves</strong><br />{selected.evidence?.join(' · ') ?? 'Aucune preuve de timestamp disponible.'}</p></div>
              <button className="verify-button" onClick={verifySelected} disabled={selected.verified}>{selected.verified ? <><Check size={15} /> Titre validé</> : 'Valider ce titre'}</button>
              <button className={`track-favorite-button ${studioFavoriteKey && favoriteTrackIds.includes(studioFavoriteKey) ? 'active' : ''}`} onClick={() => studioFavoriteKey && toggleTrackFavorite(studioFavoriteKey)} disabled={!studioFavoriteKey} title={!studioFavoriteKey ? 'Enregistrez ce morceau dans un épisode avant de le placer en favori' : undefined}><Heart size={15} fill={studioFavoriteKey && favoriteTrackIds.includes(studioFavoriteKey) ? 'currentColor' : 'none'} /> {studioFavoriteKey && favoriteTrackIds.includes(studioFavoriteKey) ? 'Retirer des favoris' : 'Ajouter aux favoris'}</button>
              <button className="delete-track-button" onClick={deleteSelectedTrack} disabled={!selected.id}><Trash2 size={14} /> Supprimer ce marqueur</button>
              <details className="advanced-analysis">
                <summary>Outils avancés et autres sources <ChevronDown size={15} /></summary>
                <button className="source-button">Source : {selected.source === 'manual' ? 'saisie manuelle' : 'analyse croisée'} <ChevronDown size={15} /></button>
                <button className="catalog-button" onClick={validateSelectedInCatalog} disabled={validatingCatalog || !settings.musicBrainzValidation}>{!settings.musicBrainzValidation ? 'Validation catalogue désactivée' : validatingCatalog ? 'Recherche MusicBrainz…' : selected.catalogValidated ? '✓ Présent dans MusicBrainz' : 'Vérifier dans MusicBrainz'}</button>
                {(selected.deezerUrl || selected.spotifyUrl) && <div className="catalog-links">
                  {selected.deezerUrl && <a href={selected.deezerUrl} target="_blank" rel="noreferrer">Deezer <ExternalLink size={13} /></a>}
                  {selected.spotifyUrl && <a href={selected.spotifyUrl} target="_blank" rel="noreferrer">Spotify <ExternalLink size={13} /></a>}
                </div>}
                <div className="tracklist-import">
                  <span>Rechercher une autre tracklist</span>
                  <button onClick={discoverFromEpisodeTitle} disabled={searchingSource || !currentJobId}>{searchingSource ? 'Recherche de la source…' : `Rechercher depuis « ${audioName.slice(0, 35)}${audioName.length > 35 ? '…' : ''} »`}</button>
                  <input className="source-url" type="url" value={sourceUrl} onChange={(event) => setSourceUrl(event.target.value)} placeholder="URL YouTube, SoundCloud ou Mixcloud" />
                  <button onClick={discoverFromUrl} disabled={discovering}>{discovering ? 'Exploration…' : 'Explorer les métadonnées'}</button>
                  <span className="or-label">MixesDB + 1001Tracklists</span>
                  <input className="source-url" value={tl1001Query} onChange={(event) => setTl1001Query(event.target.value)} placeholder="Titre de l’émission ou URL 1001Tracklists" />
                  <button onClick={discoverFrom1001} disabled={discovering1001}>{discovering1001 ? 'Recherche…' : 'Chercher et aligner'}</button>
                  <span className="or-label">Coller une tracklist</span>
                  <textarea value={tracklistText} onChange={(event) => setTracklistText(event.target.value)} placeholder={'00:00 Artiste — Titre\n04:12 Artiste — Titre'} />
                  <button onClick={submitTracklist} disabled={aligning}>{aligning ? 'Alignement…' : 'Analyser le texte'}</button>
                </div>
              </details>
            </aside>
          </div>

          <section className="tracklist">
            <div className="tracklist-head"><div><h2>Tracklist</h2><span>{tracks.length} morceaux · {tracks.filter((track) => track.verified).length} vérifiés{usesEstimatedPositions(tracks) ? ' · repères estimés' : ''}</span></div><div className="tracklist-actions"><button onClick={() => exportTracklist('json')}><Download size={14} /> JSON</button><button onClick={() => exportTracklist('csv')}>CSV</button><button onClick={() => exportTracklist('chapters')}>Chapitres</button><button onClick={undoTrackChange} disabled={!undoStack.current.length}><RotateCcw size={15} /> Annuler</button></div></div>
            <div className="table-head"><span>Début</span><span>Morceau</span><span>État</span></div>
            {tracks.map((track) => <button className={`track-row ${track.id === selectedId ? 'active' : ''}`} key={track.id} onClick={() => seek(track.time, track.id)}>
              <span className="track-time">{formatTime(track.time)}</span>
              <span className="track-title"><strong>{track.title}</strong><small>{track.artist}</small></span>
              <span className="status">{track.verified ? <><Check size={14} /> Vérifié</> : timestampSourceLabel(track)}</span>
            </button>)}
          </section>
          </>}
        </section>}
      </main>
      {showAddSource && <div className="modal-backdrop" onMouseDown={() => setShowAddSource(false)}>
        <section className={`source-modal ${sourceMode === 'dj' ? 'live-sets-modal' : ''}`} onMouseDown={(event) => event.stopPropagation()}>
          <button className="modal-close" onClick={() => setShowAddSource(false)}>×</button>
          <span className="eyebrow"><i /> Ajouter</span><h2>Qu’avez-vous envie d’écouter ?</h2>
          <p className="source-modal-intro">Choisissez un type, recherchez, puis ajoutez-le à votre bibliothèque.</p>
          <div className="source-mode" aria-label="Type de contenu à ajouter"><button className={sourceMode === 'rss' ? 'active' : ''} onClick={() => selectSourceMode('rss')}><Mic2 size={17} /><span>Podcast</span></button><button className={sourceMode === 'show' ? 'active' : ''} onClick={() => selectSourceMode('show')}><AudioLines size={17} /><span>Émission</span></button><button className={sourceMode === 'radio' ? 'active' : ''} onClick={() => selectSourceMode('radio')}><Radio size={17} /><span>Radio</span></button><button className={sourceMode === 'dj' ? 'active' : ''} onClick={() => selectSourceMode('dj')}><Disc3 size={17} /><span>DJ sets</span></button></div>
          {sourceMode === 'rss' || sourceMode === 'show' ? <><p>Recherchez dans l’annuaire, puis choisissez un résultat. L’import du flux est effectué par Podmix, sans blocage du navigateur.</p>
          <label className="directory-search">Rechercher {sourceMode === 'show' ? 'une émission' : 'un podcast'}<input value={podcastQuery} onChange={(event) => setPodcastQuery(event.target.value)} placeholder="Nom, auteur ou sujet…" autoFocus /></label>{searchingPodcasts && <span className="live-search-status" role="status"><LoaderCircle className="spinning" size={14} /> Recherche {sourceMode === 'show' ? 'des émissions' : 'des podcasts'}…</span>}
          {!searchingPodcasts && podcastQuery.trim().length >= 2 && !podcastResults.length && !feedError && <div className="directory-empty">Aucun podcast trouvé. Essayez le nom de l’émission ou de son auteur.</div>}
          <div className="radio-results podcast-results" aria-live="polite">{podcastResults.map((podcast) => <button key={podcast.id} onClick={() => void addPodcastResult(podcast)} disabled={addingFeed}><span className="podcast-thumb"><CachedArtwork artworkUrl={podcast.artworkUrl} title={podcast.title} Icon={Mic2} size={16} /></span><span><strong>{podcast.title}</strong><small>{podcast.artist || podcast.genre || 'Podcast'}{podcast.episodeCount ? ` · ${podcast.episodeCount} épisodes` : ''}</small></span><Plus size={15} /></button>)}</div>
          <label>Ou URL du flux RSS<input type="url" value={feedUrl} onChange={(event) => setFeedUrl(event.target.value)} placeholder="https://exemple.com/podcast.xml" /></label></> : sourceMode === 'radio' ? <><p>Recherchez une station dans l’annuaire communautaire Radio Browser.</p><label className="directory-search">Nom de la radio<input value={radioQuery} onChange={(event) => setRadioQuery(event.target.value)} placeholder="FIP, NTS, KEXP…" autoFocus /></label>{searchingRadios && <span className="live-search-status" role="status"><LoaderCircle className="spinning" size={14} /> Recherche des radios…</span>}
          <div className="radio-results">{radioResults.map((radio) => <button key={radio.id} onClick={() => addRadio(radio)}><Radio size={16} /><span><strong>{radio.title}</strong><small>{radio.description}</small></span><Plus size={15} /></button>)}</div></> : <><p>Recherche YouTube et SoundCloud, sans flux RSS ni analyse des podcasts.</p>
          <form className="live-sets-search" onSubmit={(event) => void runLiveSetSearch(event)}><Search size={21} /><input value={liveSetQuery} onChange={(event) => setLiveSetQuery(event.target.value)} placeholder="Armin Tomorrowland 2026, Daxon…" autoFocus /><button type="submit" disabled={liveSetSearching || liveSetQuery.trim().length < 2}>{liveSetSearching ? <><LoaderCircle className="spinning" size={15} /> Recherche…</> : 'Rechercher'}</button></form>
          {liveSetSearching && <span className="live-search-status" role="status"><LoaderCircle className="spinning" size={14} /> Recherche des DJ sets…</span>}
          <div className="live-sets-toolbar"><div className="live-sets-chips" aria-label="Filtrer les plateformes">{(['all', 'youtube', 'soundcloud'] as const).map((provider) => <button type="button" key={provider} className={liveSetProvider === provider ? 'active' : ''} onClick={() => setLiveSetProvider(provider)}>{provider === 'all' ? 'Toutes' : provider === 'youtube' ? 'YouTube' : 'SoundCloud'}</button>)}</div><div className="live-sets-chips" aria-label="Trier les résultats">{(['relevance', 'recent', 'popular'] as const).map((sort) => <button type="button" key={sort} className={liveSetSort === sort ? 'active' : ''} onClick={() => setLiveSetSort(sort)}>{sort === 'relevance' ? 'Pertinence' : sort === 'recent' ? 'Récent' : 'Populaire'}</button>)}</div>{liveSetLimitControl}</div>
          {liveSetQuery.trim().length >= 2 && !liveSetSearching && <div className="live-set-result-count" role="status">{displayedLiveSets.length} résultat{displayedLiveSets.length > 1 ? 's' : ''} affiché{displayedLiveSets.length > 1 ? 's' : ''} · recherche réglée sur {liveSetResultLimit}</div>}
          {!liveSetSearching && liveSetQuery.trim().length >= 2 && !liveSetResults.length && !liveSetError && <div className="live-sets-empty">Aucun set public trouvé. Essayez le nom du DJ, puis une année ou « live set ».</div>}
          {liveSetError && <div className="form-error">{liveSetError}</div>}
          <div className="live-sets-grid" aria-live="polite">{displayedLiveSets.map((item) => { const imported = catalog.some((source) => source.id === liveSetCatalogId(item)); const importing = liveSetBusyId === item.id; return <article className="live-set-card" key={item.id}><div className="live-set-art"><CachedArtwork artworkUrl={item.artworkUrl} title={item.title} Icon={Disc3} size={30} /><span className={item.provider}>{item.provider === 'youtube' ? 'YT' : 'SC'}</span></div><div className="live-set-copy"><h3>{item.title}</h3><p>{item.channel || 'Artiste non précisé'}</p><small>{item.duration ? formatTime(item.duration) : 'Durée inconnue'}{item.viewCount ? ` · ${new Intl.NumberFormat('fr-FR', { notation: 'compact' }).format(item.viewCount)} vues` : ''}</small></div><div className="live-set-actions"><a href={item.url} target="_blank" rel="noreferrer" aria-label={`Ouvrir ${item.title}`}><ExternalLink size={15} /></a><button type="button" className={imported ? 'saved' : ''} onClick={() => void saveLiveSet(item)} disabled={importing}>{importing ? <LoaderCircle className="spinning" size={15} /> : imported ? <Check size={15} /> : <Plus size={15} />}{importing ? 'Import…' : imported ? 'Réimporter' : 'Importer'}</button></div></article>})}</div>
          {savedLiveSets.length > 0 && <section className="saved-live-sets"><div className="section-heading"><div><Disc3 size={17} /><h2>Ma sélection DJ</h2></div><span>{savedLiveSets.length} set{savedLiveSets.length > 1 ? 's' : ''}</span></div>{savedLiveSets.map((item) => <div className={`saved-live-set ${activeLiveSetId === item.id ? 'active' : ''}`} key={item.id}><button className="saved-live-set-open" onClick={() => setActiveLiveSetId(item.id)}><span>{item.provider === 'youtube' ? 'YouTube' : 'SoundCloud'}</span><strong>{item.title}</strong></button><button className="saved-live-set-play" onClick={() => void playLiveSet(item)} disabled={liveSetBusyId === item.id} aria-label={`Lire ${item.title}`}>{liveSetBusyId === item.id ? <LoaderCircle className="spinning" size={14} /> : <Play size={14} fill="currentColor" />}</button><a href={item.url} target="_blank" rel="noreferrer"><ExternalLink size={14} /></a><button onClick={() => removeLiveSet(item.id)} aria-label={`Retirer ${item.title}`}><Trash2 size={14} /></button></div>)}{savedLiveSets.filter((item) => item.id === activeLiveSetId).map((item) => <div className="live-set-detail" key={`${item.id}:detail`}><div className="live-set-detail-head"><div><span>Tracklist DJ · {item.tracklistOrigin || 'non recherchée'}</span><h3>{item.title}</h3></div><button onClick={() => void loadLiveSetTracklist(item)} disabled={liveSetBusyId === item.id}>{liveSetBusyId === item.id ? 'Recherche…' : item.tracks?.length ? 'Actualiser' : 'Trouver la tracklist'}</button></div>{item.tracks?.length ? <div className="live-set-tracks">{item.tracks.map((track, index) => <button key={`${track.id}:${index}`} disabled={track.time === null} onClick={() => void playLiveSet(item, track.time ?? 0)}><span>{String(index + 1).padStart(2, '0')}</span><time>{track.time === null ? '—' : formatTime(track.time)}</time><strong>{track.title}<small>{track.artist}</small></strong><i>{track.timestampStatus === 'provided' ? 'Repère source' : 'Sans repère'}</i></button>)}</div> : <div className="live-set-manual"><p>La recherche examine séparément la description, les chapitres et les sources DJ externes. Rien n’est envoyé aux jobs podcasts.</p><textarea value={liveSetTracklistText} onChange={(event) => setLiveSetTracklistText(event.target.value)} placeholder={'00:00 Artiste — Titre\n04:32 Artiste — Titre'} /><button onClick={() => void loadLiveSetTracklist(item, liveSetTracklistText)} disabled={!liveSetTracklistText.trim() || liveSetBusyId === item.id}>Importer la tracklist collée</button></div>}</div>)}</section>}</>}
          {feedError && <div className="form-error">{feedError}</div>}
          {(sourceMode === 'rss' || sourceMode === 'show') && <button className="modal-submit" onClick={addFeed} disabled={addingFeed}>{addingFeed ? 'Import en cours…' : `Importer ${sourceMode === 'show' ? 'l’émission' : 'le podcast'}`}</button>}
        </section>
      </div>}
      {showSearch && <div className="modal-backdrop search-backdrop" onMouseDown={() => setShowSearch(false)}>
        <section className="global-search" onMouseDown={(event) => event.stopPropagation()}>
          <div className="global-search-input"><Search size={20} /><input value={globalQuery} onChange={(event) => setGlobalQuery(event.target.value)} placeholder="Podcast, épisode, radio ou DJ set…" autoFocus /><button onClick={() => setShowSearch(false)}>Échap</button></div>
          <div className="global-results">
            {globalResults.map((result) => result.type === 'source'
              ? <button key={`source:${result.source.id}`} onClick={() => { setSelectedSourceId(result.source.id); setActiveView('home'); setShowSearch(false) }}>
                <span className="result-icon">{result.source.kind === 'radio' ? <Radio size={17} /> : result.source.kind === 'dj' ? <Disc3 size={17} /> : <Mic2 size={17} />}</span><span><strong>{result.source.title}</strong><small>{result.source.description || result.source.kind}</small></span><i>Source</i>
              </button>
              : <button key={`episode:${result.episode.id}`} onClick={() => { void playFromSource(result.source, result.episode.id); setShowSearch(false) }}>
                <span className="result-icon"><Play size={16} /></span><span><strong>{result.episode.title}</strong><small>{result.source.title}</small></span><i>Épisode</i>
              </button>)}
            {normalizedQuery.length < 2 && <div className="search-hint">Saisissez au moins deux caractères pour chercher dans votre catalogue.</div>}
            {normalizedQuery.length >= 2 && !globalResults.length && <div className="search-hint">Aucun résultat local. Ajoutez d’abord une source RSS, une radio ou un DJ set.</div>}
          </div>
        </section>
      </div>}
      {showOutputPicker && <div className="modal-backdrop output-backdrop" onMouseDown={() => setShowOutputPicker(false)}>
        <section className="source-modal output-modal" aria-labelledby="output-title" onMouseDown={(event) => event.stopPropagation()}>
          <button className="modal-close" aria-label="Fermer" onClick={() => setShowOutputPicker(false)}>×</button>
          <div className="output-modal-heading">
            <span className="output-modal-icon"><Cast size={20} /></span>
            <div><h2 id="output-title">Écouter sur</h2><p>Choisissez une enceinte pour y transférer la lecture.</p></div>
            <button className="output-refresh" onClick={() => void refreshOutputDevices()} disabled={discoveringOutputs} aria-label="Actualiser les appareils"><RotateCcw size={16} /></button>
          </div>
          <div className="output-device-list">
            {outputDevices.map((device) => {
              const id = device.kind === 'bose' ? device.ip : device.id
              const isActive = activeOutput.kind === device.kind && (device.kind === 'phone' || activeOutput.id === id)
              return <button className={`output-device ${isActive ? 'active' : ''}`} key={`${device.kind}:${id}`} onClick={() => void selectOutputDevice(device)} disabled={Boolean(connectingOutputId)}>
                <span className="output-device-icon">{device.kind === 'cast' ? <Cast size={19} /> : <Speaker size={19} />}</span>
                <span><strong>{device.name}</strong><small>{device.kind === 'cast' ? `${device.deviceType || 'Google Cast'} · disponible` : device.kind === 'bose' ? `${device.type || 'SoundTouch'} · en ligne` : device.description}</small></span>
                {connectingOutputId === id ? <RotateCcw className="spinning" size={17} /> : isActive ? <Check size={17} /> : <ChevronRight size={17} />}
              </button>
            })}
            {discoveringOutputs && <div className="output-searching"><RotateCcw className="spinning" size={16} /> Recherche des enceintes en ligne…</div>}
            {!discoveringOutputs && outputDevices.length <= 1 && <div className="output-empty">Aucune enceinte détectée sur ce réseau.</div>}
          </div>
          {!discoveringOutputs && <button className="output-system-picker" onClick={() => void chooseCastDevice()}><Wifi size={16} /> Ouvrir le sélecteur Google Cast</button>}
          {!discoveringOutputs && !outputDevices.some((device) => device.kind === 'bose') && <div className="output-manual-bose">
            <label>Adresse IP de la Bose<input value={boseIp} onChange={(event) => setBoseIp(event.target.value.replace(/[^0-9.]/g, ''))} inputMode="decimal" placeholder="192.168.1.50" /></label>
            <button onClick={() => { const device = manualBoseDevice(); if (device) void selectOutputDevice(device); else setBoseMessage('Saisissez l’adresse IP locale de la Bose') }} disabled={Boolean(connectingOutputId)}>Tester et connecter</button>
          </div>}
          {activeOutput.kind === 'bose' && <label className="output-volume"><span>Volume de {activeOutput.name}<b>{boseVolume}%</b></span><input type="range" min="0" max="100" value={boseVolume} onChange={(event) => changeBoseVolume(Number(event.target.value))} /></label>}
          {activeOutput.kind === 'cast' && <label className="output-volume"><span>Volume de {activeOutput.name}<b>{castVolume}%</b></span><input type="range" min="0" max="100" value={castVolume} onChange={(event) => void changeCastVolume(Number(event.target.value))} /></label>}
          {(castMessage || boseMessage) && <p className="output-message" role="status">{boseMessage || castMessage}</p>}
        </section>
      </div>}
      {nowPlaying && fullPlayerOpen && <section className="full-player" role="dialog" aria-modal="true" aria-label="Lecteur en cours">
        <header className="full-player-head">
          <button onClick={() => setFullPlayerOpen(false)} aria-label="Réduire le lecteur"><ChevronDown size={25} /></button>
          <div className="full-player-brand"><img src={podmixInfinityLogo} alt="Podmix" /><span>PODMIX</span></div>
          <button
            onClick={openOutputPicker}
            aria-label={`Sortie audio : ${activeOutput.name}`}
            aria-pressed={activeOutput.kind !== 'phone'}
            className={`full-player-output ${activeOutput.kind !== 'phone' ? 'active' : ''}`}
          ><Cast size={20} /></button>
        </header>
        <div className="full-player-stage">
          <div className="full-player-art"><CachedArtwork artworkUrl={nowPlaying.artworkUrl} title={nowPlaying.title} Icon={AudioLines} size={64} /></div>
          <div className="full-player-copy"><span>EN LECTURE</span><h2>{nowPlaying.title}</h2><p>{nowPlaying.artist}</p></div>
          <div className="full-player-actions">
            {playerFavorite && <button className={`full-player-favorite ${playerFavoriteActive ? 'active' : ''}`} onClick={() => toggleTrackFavorite(playerFavorite.key)} aria-label={`${playerFavoriteActive ? 'Retirer' : 'Ajouter'} ${playerFavorite.title} ${playerFavoriteActive ? 'des' : 'aux'} favoris`} title={playerFavoriteActive ? 'Retirer des favoris' : 'Ajouter aux favoris'}><Heart size={23} fill={playerFavoriteActive ? 'currentColor' : 'none'} /></button>}
          </div>
          <div className="full-player-progress">
            <input
              type="range"
              aria-label="Position de lecture"
              min="0"
              max={Math.max(1, globalDuration)}
              step="0.1"
              value={Math.min(globalPosition, Math.max(1, globalDuration))}
              disabled={globalDuration <= 0}
              onChange={(event) => void seekGlobal(Number(event.target.value))}
              style={{ '--player-progress': `${globalDuration > 0 ? Math.min(100, globalPosition / globalDuration * 100) : 0}%` } as CSSProperties}
            />
            <div><time>{formatTime(globalPosition)}</time><time>{globalDuration > 0 ? formatTime(globalDuration) : '–:––'}</time></div>
          </div>
          <div className="full-player-controls">
            <button onClick={() => void cycleRepeatMode()} aria-label="Répétition" className={repeatMode !== 0 ? 'active' : ''}><Repeat size={19} /><small>{repeatMode === 1 ? '1' : repeatMode === 2 ? '∞' : ''}</small></button>
            <button onClick={() => skip('previous')} aria-label="Morceau précédent" disabled={skipPendingDirection !== null || !canSkipPrevious}><SkipBack size={25} fill="currentColor" /></button>
            <button className="full-player-play" onClick={() => void toggleCurrentPlayback()} aria-label={globalPlaying ? 'Mettre en pause' : 'Lire'}>{globalPlaying ? <Pause size={31} /> : <Play size={31} fill="currentColor" />}</button>
            <button onClick={() => skip('next')} aria-label="Morceau suivant" disabled={skipPendingDirection !== null || !canSkipNext}><SkipForward size={25} fill="currentColor" /></button>
            <button onClick={openOutputPicker} aria-label={`Sortie audio : ${activeOutput.name}`} className={activeOutput.kind !== 'phone' ? 'active' : ''}><Cast size={20} /></button>
          </div>
        </div>
      </section>}
      {nowPlaying && <div className="mini-player-global" onTouchStart={(event) => { if (!(event.target as HTMLElement).closest('button,input,label')) { miniPlayerIgnoreTap.current = false; miniPlayerSwipeStart.current = event.touches[0]?.clientY ?? null } }} onTouchEnd={(event) => { const start = miniPlayerSwipeStart.current; miniPlayerSwipeStart.current = null; if (start !== null && (event.changedTouches[0]?.clientY ?? start) < start - 45) { miniPlayerIgnoreTap.current = true; setFullPlayerOpen(true) } }} onClick={(e) => { if (!(e.target as HTMLElement).closest('button,input,label')) { if (miniPlayerIgnoreTap.current) { miniPlayerIgnoreTap.current = false; return } handleMiniPlayerTap() } }} style={{ cursor: 'pointer' }}>
        <div className="mini-art"><CachedArtwork artworkUrl={nowPlaying.artworkUrl} title={nowPlaying.title} Icon={AudioLines} size={19} /></div>
        <div className="mini-meta"><strong>{nowPlaying.title}</strong><span>{nowPlaying.artist}</span></div>
        <button onClick={() => void cycleRepeatMode()} aria-label={repeatMode === 0 ? 'Activer la répétition' : repeatMode === 1 ? 'Répéter le morceau' : 'Répéter la file'} aria-pressed={repeatMode !== 0} className={repeatMode !== 0 ? 'active repeat-control' : 'repeat-control'}><Repeat size={16} /><small>{repeatMode === 1 ? '1' : repeatMode === 2 ? '∞' : ''}</small></button>
        <button
          onClick={() => skip('previous')}
          aria-label="Morceau précédent"
          disabled={skipPendingDirection !== null || !canSkipPrevious}
          className={`mini-previous ${skipPendingDirection === 'previous' ? 'transport-pending' : ''}`}
        ><SkipBack size={17} fill="currentColor" /></button>
        <button className="mini-play-toggle" onClick={() => void toggleCurrentPlayback()} aria-label={globalPlaying ? 'Mettre en pause' : 'Lire'}>{globalPlaying ? <Pause size={18} /> : <Play size={18} fill="currentColor" />}</button>
        <button
          onClick={() => skip('next')}
          aria-label="Morceau suivant"
          disabled={skipPendingDirection !== null || !canSkipNext}
          className={`mini-next ${skipPendingDirection === 'next' ? 'transport-pending' : ''}`}
        ><SkipForward size={17} fill="currentColor" /></button>
        <button onClick={openOutputPicker} aria-label={`Sortie audio : ${activeOutput.name}`} aria-pressed={activeOutput.kind !== 'phone'} className={`mini-output ${activeOutput.kind !== 'phone' ? 'active' : ''}`}><Cast size={17} /></button>
        {playerFavorite && <button className={`mini-favorite ${playerFavoriteActive ? 'active' : ''}`} onClick={() => toggleTrackFavorite(playerFavorite.key)} aria-label={`${playerFavoriteActive ? 'Retirer' : 'Ajouter'} ${playerFavorite.title} ${playerFavoriteActive ? 'des' : 'aux'} favoris`} title={playerFavoriteActive ? 'Retirer des favoris' : 'Ajouter aux favoris'}><Heart size={21} fill={playerFavoriteActive ? 'currentColor' : 'none'} /></button>}
        <div className="mini-progress">
          <time>{formatTime(globalPosition)}</time>
          <input
            type="range"
            aria-label="Position de lecture"
            min="0"
            max={Math.max(1, globalDuration)}
            step="0.1"
            value={Math.min(globalPosition, Math.max(1, globalDuration))}
            disabled={globalDuration <= 0}
            onChange={(event) => void seekGlobal(Number(event.target.value))}
            style={{ '--player-progress': `${globalDuration > 0 ? Math.min(100, globalPosition / globalDuration * 100) : 0}%` } as CSSProperties}
          />
          <time>{globalDuration > 0 ? formatTime(globalDuration) : '–:––'}</time>
        </div>
        <label className="mini-volume" onClick={(event) => event.stopPropagation()}>
          <span><Speaker size={15} /><b>Volume</b><small>{playbackVolume}% · {playbackVolumeLabel}</small></span>
          <input type="range" aria-label={`Volume ${playbackVolumeLabel}`} min="0" max="100" value={playbackVolume} onChange={(event) => void changePlaybackVolume(Number(event.target.value))} style={{ '--volume-level': `${playbackVolume}%` } as CSSProperties} />
        </label>
      </div>}
      {downloadMessage && <span className="sr-only" role="status">{downloadMessage}</span>}
    </div>
  )
}

export default App

