import { useEffect, useRef, useState } from 'react'
import type { CSSProperties, ChangeEvent } from 'react'
import { App as CapacitorApp } from '@capacitor/app'
import { AudioLines, Cast, Check, ChevronDown, ChevronRight, Clock3, CloudUpload, Command, Disc3, Download, ExternalLink, Gauge, Heart, Library, Mic2, MoreHorizontal, Pause, Play, Plus, Radio, RotateCcw, Search, Settings2, SkipBack, SkipForward, Sparkles, Speaker, Trash2, WandSparkles, Wifi } from 'lucide-react'
import WaveSurfer from 'wavesurfer.js'
import { alignTracklist, createBoseCastSession, createDetectionJob, createEpisodeAnalysisJob, discover1001Tracklist, discoverTracklist, findDetectionJob, findTrackArtwork, findTrackLinks, fingerprintTrack, getApiUrl, getDetectionJob, importDjSet, importRssFeed, observeDetectionJob, refineDetectionJob, searchDjSets, searchPodcasts, searchRadios, searchTracklistCandidates, setApiUrl, testApi, uploadAudio, validateCatalogTrack } from './api'
import type { CatalogSource, DetectionJob, DjSearchResult, Episode, OfflineEpisode, PodcastSearchResult, Track } from './domain'
import { loadCatalog, loadOfflineEpisodes, loadSession, saveCatalog, saveOfflineEpisodes, saveSession } from './storage'
import { podmixPlayer } from './nativePlayer'
import type { BoseDevice, CastDevice } from './nativePlayer'
import { checkForUpdate, currentVersion, openUpdate } from './updates'
import type { UpdateManifest } from './updates'
import { mergeCatalogSource, mergeEpisode } from './catalogMerge'
import './App.css'
import './identification.css'

const initialTracks: Track[] = []

type AppView = 'home' | 'favorites' | 'studio' | 'settings'
type HomeSectionId = 'resume' | 'podcasts' | 'shows' | 'radios' | 'djSets' | 'offline'
type HistoryItem = { id: string; title: string; artist: string; url: string; position: number; duration?: number; playedAt: string }
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
  scope?: 'episode' | 'track' | 'favorite' | 'radio'
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

function formatTime(seconds: number) {
  const safe = Number.isFinite(seconds) ? Math.max(0, seconds) : 0
  const hours = Math.floor(safe / 3600)
  const minutes = Math.floor((safe % 3600) / 60)
  const secs = Math.floor(safe % 60)
  return hours > 0 ? `${hours}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}` : `${minutes}:${secs.toString().padStart(2, '0')}`
}

function usesEstimatedPositions(tracks: Track[] | undefined) {
  return (tracks ?? []).some((track) =>
    (track.evidence ?? []).some((item) => item === 'Ordre de la tracklist'),
  )
}

function isAutomaticMusicSource(source: CatalogSource | undefined) {
  return Boolean(source && (
    source.kind === 'dj'
    || source.musical === true
  ))
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

function parseDuration(value: string): number {
  const parts = value.split(':').map(Number)
  if (!parts.length || parts.some((part) => !Number.isFinite(part) || part < 0)) return 0
  return parts.reduce((total, part) => total * 60 + part, 0)
}

function episodePlaybackStatus(episode: Episode, position: number, liveDuration = 0) {
  const duration = liveDuration > 0 ? liveDuration : parseDuration(episode.duration)
  if (position < 1) return { kind: 'new', label: 'À lire', percent: 0 }
  if (duration <= 0) return { kind: 'progress unknown', label: 'En cours', percent: 0 }
  const percent = Math.min(100, Math.max(1, Math.round((position / duration) * 100)))
  if (percent >= 95 || duration - position <= 30) return { kind: 'done', label: 'Lu', percent: 100 }
  return { kind: 'progress', label: 'En cours', percent }
}

function favoriteTrackKey(episodeId: string, track: Track, trackIndex: number) {
  return `${episodeId}::track::${track.id}::${trackIndex}`
}

function trackArtwork(track: Track, episode: Episode, source: CatalogSource) {
  return track.artworkUrl || episode.artworkUrl || source.artworkUrl || ''
}

function DeezerIcon() {
  return <svg className="favorite-service-icon" viewBox="0 0 20 20" aria-hidden="true">
    <path d="M2 14h3v3H2zm0-4h3v3H2zm4 4h3v3H6zm0-8h3v3H6zm0 4h3v3H6zm4 4h3v3h-3zm0-8h3v3h-3zm0 4h3v3h-3zm4 4h3v3h-3zm0-12h3v3h-3zm0 4h3v3h-3zm0 4h3v3h-3z" fill="currentColor" />
  </svg>
}

function SpotifyIcon() {
  return <svg className="favorite-service-icon" viewBox="0 0 20 20" aria-hidden="true">
    <circle cx="10" cy="10" r="8.5" fill="currentColor" />
    <path d="M5.4 7.4c3.6-1 7.1-.7 9.6.7M6 10.3c3-.8 6.2-.5 8.4.7m-7.8 2.1c2.4-.6 5-.4 7 .6" fill="none" stroke="var(--ink)" strokeLinecap="round" strokeWidth="1.25" />
  </svg>
}

function favoriteServiceControl(service: 'deezer' | 'spotify', url?: string) {
  const label = service === 'deezer' ? 'Deezer' : 'Spotify'
  const icon = service === 'deezer' ? <DeezerIcon /> : <SpotifyIcon />
  return url
    ? <a className={`favorite-service ${service} available`} href={url} target="_blank" rel="noreferrer" aria-label={`${label} disponible : ouvrir le morceau`} title={`Écouter sur ${label}`}>{icon}<span className="favorite-service-label">{label}</span></a>
    : <button className={`favorite-service ${service} unavailable`} type="button" disabled aria-label={`${label} indisponible pour ce morceau`} title={`${label} indisponible`}>{icon}<span className="favorite-service-label">{label}</span></button>
}

function loadFavoriteTrackIds() {
  try {
    const stored = JSON.parse(localStorage.getItem('podmix-track-favorites-v1') ?? '[]')
    return Array.isArray(stored) ? stored.map(String) : []
  } catch {
    return []
  }
}

function loadStoredArray<T>(key: string): T[] {
  try {
    const stored: unknown = JSON.parse(localStorage.getItem(key) ?? '[]')
    return Array.isArray(stored) ? stored as T[] : []
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
  const persistenceReady = useRef(false)
  const backupInputRef = useRef<HTMLInputElement>(null)
  const undoStack = useRef<Track[][]>([])
  const [tracks, setTracks] = useState(initialTracks)
  const [selectedId, setSelectedId] = useState(0)
  const [isPlaying, setIsPlaying] = useState(false)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(3420)
  const [hasLocalAudio, setHasLocalAudio] = useState(false)
  const [uploadId, setUploadId] = useState('')
  const [uploading, setUploading] = useState(false)
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
  const [fingerprinting, setFingerprinting] = useState(false)
  const [activeView, setActiveView] = useState<AppView>('home')
  const [homeSectionOrder, setHomeSectionOrder] = useState<HomeSectionId[]>(() => {
    try {
      const stored = JSON.parse(localStorage.getItem('podmix-home-section-order-v1') ?? 'null')
      if (Array.isArray(stored) && stored.every((id) => ['resume', 'podcasts', 'shows', 'radios', 'djSets', 'offline'].includes(id))) {
        return stored as HomeSectionId[]
      }
    } catch {}
    return ['resume', 'podcasts', 'shows', 'radios', 'djSets', 'offline']
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
  const catalogRef = useRef(catalog)
  const artworkLookupAttempted = useRef(new Set<string>())
  const linkLookupAttempted = useRef(new Set<string>())
  const forceSpotifyLookup = useRef(new Set<string>())
  const [spotifyRefreshRequest, setSpotifyRefreshRequest] = useState(0)
  const [showAddSource, setShowAddSource] = useState(false)
  const [feedUrl, setFeedUrl] = useState('')
  const [feedError, setFeedError] = useState('')
  const [addingFeed, setAddingFeed] = useState(false)
  const [selectedSourceId, setSelectedSourceId] = useState('')
  const [selectedEpisodeId, setSelectedEpisodeId] = useState('')
  const [nowPlaying, setNowPlaying] = useState<NowPlayingItem>()
  const nowPlayingRef = useRef(nowPlaying)
  const [globalPlaying, setGlobalPlaying] = useState(false)
  const [globalPosition, setGlobalPosition] = useState(0)
  const [globalDuration, setGlobalDuration] = useState(0)
  const [activeMediaId, setActiveMediaId] = useState('')
  const [downloadMessage, setDownloadMessage] = useState('')
  const [sourceMode, setSourceMode] = useState<'rss' | 'show' | 'radio' | 'dj'>('rss')
  const [radioQuery, setRadioQuery] = useState('')
  const [radioResults, setRadioResults] = useState<CatalogSource[]>([])
  const [podcastQuery, setPodcastQuery] = useState('')
  const [podcastResults, setPodcastResults] = useState<PodcastSearchResult[]>([])
  const [searchingPodcasts, setSearchingPodcasts] = useState(false)
  const [searchingRadios, setSearchingRadios] = useState(false)
  const [djQuery, setDjQuery] = useState('')
  const [djResults, setDjResults] = useState<DjSearchResult[]>([])
  const [selectedDjResults, setSelectedDjResults] = useState<string[]>([])
  const [searchingDj, setSearchingDj] = useState(false)
  const [favoriteTrackIds, setFavoriteTrackIds] = useState<string[]>(loadFavoriteTrackIds)
  const [nativeFavoritesReady, setNativeFavoritesReady] = useState(!podmixPlayer.isNative)
  const [history, setHistory] = useState<HistoryItem[]>(() => loadStoredArray<HistoryItem>('podmix-history-v1'))
  const historyRef = useRef(history)
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
  const [boseIp, setBoseIp] = useState(() => localStorage.getItem('podmix-bose-ip') ?? '')
  const [boseVolume, setBoseVolume] = useState(30)
  const [boseMessage, setBoseMessage] = useState('')
  const [castVolume, setCastVolume] = useState(50)
  const boseActiveRef = useRef(false)
  const boseStartPositionRef = useRef(0)
  const boseContentOffsetRef = useRef(0)
  const bosePersistedAtRef = useRef(0)
  const boseClockRef = useRef({ positionSeconds: 0, updatedAt: 0, playing: false, remotePositionSeconds: 0 })
  const boseTrackTransitionRef = useRef(false)
  const boseVolumePendingRef = useRef<number | null>(null)
  const boseVolumeSendingRef = useRef(false)

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
        setSelectedEpisodeId('')
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
  const [updateMessage, setUpdateMessage] = useState('Recherche automatique…')

  useEffect(() => () => waveRef.current?.destroy(), [])
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
    const timer = window.setTimeout(() => {
      setSearchingPodcasts(true)
      setFeedError('')
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
    const timer = window.setTimeout(() => {
      setSearchingRadios(true)
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
    if (!showAddSource || sourceMode !== 'dj') return
    const query = djQuery.trim()
    if (query.length < 2) {
      setDjResults([])
      setSelectedDjResults([])
      setSearchingDj(false)
      return
    }
    let cancelled = false
    const timer = window.setTimeout(() => {
      setSearchingDj(true)
      setFeedError('')
      void searchDjSets(query)
        .then((results) => {
          if (cancelled) return
          setDjResults(results)
          setSelectedDjResults([])
        })
        .catch((error) => { if (!cancelled) setFeedError(error instanceof Error ? error.message : 'Recherche DJ impossible') })
        .finally(() => { if (!cancelled) setSearchingDj(false) })
    }, 350)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [djQuery, showAddSource, sourceMode])
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
          && (forceSpotifyLookup.current.has(key) || !track.deezerUrl || !track.spotifyUrl)
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
            const replaceSpotify = forceSpotifyLookup.current.has(key)
            forceSpotifyLookup.current.delete(key)
            return {
              ...track,
              artworkUrl: track.artworkUrl || links.artworkUrl,
              deezerUrl: track.deezerUrl || links.deezerUrl,
              spotifyUrl: replaceSpotify ? links.spotifyUrl : track.spotifyUrl || links.spotifyUrl,
            }
          }),
        })),
      })))
    }).catch(() => candidates.forEach((candidate) => linkLookupAttempted.current.delete(candidate.key)))
  }, [catalog, favoriteTrackIds, spotifyRefreshRequest])
  useEffect(() => {
    let stopped = false
    const refreshFeeds = async () => {
      if (!navigator.onLine) return
      const lastRefresh = Number(localStorage.getItem('podmix-last-feed-refresh') || 0)
      if (Date.now() - lastRefresh < 15 * 60 * 1000) return
      localStorage.setItem('podmix-last-feed-refresh', String(Date.now()))
      const scheduled: Array<{ source: CatalogSource; episode: Episode }> = []
      const refreshed = await Promise.all(catalogRef.current.map(async (source) => {
        if (!source.feedUrl || !['podcast', 'show'].includes(source.kind)) return source
        try {
          const next = await importRssFeed(source.feedUrl, source.kind === 'show' ? 'show' : 'podcast', source.kind === 'show' ? settings.maxShowEpisodes : settings.maxPodcastEpisodes)
          const merged = mergeCatalogSource(source, next)
          if (settings.automaticAnalysis && isAutomaticMusicSource(source)) {
            const previousIds = new Set(source.episodes.map((episode) => episode.id))
            const newest = merged.episodes.find((episode) => episode.audioUrl && !previousIds.has(episode.id))
            if (newest) scheduled.push({ source: merged, episode: newest })
          }
          return merged
        } catch {
          return source
        }
      }))
      if (!stopped) {
        setCatalog(refreshed)
        for (const item of scheduled) void scheduleEpisodeAnalysis(item.source, item.episode)
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
      if (isActive) void restoreNativeFavorites()
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
    localStorage.setItem('podmix-history-v1', JSON.stringify(history.slice(0, 100)))
  }, [history])
  useEffect(() => { nowPlayingRef.current = nowPlaying }, [nowPlaying])
  useEffect(() => localStorage.setItem('podmix-bose-ip', boseIp), [boseIp])
  useEffect(() => {
    if (podmixPlayer.isNative) void restoreBoseSession()
    // La recherche réseau est volontairement lancée une fois au démarrage.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  useEffect(() => localStorage.setItem('podmix-settings-v1', JSON.stringify(settings)), [settings])
  useEffect(() => {
    offlineEpisodesRef.current = offlineEpisodes
    saveOfflineEpisodes(offlineEpisodes)
  }, [offlineEpisodes])
  useEffect(() => {
    if (!podmixPlayer.isNative) return
    const offlineById = new Map(offlineEpisodes.filter((item) => item.status === 'completed' && item.localUri).map((item) => [item.id, item]))
    const library = catalog.flatMap((source) => {
      if (source.kind === 'radio' && source.streamUrl) {
        return [{
          id: `source::${source.id}`,
          parentId: 'podmix-root',
          groupId: source.id,
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
        const url = offline?.localUri || episode.audioUrl
        if (!url) return []
        if (!episode.tracks?.length) {
          return [{
            id: `episode::${episode.id}`,
            parentId: sourceId,
            groupId: episode.id,
        url,
        title: episode.title,
        artist: source.title,
        artworkUrl: episode.artworkUrl || source.artworkUrl,
        browsable: false,
            playable: true,
          }]
        }
        const episodeId = `episode::${episode.id}`
        const trackItems = episode.tracks.map((track, index) => {
          const nextTime = episode.tracks?.[index + 1]?.time
          const mediaId = favoriteTrackKey(episode.id, track, index)
          return {
            id: mediaId,
            parentId: episodeId,
            groupId: episode.id,
            favoriteId: mediaId,
            url,
            title: track.title,
            artist: track.artist || source.title,
            artworkUrl: trackArtwork(track, episode, source),
            browsable: false,
            playable: true,
            startPositionSeconds: Math.max(0, track.time),
            ...(nextTime !== undefined && nextTime > track.time
              ? { endPositionSeconds: nextTime }
              : {}),
          }
        })
        return [{
          id: episodeId,
          parentId: sourceId,
          title: episode.title,
          artist: source.title,
          artworkUrl: episode.artworkUrl || source.artworkUrl,
          browsable: true,
          playable: false,
        }, ...trackItems]
      })
      if (!episodes.length) return []
      return [{
        id: sourceId,
        parentId: 'podmix-root',
        title: source.title,
        artist: source.kind === 'podcast' ? 'Podcast' : source.kind === 'show' ? 'Émission' : 'DJ set',
        artworkUrl: source.artworkUrl,
        browsable: true,
        playable: false,
      }, ...episodes]
    })
    void podmixPlayer.syncLibrary(library).catch(() => undefined)
    if (nativeFavoritesReady) {
      void podmixPlayer.syncFavorites(favoriteTrackIds).catch(() => undefined)
    }
    void podmixPlayer.syncSubscriptions(
      catalog
        .filter((source) => source.feedUrl && ['podcast', 'show'].includes(source.kind))
        .map((source) => ({ id: source.id, title: source.title, feedUrl: source.feedUrl! })),
    ).catch(() => undefined)
  }, [catalog, favoriteTrackIds, nativeFavoritesReady, offlineEpisodes])
  useEffect(() => {
    void podmixPlayer.getStorage().then(setStorage).catch(() => undefined)
  }, [offlineEpisodes])
  useEffect(() => {
    if (!podmixPlayer.isNative) return
    const listener = podmixPlayer.onStateChanged((state) => {
      if (!boseActiveRef.current) {
        setGlobalPlaying(state.playing)
        setGlobalPosition(Math.max(0, state.positionSeconds))
        setGlobalDuration(Math.max(0, state.durationSeconds))
      }
      setActiveMediaId(state.mediaId)
      if (state.error) setDownloadMessage(`Lecture impossible : ${state.error}`)
      if (studioEpisodeRef.current && state.mediaId === studioEpisodeRef.current.episodeId) {
        setCurrentTime(state.positionSeconds)
        if (state.durationSeconds > 0) setDuration(state.durationSeconds)
        setIsPlaying(state.playing)
      }
      if (state.mediaId && state.title) {
        const trackQueue = trackQueueRef.current
        const favoriteItem = favoriteQueueRef.current?.[state.queueIndex]
        const activeTrack = trackQueue?.mediaIds.includes(state.mediaId) ? trackQueue.tracks[state.queueIndex] : undefined
        const queued = catalogRef.current
          .flatMap((source) => source.episodes.map((episode) => ({ ...episode, artist: source.title })))
          .find((episode) => episode.id === state.mediaId)
        setNowPlaying((current) => ({
          id: favoriteItem?.episode.id ?? (activeTrack ? trackQueue!.episodeId : state.mediaId),
          title: favoriteItem?.track.title ?? activeTrack?.title ?? state.title,
          artist: favoriteItem?.track.artist ?? activeTrack?.artist ?? state.artist,
          url: favoriteItem?.episode.audioUrl ?? trackQueue?.audioUrl ?? queued?.audioUrl ?? current?.url ?? '',
          scope: favoriteItem ? 'favorite' : activeTrack ? 'track' : current?.scope ?? 'episode',
          artworkUrl: favoriteItem
            ? trackArtwork(favoriteItem.track, favoriteItem.episode, favoriteItem.source)
            : activeTrack
              ? trackQueue?.artworkUrls[state.queueIndex]
              : queued?.artworkUrl ?? current?.artworkUrl,
        }))
      }
      if (!state.playing && state.playbackState !== 1) persistPlaybackState(state)
    })
    return () => { void listener.then((handle) => handle.remove()).catch(() => undefined) }
  }, [])
  useEffect(() => {
    if (!nowPlaying) return
    const timer = window.setInterval(async () => {
      if (boseActiveRef.current) {
        try {
          const state = await podmixPlayer.boseGetState(boseIp.trim())
          let relativePosition = syncBoseClock(state.positionSeconds ?? 0, Boolean(state.playing))
          relativePosition = await advanceBoseTrackIfNeeded(relativePosition, Boolean(state.playing))
          setGlobalPlaying(Boolean(state.playing))
          setGlobalPosition(relativePosition)
          if (Date.now() - bosePersistedAtRef.current >= 5000) {
            const localState = await podmixPlayer.getState()
            persistPlaybackState({ ...localState, positionSeconds: relativePosition, playing: Boolean(state.playing) })
            persistBoseClock(relativePosition, Boolean(state.playing))
            bosePersistedAtRef.current = Date.now()
          }
        } catch {
          setBoseMessage('Connexion avec la Bose interrompue')
        }
        return
      }
      const state = await podmixPlayer.getState()
      setGlobalPlaying(state.playing)
      setGlobalPosition(Math.max(0, state.positionSeconds))
      setGlobalDuration(Math.max(0, state.durationSeconds))
      setActiveMediaId(state.mediaId)
      const trackQueue = trackQueueRef.current
      const favoriteItem = favoriteQueueRef.current?.[state.queueIndex]
      const activeTrack = trackQueue?.mediaIds.includes(state.mediaId) ? trackQueue.tracks[state.queueIndex] : undefined
      if (favoriteItem) {
        setNowPlaying({
          id: favoriteItem.episode.id,
          title: favoriteItem.track.title,
          artist: favoriteItem.track.artist,
          url: favoriteItem.episode.audioUrl,
          scope: 'favorite',
          artworkUrl: trackArtwork(favoriteItem.track, favoriteItem.episode, favoriteItem.source),
        })
      }
      if (trackQueue && activeTrack) {
        setNowPlaying({
          id: trackQueue.episodeId,
          title: activeTrack.title,
          artist: activeTrack.artist,
          url: trackQueue.audioUrl,
          scope: 'track',
          artworkUrl: trackQueue.artworkUrls[state.queueIndex],
        })
      }
      persistPlaybackState(state)
    }, 1000)
    return () => window.clearInterval(timer)
  }, [boseIp, nowPlaying])
  useEffect(() => {
    const persistCurrentPosition = () => {
      void podmixPlayer.getState().then(persistPlaybackState).catch(() => undefined)
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
      const states = await Promise.all(offlineEpisodesRef.current.map((episode) => podmixPlayer.getDownload(episode.id)))
      if (cancelled) return
      setOfflineEpisodes((items) => items.map((item) => {
        const state = states.find((candidate) => candidate.id === item.id)
        if (!state || state.status === 'not_found' || state.status === 'unknown') return item
        return {
          ...item,
          status: state.status,
          localUri: state.localUri ?? item.localUri,
          bytesDownloaded: state.bytesDownloaded,
          totalBytes: state.totalBytes,
        }
      }))
    }
    void refresh()
    const timer = window.setInterval(refresh, 2000)
    return () => { cancelled = true; window.clearInterval(timer) }
  }, [offlineEpisodes.length])

  const selected = tracks.find((track) => track.id === selectedId) ?? tracks[0] ?? {
    id: 0, time: currentTime, artist: '—', title: 'Aucune transition', confidence: 0, source: 'manual' as const,
  }

  function loadAudio(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file || !waveformRef.current) return
    waveRef.current?.destroy()
    const wave = WaveSurfer.create({
      container: waveformRef.current, waveColor: '#60605b', progressColor: '#ff6a3d',
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
    setUploadId('')
    setUploading(true)
    setDetectionError('')
    uploadAudio(file)
      .then((upload) => {
        setUploadId(upload.id)
        if (settings.automaticAnalysis) void startDetection(upload.id)
      })
      .catch(() => setDetectionError('Import serveur impossible — vérifiez que npm run api est lancé'))
      .finally(() => setUploading(false))
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
  ) {
    if (!target) return
    const hasNamedTracks = job.tracks.some((track) => !/^Transition \d+$/i.test(track.title))
    setCatalog((sources) => sources.map((source) => source.id !== target.sourceId ? source : {
      ...source,
      episodes: source.episodes.map((episode) => episode.id !== target.episodeId ? episode : {
        ...episode,
        ...(job.status === 'completed' && hasNamedTracks ? { tracks: job.tracks } : {}),
        analysis: {
          jobId: job.id,
          status: job.status,
          stage: job.stage,
          progress: job.progress,
          updatedAt: job.updatedAt,
          ...(job.error ? { error: job.error } : {}),
        },
      }),
    }))
  }

  function watchDetectionJob(
    job: DetectionJob,
    target?: { sourceId: string; episodeId: string },
  ) {
    setCurrentJobId(job.id)
    setDetection(job.status === 'completed' ? 'done' : 'running')
    setProgress(job.progress)
    setDetectionStage(job.stage)
    persistEpisodeAnalysis(target, job)
    if (job.status === 'completed') {
      setTracks(job.tracks)
      if (job.tracks[0]) setSelectedId(job.tracks[0].id)
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
    }, () => {
      setDetection('idle')
      setDetectionError('Flux de progression interrompu')
    })
  }

  async function scheduleEpisodeAnalysis(source: CatalogSource, episode: Episode) {
    if (!episode.audioUrl) return
    const target = { sourceId: source.id, episodeId: episode.id }
    try {
      const existing = await findDetectionJob(`episode:${episode.id}`)
      const job = existing ?? await createEpisodeAnalysisJob(source, episode)
      persistEpisodeAnalysis(target, job)
      if (['completed', 'failed', 'cancelled'].includes(job.status)) return
      observeDetectionJob(job.id, (update) => {
        persistEpisodeAnalysis(target, update)
      }, () => {
        // Le job reste durable sur le VPS et sera resynchronisé à la prochaine ouverture.
      })
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
    }
  }

  async function startDetection(
    explicitUploadId?: string,
    explicitTarget?: { sourceId: string; episodeId: string },
  ) {
    const target = explicitTarget ?? studioEpisode
    const source = target ? catalogRef.current.find((item) => item.id === target.sourceId) : undefined
    const episode = source?.episodes.find((item) => item.id === target?.episodeId)
    let targetUploadId = explicitUploadId ?? uploadId
    if (episode && !targetUploadId) {
      try {
        const existing = await findDetectionJob(`episode:${episode.id}`)
        const hasNamedTracks = existing?.tracks.some((track) => !/^Transition \d+$/i.test(track.title))
        if (existing && (['queued', 'running'].includes(existing.status) || hasNamedTracks)) {
          watchDetectionJob(existing, target)
          return
        }
      } catch {
        // Une indisponibilité de l’index ne doit pas empêcher une nouvelle analyse.
      }
      if (source && episode.audioUrl) {
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
    if (!targetUploadId) {
      setDetectionError(uploading ? 'Import audio en cours…' : 'Importez d’abord un fichier audio')
      return
    }
    setDetection('running')
    setProgress(0)
    setDetectionStage('Connexion au moteur')
    setDetectionError('')
    try {
      const job = await createDetectionJob(episode?.title ?? audioName, targetUploadId, {
        episodeId: episode?.id,
        title: episode?.title,
        description: episode?.description,
        sourceUrl: episode?.sourceUrl,
        automaticTracklist: Boolean(episode && settings.automaticAnalysis),
        enable1001: Boolean(episode && isAutomaticMusicSource(source)),
        enableExternalTracklists: Boolean(episode && isAutomaticMusicSource(source)),
        sourceKind: source?.kind,
        musical: source?.musical === true,
        refineTimestamps: Boolean(episode && settings.automaticAnalysis),
        requestKey: episode ? `episode:${episode.id}` : undefined,
        force: Boolean(episode?.analysis?.status === 'completed' || detection === 'done'),
      })
      watchDetectionJob(job, target)
    } catch {
      setDetection('idle')
      setDetectionError('Moteur hors ligne — lancez npm run api')
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

  async function fingerprintSelected() {
    if (!currentJobId || !selected.id) {
      setDetectionError('Une analyse audio est nécessaire avant l’empreinte acoustique')
      return
    }
    setFingerprinting(true)
    setDetectionError('')
    try {
      const result = await fingerprintTrack(currentJobId, selected.id)
      setTracks((items) => items.map((track) => track.id === result.track.id ? result.track : track))
      setDetectionStage(result.bestMatch ? `AcoustID : ${result.bestMatch.artist} — ${result.bestMatch.title}` : result.message || 'Aucune empreinte reconnue')
    } catch (error) {
      setDetectionError(error instanceof Error ? error.message : 'Empreinte acoustique impossible')
    } finally {
      setFingerprinting(false)
    }
  }

  async function refineWithChroma() {
    if (!currentJobId || !tracks.length) {
      setDetectionError('Terminez une analyse et importez une tracklist avant le raffinage')
      return
    }
    setDetection('running')
    setProgress(0)
    setDetectionStage('Raffinage chroma en attente')
    setDetectionError('')
    try {
      await refineDetectionJob(currentJobId)
      observeDetectionJob(currentJobId, (update) => {
        setProgress(update.progress)
        setDetectionStage(update.stage)
        if (update.status === 'completed') {
          setDetection('done')
          setTracks(update.tracks)
          if (update.tracks[0]) setSelectedId(update.tracks[0].id)
        }
        if (update.status === 'failed' || update.status === 'cancelled') {
          setDetection('idle')
          setDetectionError(update.error ?? 'Raffinage interrompu')
        }
      }, () => {
        setDetection('idle')
        setDetectionError('Flux de raffinage interrompu')
      })
    } catch (error) {
      setDetection('idle')
      setDetectionError(error instanceof Error ? error.message : 'Raffinage impossible')
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
      setDetectionError('Terminez d’abord l’analyse audio')
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
          const aligned = await alignTracklist(currentJobId, text)
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

  const viewLabels: Record<AppView, string> = {
    home: 'Accueil', favorites: 'Favoris', studio: 'Analyses', settings: 'Réglages',
  }

  function navigateTo(view: AppView) {
    setActiveView(view)
    setSelectedSourceId('')
    setSelectedEpisodeId('')
  }

  async function addFeed() {
    if (!feedUrl.trim()) return
    setAddingFeed(true); setFeedError('')
    try {
      const source = await importRssFeed(feedUrl.trim(), sourceMode === 'show' ? 'show' : 'podcast', sourceMode === 'show' ? settings.maxShowEpisodes : settings.maxPodcastEpisodes)
      setCatalog((items) => [source, ...items.filter((item) => item.id !== source.id)])
      if (settings.automaticAnalysis && isAutomaticMusicSource(source) && source.episodes[0]) {
        void scheduleEpisodeAnalysis(source, source.episodes[0])
      }
      setFeedUrl(''); setShowAddSource(false); setActiveView('home')
    } catch (error) {
      setFeedError(error instanceof Error ? error.message : 'Flux indisponible')
    } finally {
      setAddingFeed(false)
    }
  }

  async function addDjSet() {
    if (!feedUrl.trim()) return
    setAddingFeed(true); setFeedError('')
    try {
      const source = await importDjSet(feedUrl.trim())
      setCatalog((items) => [source, ...items.filter((item) => item.id !== source.id)])
      if (settings.automaticAnalysis && source.episodes[0]) {
        void scheduleEpisodeAnalysis(source, source.episodes[0])
      }
      setFeedUrl(''); setShowAddSource(false); setSelectedSourceId(source.id); setActiveView('home')
    } catch (error) {
      setFeedError(error instanceof Error ? error.message : 'DJ set indisponible')
    } finally {
      setAddingFeed(false)
    }
  }

  async function importSelectedDjSets() {
    const selectedResults = djResults.filter((item) => selectedDjResults.includes(item.id))
    if (!selectedResults.length) return
    setAddingFeed(true); setFeedError('')
    try {
      const imported: CatalogSource[] = []
      for (const result of selectedResults) imported.push(await importDjSet(result.url))
      const sourceId = `dj-collection:${djQuery.trim().toLocaleLowerCase('fr').replace(/[^a-z0-9]+/g, '-')}`
      const existing = catalog.find((source) => source.id === sourceId)
      const episodes = [...imported.flatMap((source) => source.episodes), ...(existing?.episodes ?? [])]
        .filter((episode, index, items) => items.findIndex((item) => item.id === episode.id) === index)
        .slice(0, settings.maxDjEpisodes)
      const source: CatalogSource = {
        id: sourceId,
        kind: 'dj',
        title: djQuery.trim(),
        description: `${episodes.length} DJ sets importés`,
        artworkUrl: imported.find((item) => item.artworkUrl)?.artworkUrl ?? existing?.artworkUrl ?? '',
        episodes,
      }
      setCatalog((items) => [source, ...items.filter((item) => item.id !== sourceId)])
      if (settings.automaticAnalysis) {
        for (const episode of imported.flatMap((item) => item.episodes).slice(0, 3)) {
          void scheduleEpisodeAnalysis(source, episode)
        }
      }
      setSelectedSourceId(sourceId)
      setSelectedEpisodeId('')
      setShowAddSource(false)
      setActiveView('home')
      setDjResults([])
      setSelectedDjResults([])
    } catch (error) {
      setFeedError(error instanceof Error ? error.message : 'Import des sets impossible')
    } finally {
      setAddingFeed(false)
    }
  }

  function persistPlaybackState(state: Awaited<ReturnType<typeof podmixPlayer.getState>>) {
    const current = nowPlayingRef.current
    if (!current || !state.mediaId) return
    const trackQueue = trackQueueRef.current
    const favoriteItem = favoriteQueueRef.current?.[state.queueIndex]
    const activeTrack = trackQueue?.mediaIds.includes(state.mediaId) ? trackQueue.tracks[state.queueIndex] : undefined
    const historyItem = favoriteItem
      ? {
          id: favoriteItem.episode.id,
          title: favoriteItem.episode.title,
          artist: favoriteItem.source.title,
          url: favoriteItem.episode.audioUrl,
          position: favoriteItem.track.time + state.positionSeconds,
        }
      : trackQueue && activeTrack
      ? {
          id: trackQueue.episodeId,
          title: trackQueue.episodeTitle,
          artist: trackQueue.sourceTitle,
          url: trackQueue.audioUrl,
          position: activeTrack.time + state.positionSeconds,
        }
      : { ...current, position: state.positionSeconds }
    const catalogDuration = catalogRef.current
      .flatMap((source) => source.episodes)
      .find((episode) => episode.id === historyItem.id)?.duration
    const previousDuration = historyRef.current.find((item) => item.id === historyItem.id)?.duration ?? 0
    const mediaDuration = trackQueue || favoriteItem ? 0 : state.durationSeconds
    const historyDuration = Math.max(previousDuration, parseDuration(catalogDuration ?? ''), mediaDuration)
    const next = [
      { ...historyItem, ...(historyDuration > 0 ? { duration: historyDuration } : {}), playedAt: new Date().toISOString() },
      ...historyRef.current.filter((item) => item.id !== historyItem.id),
    ].slice(0, 100)
    historyRef.current = next
    localStorage.setItem('podmix-history-v1', JSON.stringify(next))
    setHistory(next)
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

  function autoplayOnCurrentOutput() {
    // A remote SoundTouch session owns playback. Preparing Media3 with autoplay
    // would start a second queue locally and let it advance while Bose buffers.
    return !boseActiveRef.current
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
      if (boseActiveRef.current && nowPlaying?.url) {
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
      setActiveMediaId(state.mediaId)
      persistPlaybackState(state)
    } catch (error) {
      setDownloadMessage(error instanceof Error ? `Positionnement impossible : ${error.message}` : 'Positionnement impossible')
    }
  }

  async function toggleCurrentPlayback() {
    if (!nowPlaying) return
    if (boseActiveRef.current) {
      await podmixPlayer.boseKey(await connectBose(), 'PLAY_PAUSE')
      const nextPlaying = !globalPlaying
      const position = currentBoseClockPosition()
      resetBoseClock(position, nextPlaying, boseClockRef.current.remotePositionSeconds)
      persistBoseClock(position, nextPlaying)
      setGlobalPosition(position)
      setGlobalPlaying(nextPlaying)
      return
    }
    const state = globalPlaying ? await podmixPlayer.pause() : await podmixPlayer.play()
    setGlobalPlaying(state.playing)
  }

  async function playEpisode(id: string, title: string, artist: string, url: string, artworkUrl?: string, resumePosition = 0, scope: NowPlayingItem['scope'] = 'episode') {
    if (!url) return
    try {
      setDownloadMessage('')
      if (nowPlaying?.id === id && nowPlaying.url === url && nowPlaying.scope === scope) {
        await toggleCurrentPlayback()
        return
      }
      trackQueueRef.current = undefined
      favoriteQueueRef.current = undefined
      const playbackPosition = Math.max(0, resumePosition)
      const state = await podmixPlayer.setQueue(
        [{ id, url, title, artist, artworkUrl }],
        0,
        autoplayOnCurrentOutput(),
        playbackPosition,
      )
      setGlobalPosition(Math.max(0, state.positionSeconds))
      setGlobalDuration(Math.max(0, state.durationSeconds))
      setActiveMediaId(state.mediaId)
      const item: NowPlayingItem = { id, title, artist, url, artworkUrl, scope }
      setNowPlaying(item); setGlobalPlaying(state.playing)
      if (boseActiveRef.current) await sendToBose(item, playbackPosition)
      if (scope !== 'radio') {
        setHistory((items) => [{ id, title, artist, url, position: playbackPosition, duration: state.durationSeconds || undefined, playedAt: new Date().toISOString() }, ...items.filter((item) => item.id !== id)].slice(0, 100))
      }
    } catch (error) {
      setDownloadMessage(error instanceof Error ? `Lecture impossible : ${error.message}` : 'Lecture impossible')
    }
  }

  async function playFromSource(source: CatalogSource, episodeId: string, startPosition?: number) {
    let playbackSource = source
    const candidate = source.episodes.find((episode) => episode.id === episodeId)
    if (source.kind === 'dj' && candidate?.sourceUrl) {
      try {
        const resolved = await importDjSet(candidate.sourceUrl)
        const fresh = resolved.episodes[0]
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
      await toggleCurrentPlayback()
      return
    }
    const resume = historyRef.current.find((item) => item.id === episode.id)?.position ?? 0
    const playbackPosition = Math.max(0, startPosition ?? resume)
    const queue = settings.continuousPlayback && source.kind !== 'dj' ? playable : [episode]
    const queueIndex = settings.continuousPlayback && source.kind !== 'dj' ? index : 0
    try {
      trackQueueRef.current = undefined
      favoriteQueueRef.current = undefined
      setDownloadMessage('')
      const state = await podmixPlayer.setQueue(
        queue.map((item) => ({
          id: item.id,
          url: item.audioUrl,
          title: item.title,
          artist: source.title,
          artworkUrl: item.artworkUrl || source.artworkUrl,
        })),
        queueIndex,
        autoplayOnCurrentOutput(),
        playbackPosition,
      )
      setGlobalPosition(Math.max(0, state.positionSeconds))
      setGlobalDuration(Math.max(0, state.durationSeconds))
      setActiveMediaId(state.mediaId)
      const item: NowPlayingItem = { id: episode.id, title: episode.title, artist: source.title, url: episode.audioUrl, artworkUrl: episode.artworkUrl || source.artworkUrl, scope: 'episode' }
      setNowPlaying(item)
      setGlobalPlaying(state.playing)
      if (boseActiveRef.current) {
        await sendToBose(item, playbackPosition)
      }
      setHistory((items) => [{ id: episode.id, title: episode.title, artist: source.title, url: episode.audioUrl, position: playbackPosition, duration: Math.max(parseDuration(episode.duration), state.durationSeconds) || undefined, playedAt: new Date().toISOString() }, ...items.filter((item) => item.id !== episode.id)].slice(0, 100))
    } catch (error) {
      setDownloadMessage(error instanceof Error ? `Lecture impossible : ${error.message}` : 'Lecture impossible')
    }
  }

  async function playTrackFromSource(source: CatalogSource, episode: Episode, trackIndex: number) {
    const tracklist = episode.tracks ?? []
    const selectedTrack = tracklist[trackIndex]
    if (!episode.audioUrl || !selectedTrack) return
    const savedPosition = historyRef.current.find((item) => item.id === episode.id)?.position ?? 0
    const trackStartTime = selectedTrack.time
    const nextTrackTime = tracklist[trackIndex + 1]?.time ?? Infinity
    const isResume = savedPosition > 0 && savedPosition >= trackStartTime && savedPosition < nextTrackTime
    const playbackPosition = isResume ? savedPosition : undefined
    const queue = tracklist.map((track, index) => {
      const nextTime = tracklist[index + 1]?.time
      return {
        id: favoriteTrackKey(episode.id, track, index),
        url: episode.audioUrl,
        title: track.title,
        artist: track.artist,
        artworkUrl: trackArtwork(track, episode, source),
        startPositionSeconds: Math.max(0, track.time),
        ...(nextTime > track.time ? { endPositionSeconds: nextTime } : {}),
      }
    })
    favoriteQueueRef.current = undefined
    trackQueueRef.current = {
      episodeId: episode.id,
      episodeTitle: episode.title,
      sourceTitle: source.title,
      audioUrl: episode.audioUrl,
      tracks: tracklist,
      mediaIds: queue.map((item) => item.id),
      artworkUrls: queue.map((item) => item.artworkUrl),
    }
    try {
      setDownloadMessage('')
      const state = await podmixPlayer.setQueue(queue, trackIndex, autoplayOnCurrentOutput(), playbackPosition)
      setNowPlaying({
        id: episode.id,
        title: selectedTrack.title,
        artist: selectedTrack.artist,
        url: episode.audioUrl,
        scope: 'track',
        artworkUrl: trackArtwork(selectedTrack, episode, source),
      })
      setActiveMediaId(state.mediaId)
      setGlobalPlaying(state.playing)
      setGlobalPosition(Math.max(0, state.positionSeconds))
      setGlobalDuration(
        tracklist[trackIndex + 1]?.time > selectedTrack.time
          ? tracklist[trackIndex + 1].time - selectedTrack.time
          : Math.max(0, parseDuration(episode.duration) - selectedTrack.time),
      )
      if (boseActiveRef.current) {
        await sendToBose({
          id: episode.id,
          title: selectedTrack.title,
          artist: selectedTrack.artist,
          url: episode.audioUrl,
          scope: 'track',
          artworkUrl: trackArtwork(selectedTrack, episode, source),
        }, selectedTrack.time, selectedTrack.time)
      }
      setHistory((items) => [{
        id: episode.id,
        title: episode.title,
        artist: source.title,
        url: episode.audioUrl,
        position: selectedTrack.time,
        duration: parseDuration(episode.duration) || undefined,
        playedAt: new Date().toISOString(),
      }, ...items.filter((item) => item.id !== episode.id)].slice(0, 100))
    } catch (error) {
      trackQueueRef.current = undefined
      setDownloadMessage(error instanceof Error ? `Lecture du titre impossible : ${error.message}` : 'Lecture du titre impossible')
    }
  }

  async function playFavoriteTracks(startIndex: number) {
    const requestedEntry = favoriteTrackEntries[startIndex]
    const entries = favoriteTrackEntries.filter((entry) => entry.episode.audioUrl)
    const selectedIndex = requestedEntry
      ? entries.findIndex((entry) => entry.key === requestedEntry.key)
      : 0
    const selectedEntry = entries[Math.max(0, selectedIndex)]
    if (!selectedEntry) return
    const queue = entries.map((entry) => {
      const tracks = entry.episode.tracks ?? []
      const nextTime = tracks[entry.trackIndex + 1]?.time
      return {
        id: entry.key,
        url: entry.episode.audioUrl,
        title: entry.track.title,
        artist: entry.track.artist || entry.source.title,
        artworkUrl: trackArtwork(entry.track, entry.episode, entry.source),
        startPositionSeconds: Math.max(0, entry.track.time),
        ...(nextTime !== undefined && nextTime > entry.track.time
          ? { endPositionSeconds: nextTime }
          : {}),
      }
    })
    trackQueueRef.current = undefined
    favoriteQueueRef.current = entries
    try {
      setDownloadMessage('')
      const state = await podmixPlayer.setQueue(queue, Math.max(0, selectedIndex), autoplayOnCurrentOutput())
      setNowPlaying({
        id: selectedEntry.episode.id,
        title: selectedEntry.track.title,
        artist: selectedEntry.track.artist || selectedEntry.source.title,
        url: selectedEntry.episode.audioUrl,
        scope: 'favorite',
        artworkUrl: trackArtwork(selectedEntry.track, selectedEntry.episode, selectedEntry.source),
      })
      setActiveMediaId(state.mediaId)
      setGlobalPlaying(state.playing)
      setGlobalPosition(0)
      const selectedTracks = selectedEntry.episode.tracks ?? []
      const nextSelectedTime = selectedTracks[selectedEntry.trackIndex + 1]?.time
      setGlobalDuration(
        nextSelectedTime > selectedEntry.track.time
          ? nextSelectedTime - selectedEntry.track.time
          : Math.max(0, parseDuration(selectedEntry.episode.duration) - selectedEntry.track.time),
      )
      if (boseActiveRef.current) {
        await sendToBose({
          id: selectedEntry.episode.id,
          title: selectedEntry.track.title,
          artist: selectedEntry.track.artist || selectedEntry.source.title,
          url: selectedEntry.episode.audioUrl,
          scope: 'favorite',
          artworkUrl: trackArtwork(selectedEntry.track, selectedEntry.episode, selectedEntry.source),
        }, selectedEntry.track.time, selectedEntry.track.time)
      }
    } catch (error) {
      favoriteQueueRef.current = undefined
      setDownloadMessage(error instanceof Error ? `Lecture des favoris impossible : ${error.message}` : 'Lecture des favoris impossible')
    }
  }

  async function skip(direction: 'next' | 'previous') {
    const state = direction === 'next' ? await podmixPlayer.next() : await podmixPlayer.previous()
    setActiveMediaId(state.mediaId)
    setGlobalPlaying(state.playing)
    if (state.title) {
      const favoriteItem = favoriteQueueRef.current?.[state.queueIndex]
      const trackQueue = trackQueueRef.current
      const activeTrack = trackQueue?.mediaIds.includes(state.mediaId) ? trackQueue.tracks[state.queueIndex] : undefined
      const queued = catalog.flatMap((source) => source.episodes.map((episode) => ({ ...episode, artist: source.title }))).find((episode) => episode.id === state.mediaId)
      const nextPlaying = {
        id: favoriteItem?.episode.id ?? (activeTrack ? trackQueue!.episodeId : state.mediaId) ?? queued?.id ?? `${state.artist}:${state.title}`,
        title: favoriteItem?.track.title ?? activeTrack?.title ?? state.title,
        artist: favoriteItem?.track.artist ?? activeTrack?.artist ?? state.artist,
        url: favoriteItem?.episode.audioUrl ?? trackQueue?.audioUrl ?? queued?.audioUrl ?? '',
        scope: favoriteItem ? 'favorite' as const : activeTrack ? 'track' as const : 'episode' as const,
        artworkUrl: favoriteItem
          ? trackArtwork(favoriteItem.track, favoriteItem.episode, favoriteItem.source)
          : activeTrack
            ? trackQueue?.artworkUrls[state.queueIndex]
            : queued?.artworkUrl,
      }
      setNowPlaying(nextPlaying)
      if (boseActiveRef.current && nextPlaying.url) {
        const contentOffset = favoriteItem?.track.time ?? activeTrack?.time ?? 0
        const absolutePosition = contentOffset + state.positionSeconds
        await sendToBose(nextPlaying, absolutePosition, contentOffset)
      }
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
    } catch (error) {
      setDownloadMessage(error instanceof Error ? error.message : 'Téléchargement impossible')
    }
  }

  async function downloadSourceEpisode(source: CatalogSource, episode: Episode) {
    let downloadUrl = episode.audioUrl
    if (source.kind === 'dj' && episode.sourceUrl) {
      try {
        const refreshed = await importDjSet(episode.sourceUrl)
        downloadUrl = refreshed.episodes[0]?.audioUrl || downloadUrl
        if (downloadUrl) {
          setCatalog((items) => items.map((item) => item.id !== source.id ? item : {
            ...item,
            episodes: item.episodes.map((entry) => entry.id === episode.id ? { ...entry, audioUrl: downloadUrl } : entry),
          }))
        }
      } catch (error) {
        setDownloadMessage(error instanceof Error ? error.message : 'Flux DJ expiré')
        return
      }
    }
    await downloadEpisode(episode.id, episode.title, downloadUrl, source.title)
  }

  async function playOffline(episode: OfflineEpisode) {
    const url = episode.localUri || episode.remoteUrl
    const state = await podmixPlayer.load({ url, title: episode.title, artist: episode.artist, autoplay: true })
    setNowPlaying({ id: episode.id, title: episode.title, artist: episode.artist, url })
    setActiveMediaId(state.mediaId)
    setGlobalPlaying(state.playing)
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
      const [castResult, boseResult, castStateResult] = await Promise.allSettled([
        podmixPlayer.discoverCastDevices(),
        podmixPlayer.boseDiscover(),
        podmixPlayer.getCastState(),
      ])
      const castState = castStateResult.status === 'fulfilled' ? castStateResult.value : { connected: false }
      const casts: OutputDevice[] = castResult.status === 'fulfilled'
        ? castResult.value.devices.map((device) => ({
            ...device,
            kind: 'cast' as const,
            connected: Boolean(device.connected || (castState.connected && device.name === castState.deviceName)),
          }))
        : []
      const boseDevices: OutputDevice[] = boseResult.status === 'fulfilled'
        ? boseResult.value.devices.map((device) => ({
            ...device,
            kind: 'bose' as const,
            connected: boseActiveRef.current && device.ip === boseIp,
          }))
        : []
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
      const favoriteItem = favoriteQueueRef.current?.[playerState.queueIndex]
      const trackQueue = trackQueueRef.current
      const trackIndex = trackQueue?.mediaIds.indexOf(playerState.mediaId) ?? -1
      const activeTrack = trackIndex >= 0 ? trackQueue?.tracks[trackIndex] : undefined
      const nextTrack = trackIndex >= 0 ? trackQueue?.tracks[trackIndex + 1] : undefined
      const positionOffsetSeconds = favoriteItem?.track.time ?? activeTrack?.time ?? 0
      const favoriteNextTrack = favoriteItem?.episode.tracks?.[favoriteItem.trackIndex + 1]
      const endPositionSeconds = favoriteNextTrack?.time ?? nextTrack?.time
      const result = await podmixPlayer.cast({
        url: nowPlaying.url,
        title: nowPlaying.title,
        artist: nowPlaying.artist,
        artworkUrl: nowPlaying.artworkUrl,
        positionSeconds: positionOffsetSeconds + playerState.positionSeconds,
        positionOffsetSeconds,
        ...(endPositionSeconds !== undefined ? { endPositionSeconds } : {}),
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
      const savedIp = boseIp.trim()
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

  function inferBoseSession(
    ip: string,
    name: string,
    title: string,
    speakerPositionSeconds: number,
  ): StoredBoseSession | undefined {
    const normalizedTitle = title.trim().toLocaleLowerCase('fr')
    if (!normalizedTitle) return undefined
    for (const source of catalogRef.current) {
      for (const episode of source.episodes) {
        const historyPosition = historyRef.current.find((entry) => entry.id === episode.id)?.position ?? 0
        const trackIndex = (episode.tracks ?? []).findIndex(
          (track) => track.title.trim().toLocaleLowerCase('fr') === normalizedTitle,
        )
        if (trackIndex >= 0) {
          const track = episode.tracks![trackIndex]
          const nextTrack = episode.tracks![trackIndex + 1]
          const contentOffsetSeconds = Math.max(0, track.time)
          return {
            ip,
            name,
            item: {
              id: episode.id,
              title: track.title,
              artist: track.artist || source.title,
              url: episode.audioUrl,
              scope: 'track',
              artworkUrl: trackArtwork(track, episode, source),
            },
            contentOffsetSeconds,
            streamStartSeconds: Math.max(
              contentOffsetSeconds,
              historyPosition - Math.max(0, speakerPositionSeconds),
            ),
            durationSeconds: nextTrack?.time > track.time ? nextTrack.time - track.time : 0,
            savedAt: Date.now(),
          }
        }
        if (episode.title.trim().toLocaleLowerCase('fr') === normalizedTitle) {
          return {
            ip,
            name,
            item: {
              id: episode.id,
              title: episode.title,
              artist: source.title,
              url: episode.audioUrl,
              scope: 'episode',
              artworkUrl: episode.artworkUrl || source.artworkUrl,
            },
            contentOffsetSeconds: 0,
            streamStartSeconds: Math.max(0, historyPosition - Math.max(0, speakerPositionSeconds)),
            durationSeconds: parseDuration(episode.duration),
            savedAt: Date.now(),
          }
        }
      }
    }
    return undefined
  }

  async function restoreBoseSession() {
    let stored = loadBoseSession()
    try {
      if (!stored) {
        const ip = await connectBose(true)
        if (!ip) return
        const current = await podmixPlayer.boseGetState(ip)
        if (current.source !== 'UPNP' || !current.location?.includes('/podmix-cast/')) return
        stored = inferBoseSession(
          ip,
          current.name || 'Bose SoundTouch',
          current.title || '',
          current.positionSeconds ?? 0,
        )
        if (!stored) return
        localStorage.setItem('podmix-bose-session-v1', JSON.stringify(stored))
      }
      const state = await podmixPlayer.boseGetState(stored.ip)
      const stillPodmix = state.source === 'UPNP' && (
        !state.location
        || state.location.includes('/podmix-cast/')
        || state.title === stored.item.title
      )
      if (!stillPodmix) {
        localStorage.removeItem('podmix-bose-session-v1')
        await connectBose(true)
        return
      }
      const reportedPosition = Math.max(0, state.positionSeconds ?? 0)
      const speakerPosition = Math.max(
        0,
        stored.streamStartSeconds + reportedPosition - stored.contentOffsetSeconds,
      )
      const storedPosition = Math.max(0, stored.positionSeconds ?? speakerPosition)
      const elapsedSinceSave = state.playing && stored.playing !== false && stored.savedAt > 0
        ? Math.max(0, (Date.now() - stored.savedAt) / 1000)
        : 0
      let relativePosition = reportedPosition > 0
        ? speakerPosition
        : Math.max(speakerPosition, storedPosition + elapsedSinceSave)
      let restoredItem = stored.item
      let restoredContentOffset = stored.contentOffsetSeconds
      let restoredDuration = stored.durationSeconds
      const source = catalogRef.current.find((entry) => entry.episodes.some((episode) => episode.id === stored!.item.id))
      const episode = source?.episodes.find((entry) => entry.id === stored!.item.id)
      const episodeTracks = episode?.tracks ?? []
      const absolutePosition = stored.contentOffsetSeconds + relativePosition
      const trackIndex = episodeTracks.reduce(
        (activeIndex, track, index) => absolutePosition >= track.time ? index : activeIndex,
        -1,
      )
      let localState: Awaited<ReturnType<typeof podmixPlayer.getState>>
      if (source && episode && episode.audioUrl && trackIndex >= 0) {
        const queue = episodeTracks.map((track, index) => {
          const nextTime = episodeTracks[index + 1]?.time
          return {
            id: favoriteTrackKey(episode.id, track, index),
            url: episode.audioUrl,
            title: track.title,
            artist: track.artist,
            artworkUrl: trackArtwork(track, episode, source),
            startPositionSeconds: Math.max(0, track.time),
            ...(nextTime > track.time ? { endPositionSeconds: nextTime } : {}),
          }
        })
        trackQueueRef.current = {
          episodeId: episode.id,
          episodeTitle: episode.title,
          sourceTitle: source.title,
          audioUrl: episode.audioUrl,
          tracks: episodeTracks,
          mediaIds: queue.map((item) => item.id),
          artworkUrls: queue.map((item) => item.artworkUrl),
        }
        const track = episodeTracks[trackIndex]
        const nextTrack = episodeTracks[trackIndex + 1]
        restoredContentOffset = track.time
        relativePosition = Math.max(0, absolutePosition - track.time)
        restoredDuration = nextTrack?.time > track.time
          ? nextTrack.time - track.time
          : Math.max(0, parseDuration(episode.duration) - track.time)
        restoredItem = {
          id: episode.id,
          title: track.title,
          artist: track.artist || source.title,
          url: episode.audioUrl,
          scope: 'track',
          artworkUrl: trackArtwork(track, episode, source),
        }
        localState = await podmixPlayer.setQueue(queue, trackIndex, false, relativePosition)
      } else {
        trackQueueRef.current = undefined
        favoriteQueueRef.current = undefined
        localState = await podmixPlayer.setQueue([{
          id: restoredItem.id,
          url: restoredItem.url,
          title: restoredItem.title,
          artist: restoredItem.artist,
          artworkUrl: restoredItem.artworkUrl,
        }], 0, false, relativePosition)
      }
      stored = {
        ...stored,
        item: restoredItem,
        contentOffsetSeconds: restoredContentOffset,
        durationSeconds: restoredDuration,
        positionSeconds: relativePosition,
        playing: Boolean(state.playing),
        savedAt: Date.now(),
      }
      boseStartPositionRef.current = stored.streamStartSeconds
      boseContentOffsetRef.current = restoredContentOffset
      bosePersistedAtRef.current = 0
      boseActiveRef.current = true
      resetBoseClock(relativePosition, Boolean(state.playing), reportedPosition)
      setBoseIp(stored.ip)
      setBoseVolume(state.volume ?? 30)
      setNowPlaying(restoredItem)
      setActiveMediaId(localState.mediaId || restoredItem.id)
      setGlobalPosition(relativePosition)
      setGlobalDuration(Math.max(restoredDuration, localState.durationSeconds))
      setGlobalPlaying(Boolean(state.playing))
      setActiveOutput({ kind: 'bose', id: stored.ip, name: stored.name })
      persistBoseClock(relativePosition, Boolean(state.playing), restoredItem, restoredDuration)
      setBoseMessage(`Session retrouvée · ${stored.name}`)
    } catch {
      localStorage.removeItem('podmix-bose-session-v1')
      boseActiveRef.current = false
      await connectBose(true)
    }
  }

  async function sendToBose(
    item: NowPlayingItem,
    absolutePositionSeconds: number,
    contentOffsetSeconds = 0,
    targetIp?: string,
    targetName?: string,
  ) {
    const ip = targetIp || await connectBose()
    setBoseIp(ip)
    setBoseMessage('Vérification du flux pour l’enceinte…')
    const canTryDirect = /^http:\/\//i.test(item.url) && !/\.m3u8(?:$|[?#])/i.test(item.url)
    let streamStart = 0
    let direct = false
    if (canTryDirect) {
      try {
        await podmixPlayer.bosePlay(ip, item.url, item.title, Math.floor(absolutePositionSeconds))
        await new Promise((resolve) => window.setTimeout(resolve, 1200))
        const state = await podmixPlayer.boseGetState(ip)
        const reportedPosition = Math.max(0, state.positionSeconds ?? 0)
        direct = Boolean(state.playing) && (
          absolutePositionSeconds <= 2
          || Math.abs(reportedPosition - absolutePositionSeconds) <= 20
        )
      } catch {
        direct = false
      }
    }
    if (!direct) {
      await podmixPlayer.boseKey(ip, 'STOP').catch(() => undefined)
      setBoseMessage('Adaptation du flux pour l’enceinte…')
      const sourceDuration = knownSourceDuration(item)
      const relay = await createBoseCastSession(item.url, item.title, absolutePositionSeconds, sourceDuration)
      await podmixPlayer.bosePlay(ip, relay.relayUrl, item.title, 0)
      streamStart = relay.startSeconds
    }
    const localState = await podmixPlayer.getState()
    boseStartPositionRef.current = streamStart
    boseContentOffsetRef.current = Math.max(0, contentOffsetSeconds)
    bosePersistedAtRef.current = 0
    boseActiveRef.current = true
    const relativePosition = Math.max(0, absolutePositionSeconds - contentOffsetSeconds)
    resetBoseClock(relativePosition, true)
    await podmixPlayer.pause()
    setGlobalPosition(relativePosition)
    setGlobalPlaying(true)
    const name = targetName || activeOutput.name || 'Bose SoundTouch'
    setActiveOutput({ kind: 'bose', id: ip, name })
    localStorage.setItem('podmix-bose-session-v1', JSON.stringify({
      ip,
      name,
      item,
      contentOffsetSeconds: Math.max(0, contentOffsetSeconds),
      streamStartSeconds: streamStart,
      durationSeconds: Math.max(0, localState.durationSeconds),
      positionSeconds: relativePosition,
      playing: true,
      savedAt: Date.now(),
    } satisfies StoredBoseSession))
    setBoseMessage(`${direct ? 'Flux direct' : 'Flux adapté'} · ${name}`)
  }

  async function playOnBose(target?: BoseDevice) {
    if (!nowPlaying?.url) {
      setBoseMessage('Aucun média à envoyer')
      return
    }
    try {
      const playerState = await podmixPlayer.getState()
      const favoriteItem = favoriteQueueRef.current?.[playerState.queueIndex]
      const trackQueue = trackQueueRef.current
      const trackIndex = trackQueue?.mediaIds.indexOf(playerState.mediaId) ?? -1
      const activeTrack = trackIndex >= 0 ? trackQueue?.tracks[trackIndex] : undefined
      const contentOffset = favoriteItem?.track.time ?? activeTrack?.time ?? 0
      const absolutePosition = contentOffset + playerState.positionSeconds
      await sendToBose(nowPlaying, absolutePosition, contentOffset, target?.ip, target?.name)
    } catch (error) {
      setBoseMessage(error instanceof Error ? error.message : 'Envoi SoundTouch impossible')
    }
  }

  async function returnPlaybackToPhone() {
    if (boseActiveRef.current) {
      const state = await podmixPlayer.boseGetState(boseIp.trim())
      const relativePosition = syncBoseClock(state.positionSeconds ?? 0, Boolean(state.playing))
      await podmixPlayer.boseKey(boseIp.trim(), 'STOP').catch(() => undefined)
      boseActiveRef.current = false
      resetBoseClock(relativePosition, false)
      localStorage.removeItem('podmix-bose-session-v1')
      const localState = await podmixPlayer.seekTo(relativePosition)
      const resumed = state.playing ? await podmixPlayer.play() : await podmixPlayer.pause()
      setGlobalPosition(relativePosition)
      setGlobalDuration(Math.max(0, localState.durationSeconds))
      setGlobalPlaying(resumed.playing)
      persistPlaybackState({ ...resumed, positionSeconds: relativePosition })
    } else {
      const castState = await podmixPlayer.getCastState()
      if (castState.connected) {
        const state = await podmixPlayer.disconnectCast()
        setGlobalPosition(Math.max(0, state.positionSeconds))
        setGlobalDuration(Math.max(0, state.durationSeconds))
        setGlobalPlaying(state.playing)
        persistPlaybackState(state)
      }
    }
    setActiveOutput({ kind: 'phone', id: 'phone', name: 'Ce téléphone' })
    setCastMessage('Lecture sur ce téléphone')
    setBoseMessage('')
  }

  async function selectOutputDevice(device: OutputDevice) {
    setConnectingOutputId(device.kind === 'bose' ? device.ip : device.id)
    try {
      if (device.kind === 'phone') {
        await returnPlaybackToPhone()
      } else if (device.kind === 'bose') {
        const castState = await podmixPlayer.getCastState()
        if (castState.connected) await podmixPlayer.disconnectCast()
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
      if (device.kind === 'bose') setBoseMessage(message)
      else setCastMessage(message)
    } finally {
      setConnectingOutputId('')
    }
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
        history?: HistoryItem[]; offlineEpisodes?: OfflineEpisode[]; settings?: AppSettings
        studio?: { audioName?: string; tracks?: Track[] }
      }
      if (payload.format !== 'podmix-next-backup' || !Array.isArray(payload.catalog)) throw new Error('Format de sauvegarde invalide')
      setCatalog((current) => [...payload.catalog!, ...current.filter((source) => !payload.catalog!.some((imported) => imported.id === source.id))])
      setFavoriteTrackIds((payload.favoriteTrackIds ?? []).map(String))
      setHistory(payload.history ?? [])
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
      setCatalog((items) => [source, ...items.filter((item) => item.id !== source.id)])
      if (settings.automaticAnalysis && isAutomaticMusicSource(source) && source.episodes[0]) {
        void scheduleEpisodeAnalysis(source, source.episodes[0])
      }
      setPodcastResults([]); setShowAddSource(false); setSelectedSourceId(source.id); setActiveView('home')
    } catch (error) {
      setFeedError(error instanceof Error ? error.message : 'Podcast indisponible')
    } finally {
      setAddingFeed(false)
    }
  }

  async function refreshSelectedSource() {
    if (!selectedSource?.feedUrl) return
    setRefreshingSource(true); setFeedError('')
    try {
      const refreshed = selectedSource.kind === 'dj'
        ? await importDjSet(selectedSource.feedUrl)
        : await importRssFeed(selectedSource.feedUrl, selectedSource.kind === 'show' ? 'show' : 'podcast', selectedSource.kind === 'show' ? settings.maxShowEpisodes : settings.maxPodcastEpisodes)
      const merged = mergeCatalogSource(selectedSource, refreshed)
      setCatalog((items) => items.map((source) => source.id === selectedSource.id ? merged : source))
      if (settings.automaticAnalysis && isAutomaticMusicSource(merged)) {
        const previousIds = new Set(selectedSource.episodes.map((episode) => episode.id))
        const newest = merged.episodes.find((episode) => episode.audioUrl && !previousIds.has(episode.id))
        if (newest) void scheduleEpisodeAnalysis(merged, newest)
      }
      setDownloadMessage('Source actualisée')
    } catch (error) {
      setDownloadMessage(error instanceof Error ? error.message : 'Actualisation impossible')
    } finally {
      setRefreshingSource(false)
    }
  }

  function removeSelectedSource() {
    if (!selectedSource) return
    setCatalog((items) => items.filter((source) => source.id !== selectedSource.id))
    setSelectedSourceId('')
    setSelectedEpisodeId('')
  }

  async function openEpisodeInStudio() {
    if (!selectedEpisode || !selectedSource) return
    trackQueueRef.current = undefined
    favoriteQueueRef.current = undefined
    waveRef.current?.destroy()
    waveRef.current = null
    setHasLocalAudio(false)
    setUploadId('')
    setCurrentJobId('')
    setCurrentTime(0)
    setDuration(parseDuration(selectedEpisode.duration))
    setAudioName(selectedEpisode.title)
    const target = { sourceId: selectedSource.id, episodeId: selectedEpisode.id }
    setStudioEpisode(target)
    studioEpisodeRef.current = target
    if (selectedEpisode.tracks?.length) {
      setTracks(selectedEpisode.tracks)
      setSelectedId(selectedEpisode.tracks[0].id)
    } else {
      setTracks([])
      setSelectedId(0)
    }
    setActiveView('studio')
    if (!selectedEpisode.audioUrl) {
      setDetectionError('Cet épisode ne contient aucune URL audio')
      return
    }
    try {
      const state = await podmixPlayer.setQueue([{
        id: selectedEpisode.id,
        url: selectedEpisode.audioUrl,
        title: selectedEpisode.title,
        artist: selectedSource.title,
        artworkUrl: selectedEpisode.artworkUrl || selectedSource.artworkUrl,
      }], 0, false)
      setNowPlaying({ id: selectedEpisode.id, title: selectedEpisode.title, artist: selectedSource.title, url: selectedEpisode.audioUrl, artworkUrl: selectedEpisode.artworkUrl || selectedSource.artworkUrl })
      if (state.durationSeconds > 0) setDuration(state.durationSeconds)
      setDetectionError('')
      if (['queued', 'running'].includes(selectedEpisode.analysis?.status ?? '') && selectedEpisode.analysis?.jobId) {
        try {
          watchDetectionJob(await getDetectionJob(selectedEpisode.analysis.jobId), target)
        } catch {
          void startDetection(undefined, target)
        }
      } else if (settings.automaticAnalysis && !selectedEpisode.tracks?.length) {
        void startDetection(undefined, target)
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

  function toggleMusicalSource(source: CatalogSource) {
    if (!['podcast', 'show'].includes(source.kind)) return
    const musical = !source.musical
    const updated = { ...source, musical }
    setCatalog((items) => items.map((item) => item.id === source.id ? updated : item))
    if (musical && settings.automaticAnalysis && source.episodes[0]?.audioUrl) {
      void scheduleEpisodeAnalysis(updated, source.episodes[0])
    }
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
      setServerMessage(`Connecté · ${health.engine}`)
    } catch (error) {
      setServerMessage(error instanceof Error ? error.message : 'Serveur inaccessible')
    }
  }

  const selectedSource = catalog.find((source) => source.id === selectedSourceId)
  const selectedEpisode = selectedSource?.episodes.find((episode) => episode.id === selectedEpisodeId)
  const playbackHistoryById = new Map(history.map((item) => [item.id, item]))
  const playbackStatusFor = (episode: Episode) => {
    const isCurrentEpisode = nowPlaying?.id === episode.id && activeMediaId === episode.id
    const position = isCurrentEpisode
      ? globalPosition
      : playbackHistoryById.get(episode.id)?.position ?? 0
    return episodePlaybackStatus(episode, position, isCurrentEpisode ? globalDuration : 0)
  }
  const resumeEpisodes = history.flatMap((item) => {
    const source = catalog.find((candidate) => candidate.episodes.some((episode) => episode.id === item.id))
    const episode = source?.episodes.find((candidate) => candidate.id === item.id)
    if (!source || !episode || !['podcast', 'show'].includes(source.kind)) return []
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
  const favoriteTrackGroups = favoriteTrackEntries.reduce<Array<{ source: CatalogSource; entries: FavoriteTrackEntry[] }>>((groups, entry) => {
    const current = groups.find((group) => group.source.id === entry.source.id)
    if (current) current.entries.push(entry)
    else groups.push({ source: entry.source, entries: [entry] })
    return groups
  }, [])
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
    ['queued', 'running'].includes(episode.analysis?.status ?? ''))
  const completedAnalyses = analyzedEpisodes.filter(({ episode }) =>
    episode.analysis?.status === 'completed')
  const failedAnalyses = analyzedEpisodes.filter(({ episode }) =>
    episode.analysis?.status === 'failed')

  function openNowPlayingEpisode() {
    if (!nowPlaying) return
    if (nowPlaying.scope === 'radio') {
      const source = catalog.find((source) => source.id === nowPlaying.id)
      if (source) {
        setSelectedSourceId(source.id)
        setSelectedEpisodeId('')
      }
      return
    }
    const episodeId = nowPlaying.id
    for (const source of catalog) {
      const episode = source.episodes.find((episode) => episode.id === episodeId)
      if (episode) {
        setSelectedSourceId(source.id)
        setSelectedEpisodeId(episode.id)
        return
      }
    }
  }

  function renderCatalogSection(sectionId: HomeSectionId, title: string, Icon: typeof Mic2, sources: CatalogSource[]) {
    return <section key={sectionId} className={`home-section ${dragSection === sectionId ? 'dragging' : ''}`}>
      <div className="section-heading" onPointerDown={(e) => { if (e.pointerType === 'touch' || e.pointerType === 'pen') handleSectionDragStart(sectionId, e.clientY) }} onTouchStart={(e) => handleSectionDragStart(sectionId, e.touches[0].clientY)}><div><Icon size={17} /><h2>{title}</h2></div><span>{sources.length}</span></div>
      <div className="catalog-grid home-catalog-grid">{sources.slice(0, 4).map((source) => {
        const SourceIcon = source.kind === 'radio' ? Radio : source.kind === 'dj' ? Disc3 : source.kind === 'show' ? AudioLines : Mic2
        const color = source.kind === 'radio' ? '#8ab4ff' : source.kind === 'show' ? '#c9f46f' : source.kind === 'dj' ? '#f3c95e' : '#ff6940'
        const firstEpisode = source.episodes.find((episode) => episode.audioUrl)
        const isRadio = source.kind === 'radio'
        const playSource = async () => {
          if (isRadio && source.streamUrl) {
            await playEpisode(source.id, source.title, 'Radio en direct', source.streamUrl, source.artworkUrl, 0, 'radio')
          } else if (firstEpisode) {
            await playFromSource(source, firstEpisode.id)
          } else {
            setDownloadMessage('Aucun épisode audio disponible dans cette source')
          }
        }
        return <article className="media-card" key={source.id} onClick={() => !isRadio && setSelectedSourceId(source.id)}>
          <div className="media-art" style={{ '--card-accent': color } as React.CSSProperties}>{source.artworkUrl ? <img src={source.artworkUrl} alt="" /> : <SourceIcon size={34} />}<button aria-label={`Lire ${source.title}`} onClick={(event) => { event.stopPropagation(); void playSource() }}><Play size={18} fill="currentColor" /></button></div>
          <span>{sourceKindLabel(source)}</span><h2>{source.title}</h2><p>{source.kind === 'radio' ? source.description : `${source.episodes.length} épisodes`}</p>
        </article>
      })}</div>
    </section>
  }

  return (
    <div className="app-shell">
      <main>
        <header className="topbar">
          <div className="brand"><div className="brand-mark"><AudioLines size={22} /></div><span>podmix</span></div>
          <div className="top-actions">
            <button className="search" aria-label="Rechercher" onClick={() => setShowSearch(true)}><Search size={18} /></button>
            <button aria-label="Ajouter une source" onClick={() => setShowAddSource(true)}><Plus size={20} /></button>
            <button aria-label="Voir les favoris" className={activeView === 'favorites' ? 'active' : ''} onClick={() => navigateTo(activeView === 'favorites' ? 'home' : 'favorites')}><Heart size={18} fill={activeView === 'favorites' ? 'currentColor' : 'none'} /></button>
            <button aria-label="Réglages" className={activeView === 'settings' ? 'active' : ''} onClick={() => navigateTo(activeView === 'settings' ? 'home' : 'settings')}><Settings2 size={18} /></button>
          </div>
        </header>

        {activeView !== 'studio' && <section className="workspace catalog-view">
          {!selectedSource && activeView !== 'home' && activeView !== 'favorites' && <div className="catalog-hero">
            <span className="eyebrow"><i /> Collection personnelle</span>
            <h1>{viewLabels[activeView]}</h1>
            <p>{activeView === 'settings' ? 'Lecture, automatisation, stockage et appareils.' : activeView === 'favorites' ? 'Les sources et morceaux que vous avez gardés.' : 'Tous vos podcasts, émissions, radios et DJ sets au même endroit.'}</p>
          </div>}
          {activeView === 'settings' ? <div className="settings-grid">
            <section><h2>Lecture</h2><label><span>Lecture continue<small>Enchaîner automatiquement les épisodes</small></span><input type="checkbox" checked={settings.continuousPlayback} onChange={(event) => changeSetting('continuousPlayback', event.target.checked)} /></label><label><span>Qualité mobile<small>Réduire la consommation de données</small></span><input type="checkbox" checked={settings.mobileQuality} onChange={(event) => changeSetting('mobileQuality', event.target.checked)} /></label></section>
            <section><h2>Détection</h2><label><span>Analyse automatique<small>Rechercher une tracklist après import</small></span><input type="checkbox" checked={settings.automaticAnalysis} onChange={(event) => changeSetting('automaticAnalysis', event.target.checked)} /></label><label><span>Validation MusicBrainz<small>Confirmer artiste et titre</small></span><input type="checkbox" checked={settings.musicBrainzValidation} onChange={(event) => changeSetting('musicBrainzValidation', event.target.checked)} /></label></section>
            <section><h2>Limites du catalogue</h2><label className="limit-setting"><span>Podcasts<small>{settings.maxPodcastEpisodes} épisodes par source</small></span><input type="range" min="10" max="500" step="10" value={settings.maxPodcastEpisodes} onChange={(event) => changeSetting('maxPodcastEpisodes', Number(event.target.value))} /></label><label className="limit-setting"><span>Émissions<small>{settings.maxShowEpisodes} épisodes par source</small></span><input type="range" min="10" max="500" step="10" value={settings.maxShowEpisodes} onChange={(event) => changeSetting('maxShowEpisodes', Number(event.target.value))} /></label><label className="limit-setting"><span>DJ sets<small>{settings.maxDjEpisodes} sets par DJ</small></span><input type="range" min="10" max="200" step="10" value={settings.maxDjEpisodes} onChange={(event) => changeSetting('maxDjEpisodes', Number(event.target.value))} /></label></section>
            <section><h2>Appareils</h2><button className="device-row" onClick={openOutputPicker}><Cast size={18} /><span>Sortie audio<small>{activeOutput.name}</small></span><ChevronDown size={16} /></button></section>
            <section><h2>Données</h2><p className="settings-copy">Exportez une sauvegarde ou importez le JSON produit par le convertisseur Room.</p><div className="backup-actions"><button onClick={exportBackup}><Download size={15} /> Exporter</button><button onClick={() => backupInputRef.current?.click()}><CloudUpload size={15} /> Importer</button><input ref={backupInputRef} type="file" accept="application/json,.json" onChange={importBackup} hidden /></div>{backupMessage && <small className="backup-message">{backupMessage}</small>}</section>
            <section><h2>Serveur Podmix</h2><p className="settings-copy">Adresse du moteur de détection et des annuaires. Sur un téléphone, utilisez l’adresse HTTPS de votre serveur ou son IP locale en debug.</p><div className="server-settings"><input type="url" value={serverUrl} onChange={(event) => setServerUrlState(event.target.value)} placeholder="https://podmix.example.com" /><button onClick={saveAndTestServer}>Enregistrer et tester</button></div>{serverMessage && <small className="backup-message">{serverMessage}</small>}</section>
            <section><h2>Mises à jour</h2><p className="settings-copy">Version installée : {currentVersion}. {updateMessage}</p>{availableUpdate && <button className="device-row" onClick={() => openUpdate(availableUpdate)}><Download size={18} /><span>Installer la version {availableUpdate.versionName}<small>{availableUpdate.notes.join(' · ')}</small></span><ExternalLink size={16} /></button>}</section>
          </div> : activeView === 'favorites' ? <section className="favorites-view">
            {favoriteTrackEntries.length > 0 && <section className="recent-list favorite-tracks">
              <div className="section-heading"><div><Heart size={17} fill="currentColor" /><h2>Morceaux favoris</h2></div><div className="favorite-heading-actions"><button className="spotify-refresh" onClick={() => {
                favoriteTrackEntries.forEach((entry) => {
                  forceSpotifyLookup.current.add(entry.key)
                  linkLookupAttempted.current.delete(entry.key)
                })
                setSpotifyRefreshRequest((request) => request + 1)
              }} aria-label="Rafraîchir les liens Spotify" title="Rafraîchir Spotify"><SpotifyIcon /><RotateCcw size={12} /></button><button className="play-all-favorites" onClick={() => void playFavoriteTracks(0)}><Play size={14} fill="currentColor" /> Tout lire</button><span>{favoriteTrackEntries.length}</span></div></div>
              <div className="favorite-groups">{favoriteTrackGroups.map((group) => <section className="favorite-group" key={group.source.id}>
                <div className="favorite-group-heading">{group.source.artworkUrl ? <img src={group.source.artworkUrl} alt="" /> : <Mic2 size={24} />}<div><strong>{group.source.title}</strong><span>{group.entries.length} morceau{group.entries.length > 1 ? 'x' : ''}</span></div></div>
                <div className="episode-list">{group.entries.map((entry) => <article className={`episode-item favorite-track-item ${activeMediaId === entry.key ? 'playing' : ''}`} key={entry.key}>
                  <button className="episode-play track-cover-button" onClick={() => void playFavoriteTracks(favoriteTrackEntries.findIndex((candidate) => candidate.key === entry.key))}>{trackArtwork(entry.track, entry.episode, entry.source) ? <img src={trackArtwork(entry.track, entry.episode, entry.source)} alt="" /> : <Mic2 size={16} />}<Play className="track-cover-play" size={14} fill="currentColor" /></button>
                  <div className="favorite-track-copy"><h3>{entry.track.title}</h3><p>{entry.track.artist} · {entry.episode.title} · {formatTime(entry.track.time)}</p></div>
                  <div className="favorite-track-actions">{favoriteServiceControl('deezer', entry.track.deezerUrl)}{favoriteServiceControl('spotify', entry.track.spotifyUrl)}<button className="episode-download" onClick={() => toggleTrackFavorite(entry.key)} aria-label="Retirer des favoris" title="Retirer des favoris"><Heart size={16} fill="currentColor" /></button></div>
                </article>)}</div>
              </section>)}</div>
            </section>}
            {!favoriteTrackEntries.length && <div className="empty-state catalog-empty"><Heart size={28} /><strong>Aucun favori</strong><span>Ajoutez des morceaux à vos favoris pour les retrouver ici.</span></div>}
          </section> : <>
            {activeView === 'home' && !selectedSource && homeSectionOrder.map((sectionId) => {
              if (sectionId === 'resume' && resumeEpisodes.length > 0) {
                return <section key="resume" className={`recent-list home-resume ${dragSection === 'resume' ? 'dragging' : ''}`}>
                  <div className="section-heading" onPointerDown={(e) => { if (e.pointerType === 'touch' || e.pointerType === 'pen') handleSectionDragStart('resume', e.clientY) }} onTouchStart={(e) => handleSectionDragStart('resume', e.touches[0].clientY)}><div><Clock3 size={17} /><h2>Reprendre l’écoute</h2></div><button onClick={() => setHistory([])}>Effacer</button></div>
                  <div className="episode-list">{resumeEpisodes.map(({ item, source, episode }) => <article className={`episode-item resume-episode ${nowPlaying?.id === item.id ? 'playing' : ''}`} key={item.id}>
                    <button className="episode-play" aria-label={`${globalPlaying && nowPlaying?.id === item.id && nowPlaying.scope === 'episode' ? 'Mettre en pause' : 'Reprendre'} ${item.title}`} onClick={() => playEpisode(item.id, item.title, item.artist, item.url, episode.artworkUrl || source.artworkUrl, item.position)}>{globalPlaying && nowPlaying?.id === item.id && nowPlaying.scope === 'episode' ? <Pause size={16} /> : <Play size={16} fill="currentColor" />}</button>
                    <button className="episode-info" aria-label={`Ouvrir ${item.title}`} onClick={() => openResumeEpisode(source, episode)}><h3>{item.title}</h3><p>{item.artist} · {globalPlaying && nowPlaying?.id === item.id ? `En lecture à ${formatTime(item.position)}` : `Reprendre à ${formatTime(item.position)}`}</p></button>
                    <span>{new Date(item.playedAt).toLocaleDateString('fr-FR')}</span><ChevronRight size={16} />
                  </article>)}</div>
                </section>
              }
              if (sectionId === 'podcasts' && podcasts.length > 0) return renderCatalogSection('podcasts', 'Podcasts', Mic2, podcasts)
              if (sectionId === 'shows' && shows.length > 0) return renderCatalogSection('shows', 'Émissions', AudioLines, shows)
              if (sectionId === 'radios' && radios.length > 0) return renderCatalogSection('radios', 'Radios', Radio, radios)
              if (sectionId === 'djSets' && djSets.length > 0) return renderCatalogSection('djSets', 'DJ sets', Disc3, djSets)
              if (sectionId === 'offline' && offlineEpisodes.length > 0) {
                return <section key="offline" className={`home-section offline-home ${dragSection === 'offline' ? 'dragging' : ''}`}>
                  <div className="section-heading" onPointerDown={(e) => { if (e.pointerType === 'touch' || e.pointerType === 'pen') handleSectionDragStart('offline', e.clientY) }} onTouchStart={(e) => handleSectionDragStart('offline', e.touches[0].clientY)}><div><Download size={17} /><h2>Hors connexion</h2></div><span>{offlineEpisodes.filter((item) => item.status === 'completed').length}</span></div>
                  <div className="episode-list">
                    {offlineEpisodes.map((episode) => {
                      const progressValue = episode.totalBytes && episode.totalBytes > 0 ? Math.round(((episode.bytesDownloaded ?? 0) / episode.totalBytes) * 100) : 0
                      return <article className={`episode-item offline-item ${nowPlaying?.id === episode.id ? 'playing' : ''}`} key={episode.id}>
                        <button className="episode-play" onClick={() => playOffline(episode)} disabled={episode.status !== 'completed'}>
                          {nowPlaying?.id === episode.id && globalPlaying ? <Pause size={16} /> : <Play size={16} fill="currentColor" />}
                        </button>
                        <div><h3>{episode.title}</h3><p>{episode.artist} · {episode.status === 'completed' ? 'Disponible hors connexion' : episode.status === 'failed' ? 'Téléchargement échoué' : `Téléchargement ${progressValue}%`}</p>{episode.status !== 'completed' && episode.status !== 'failed' && <div className="download-progress"><i style={{ width: `${progressValue}%` }} /></div>}</div>
                        <span>{episode.status === 'completed' && episode.bytesDownloaded ? `${(episode.bytesDownloaded / 1_048_576).toFixed(1)} Mo` : `${progressValue}%`}</span>
                        <button className="episode-download remove-download" onClick={() => removeOffline(episode)} aria-label="Supprimer"><Trash2 size={16} /></button>
                      </article>
                    })}
                  </div>
                </section>
              }
              return null
            })}
            {activeView === 'home' && !selectedSource && !podcasts.length && !shows.length && !radios.length && !djSets.length && !offlineEpisodes.length && !resumeEpisodes.length && <div className="empty-state catalog-empty"><Library size={28} /><strong>Votre bibliothèque est vide</strong><span>Ajoutez un podcast, une émission, une radio ou un DJ set pour commencer.</span><button onClick={openAddSource}><Plus size={15} /> Ajouter une source</button></div>}
            {selectedSource && <section className="source-detail">
              <button className="back-button" onClick={() => selectedEpisode ? setSelectedEpisodeId('') : setSelectedSourceId('')}>← Retour {selectedEpisode ? `à ${selectedSource.title}` : 'à l’accueil'}</button>
              {selectedEpisode ? <div className="episode-detail">
              <div className="source-heading">{selectedEpisode.artworkUrl || selectedSource.artworkUrl ? <img src={selectedEpisode.artworkUrl || selectedSource.artworkUrl} alt="" /> : <AudioLines size={38} />}<div><span>{selectedSource.title}</span><h2>{selectedEpisode.title}</h2><div className="source-actions"><button onClick={() => playFromSource(selectedSource, selectedEpisode.id)} disabled={!selectedEpisode.audioUrl}>{nowPlaying?.id === selectedEpisode.id && nowPlaying.scope === 'episode' && globalPlaying ? <Pause size={14} /> : <Play size={14} fill="currentColor" />} Lire</button><button onClick={openEpisodeInStudio}><AudioLines size={14} /> {selectedEpisode.analysis || selectedEpisode.tracks?.length ? 'Voir l’analyse' : 'Analyser'}</button><button onClick={() => downloadSourceEpisode(selectedSource, selectedEpisode)} disabled={!selectedEpisode.audioUrl}><Download size={14} /> Hors connexion</button></div></div></div>
                <div className="episode-meta"><span>{selectedEpisode.publishedAt || 'Date inconnue'}</span><span>{selectedEpisode.duration || 'Durée inconnue'}</span><span>{selectedEpisode.tracks?.length ?? 0} morceaux</span>{selectedEpisode.analysis && <span className={`analysis-chip ${selectedEpisode.analysis.status}`}>{analysisLabel(selectedEpisode)}</span>}</div>
                <section className={`episode-analysis-card ${selectedEpisode.analysis?.status ?? 'idle'}`}>
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
                    {['queued', 'running'].includes(selectedEpisode.analysis?.status ?? '') && <span className="analysis-card-progress"><i style={{ width: `${selectedEpisode.analysis?.progress ?? 0}%` }} /></span>}
                  </div>
                  <button onClick={openEpisodeInStudio}>{selectedEpisode.analysis?.status === 'completed' ? 'Vérifier' : selectedEpisode.analysis ? 'Voir le détail' : 'Lancer'}</button>
                </section>
                <div className="episode-tracklist"><div className="section-heading"><div><Disc3 size={17} /><h2>Tracklist</h2></div><span>{selectedEpisode.tracks?.length ?? 0} titres{usesEstimatedPositions(selectedEpisode.tracks) ? ' · repères estimés' : ''}</span></div>
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
                      <button className={`episode-track ${active ? 'playing' : ''}`} onClick={() => playTrackFromSource(selectedSource, selectedEpisode, index)}><span className="track-index">{String(index + 1).padStart(2, '0')}</span><time>{formatTime(track.time)}</time><strong><span>{track.title}</span><small>{track.artist}</small></strong>{playing ? <Pause size={14} fill="currentColor" /> : <Play size={14} />}</button>
                      <button className={`episode-track-favorite ${favorite ? 'active' : ''}`} onClick={() => toggleTrackFavorite(mediaId)} aria-label={`${favorite ? 'Retirer' : 'Ajouter'} ${track.title} ${favorite ? 'des' : 'aux'} favoris`} title={favorite ? 'Retirer des favoris' : 'Ajouter aux favoris'}><Heart size={15} fill={favorite ? 'currentColor' : 'none'} /></button>
                    </div>
                  })}
                  {!selectedEpisode.tracks?.length && <div className="empty-state"><AudioLines size={24} /><strong>Aucune tracklist enregistrée</strong><span>Lancez l’analyse pour rechercher les titres et calculer leurs repères.</span></div>}
                </div>
              </div> : <>
                <div className="source-heading">{selectedSource.artworkUrl ? <img src={selectedSource.artworkUrl} alt="" /> : <Mic2 size={38} />}<div><span>{sourceKindLabel(selectedSource)}{selectedSource.musical ? ' · musicale' : ''}</span><h2>{selectedSource.title}</h2>{selectedSource.kind === 'radio' && selectedSource.description && <p>{selectedSource.description}</p>}<div className="source-actions">{selectedSource.feedUrl && <button onClick={refreshSelectedSource} disabled={refreshingSource}><RotateCcw size={14} /> {refreshingSource ? 'Actualisation…' : 'Actualiser'}</button>}{['podcast', 'show'].includes(selectedSource.kind) && <button onClick={() => toggleMusicalSource(selectedSource)}><AudioLines size={14} /> {selectedSource.musical ? 'Analyse musicale active' : 'Activer l’analyse musicale'}</button>}<button onClick={removeSelectedSource}><Trash2 size={14} /> Supprimer</button></div></div></div>
                {selectedSource.kind === 'radio' && selectedSource.streamUrl && <button className="listen-live" onClick={() => playEpisode(selectedSource.id, selectedSource.title, 'Radio en direct', selectedSource.streamUrl!, selectedSource.artworkUrl, 0, 'radio')}>{nowPlaying?.id === selectedSource.id && globalPlaying ? <Pause size={17} /> : <Play size={17} fill="currentColor" />} Écouter en direct</button>}
                {selectedSource.kind !== 'radio' && <div className="episode-list">
                  {selectedSource.episodes.map((episode) => {
                    const playbackStatus = playbackStatusFor(episode)
                    return <article className={`episode-item ${nowPlaying?.id === episode.id ? 'playing' : ''}`} key={episode.id}>
                      <button className="episode-play" onClick={() => playFromSource(selectedSource, episode.id)} disabled={!episode.audioUrl}>{nowPlaying?.id === episode.id && nowPlaying.scope === 'episode' && globalPlaying ? <Pause size={16} /> : <Play size={16} fill="currentColor" />}</button>
                      <button className="episode-info" onClick={() => setSelectedEpisodeId(episode.id)}><h3>{episode.title}</h3><p>{analysisLabel(episode) || episode.publishedAt || episode.description.replace(/<[^>]+>/g, '').slice(0, 130)}</p><span className={`episode-read-state ${playbackStatus.kind}`} aria-label={`État de lecture : ${playbackStatus.label}`}><i className="read-orbit" style={{ '--read-progress': `${playbackStatus.percent}%` } as CSSProperties}>{playbackStatus.kind === 'done' && <Check size={9} strokeWidth={3} />}</i>{playbackStatus.label}</span>{['queued', 'running'].includes(episode.analysis?.status ?? '') && <span className="episode-analysis-progress"><i style={{ width: `${episode.analysis?.progress ?? 0}%` }} /></span>}</button>
                      <span>{episode.duration}</span>
                      <button className="episode-download" onClick={() => downloadSourceEpisode(selectedSource, episode)} disabled={!episode.audioUrl}><Download size={16} /></button>
                    </article>
                  })}
                  {!selectedSource.episodes.length && <div className="empty-state">Aucun épisode audio trouvé dans ce flux.</div>}
                </div>}
              </>}
            </section>}
          </>}
        </section>}
        {activeView === 'studio' && <section className="workspace">
          {!studioEpisode && !hasLocalAudio && !uploadId && !tracks.length ? <div className="analysis-hub">
            <div className="analysis-hub-heading">
              <div><span className="eyebrow"><i /> Traitements et résultats</span><h1>Analyses</h1><p>Suivez les tracklists automatiques, puis ouvrez seulement celles qui demandent une vérification.</p></div>
              <label className="upload-button"><CloudUpload size={17} /> {uploading ? 'Envoi…' : 'Analyser un fichier audio'}<input type="file" accept=".wav,.mp3,.flac,.ogg,.oga,.aac,.m4a,.mp4,audio/*" onChange={loadAudio} /></label>
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
                {['queued', 'running'].includes(episode.analysis?.status ?? '') && <span className="analysis-list-progress"><i style={{ width: `${episode.analysis?.progress ?? 0}%` }} /></span>}
                <ChevronRight size={17} />
              </button>)}
              {!analyzedEpisodes.length && <div className="empty-state"><WandSparkles size={26} /><strong>Aucune analyse pour le moment</strong><span>Ouvrez une émission ou un DJ set dans l’accueil, puis choisissez « Analyser ».</span><button onClick={() => navigateTo('home')}>Ouvrir l’accueil</button></div>}
            </section>
          </div> : <>
          <div className="project-head">
            <div><span className="eyebrow"><i /> Analyse ouverte</span><h1>{audioName}</h1><p>{studioEpisode ? 'Vérifiez les titres proposés et leurs repères avant d’enregistrer.' : 'Analyse d’un fichier audio local.'}</p></div>
            <div className="project-actions">
              <label className="upload-button"><CloudUpload size={17} /> {uploading ? 'Envoi…' : uploadId ? 'Audio prêt' : 'Importer l’audio'}<input type="file" accept=".wav,.mp3,.flac,.ogg,.oga,.aac,.m4a,.mp4,audio/*" onChange={loadAudio} /></label>
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
                <div><strong>{detection === 'running' ? 'Analyse spectrale en cours…' : detection === 'done' ? 'Analyse terminée' : detectionError || 'Détection assistée'}</strong><p>{detection === 'running' ? `${detectionStage} · ${progress}%` : detection === 'done' ? tracks.length ? `${tracks.length} morceau${tracks.length > 1 ? 'x' : ''} positionné${tracks.length > 1 ? 's' : ''}${usesEstimatedPositions(tracks) ? ' · repères encore estimés' : ''}.` : 'Aucune transition nette détectée.' : detectionError ? 'Le timestamping manuel reste disponible hors ligne.' : 'Lancer une analyse pour proposer les morceaux et leurs transitions.'}</p>{detection === 'running' && <div className="progress"><i style={{ width: `${progress}%` }} /></div>}</div>
                <button onClick={() => startDetection()} disabled={detection === 'running'}><Sparkles size={16} /> {detection === 'done' ? 'Relancer' : 'Détecter'}</button>
              </div>
            </section>

            <aside className="inspector">
              <div className="inspector-head"><div><span>Marqueur {String(selected.id).padStart(2, '0')}</span><strong>{formatTime(selected.time)}</strong></div><button><MoreHorizontal /></button></div>
              <div className="inspector-track-artwork">{studioArtwork ? <img src={studioArtwork} alt="" /> : <Disc3 size={28} />}<span>{selected.artworkUrl ? 'Pochette du morceau' : 'Logo du podcast'}</span></div>
              <label>Artiste<input value={selected.artist} onChange={(e) => updateSelected('artist', e.target.value)} /></label>
              <label>Titre<input value={selected.title} onChange={(e) => updateSelected('title', e.target.value)} /></label>
              <div className="confidence"><div><span>Indice de confiance</span><strong>{selected.confidence}%</strong></div><div className="confidence-track"><i style={{ width: `${selected.confidence}%` }} /></div></div>
              <div className="inspector-tip"><Gauge size={17} /><p><strong>Preuves</strong><br />{selected.evidence?.join(' · ') ?? 'Variation spectrale détectée dans le signal.'}</p></div>
              {selected.acousticValidation && <div className="inspector-tip"><AudioLines size={17} /><p><strong>Validation acoustique</strong><br />{selected.acousticValidation.available
                ? `${selected.acousticValidation.accepted ? 'Correspondance acceptée' : 'À vérifier'} · catalogue ${selected.acousticValidation.catalogScore ?? 0}% · audio ${selected.acousticValidation.acousticScore ?? 0}%`
                : 'Aucun aperçu catalogue exploitable'}</p></div>}
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
                <button className="catalog-button chroma-button" onClick={refineWithChroma} disabled={detection === 'running'}><WandSparkles size={14} /> Raffiner les timestamps</button>
                <button className="catalog-button" onClick={fingerprintSelected} disabled={fingerprinting || !currentJobId}><Disc3 size={14} /> {fingerprinting ? 'Calcul de l’empreinte…' : 'Identifier par empreinte'}</button>
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
            <div className="table-head"><span>#</span><span>Début</span><span>Morceau</span><span>Confiance</span><span>État</span></div>
            {tracks.map((track, index) => <button className={`track-row ${track.id === selectedId ? 'active' : ''}`} key={track.id} onClick={() => seek(track.time, track.id)}>
              <span className="track-number">{String(index + 1).padStart(2, '0')}</span><span className="track-time">{formatTime(track.time)}</span>
              <span className="track-title"><strong>{track.title}</strong><small>{track.artist}</small></span>
              <span className={`score ${track.confidence < 50 ? 'low' : track.confidence < 80 ? 'medium' : ''}`}><i />{track.confidence}%</span>
              <span className="status">{track.verified ? <><Check size={14} /> Vérifié</> : 'À confirmer'}</span>
            </button>)}
          </section>
          </>}
        </section>}
      </main>
      {showAddSource && <div className="modal-backdrop" onMouseDown={() => setShowAddSource(false)}>
        <section className="source-modal" onMouseDown={(event) => event.stopPropagation()}>
          <button className="modal-close" onClick={() => setShowAddSource(false)}>×</button>
          <span className="eyebrow"><i /> Nouvelle source</span><h2>Ajouter une source</h2>
          <div className="source-mode"><button className={sourceMode === 'rss' ? 'active' : ''} onClick={() => setSourceMode('rss')}>Podcast</button><button className={sourceMode === 'show' ? 'active' : ''} onClick={() => setSourceMode('show')}>Émission</button><button className={sourceMode === 'radio' ? 'active' : ''} onClick={() => setSourceMode('radio')}>Radio</button><button className={sourceMode === 'dj' ? 'active' : ''} onClick={() => setSourceMode('dj')}>DJ set</button></div>
          {sourceMode === 'rss' || sourceMode === 'show' ? <><p>Recherchez dans l’annuaire ou collez directement l’adresse HTTPS d’un flux RSS.</p>
          <label className="directory-search">Rechercher un podcast<input value={podcastQuery} onChange={(event) => setPodcastQuery(event.target.value)} placeholder="Nom, auteur ou sujet…" autoFocus /></label>{searchingPodcasts && <span className="live-search-status" role="status">Recherche…</span>}
          <div className="radio-results podcast-results">{podcastResults.map((podcast) => <button key={podcast.id} onClick={() => addPodcastResult(podcast)}><span className="podcast-thumb">{podcast.artworkUrl ? <img src={podcast.artworkUrl} alt="" /> : <Mic2 size={16} />}</span><span><strong>{podcast.title}</strong><small>{podcast.artist} · {podcast.episodeCount} épisodes</small></span><Plus size={15} /></button>)}</div>
          <label>Ou URL du flux RSS<input type="url" value={feedUrl} onChange={(event) => setFeedUrl(event.target.value)} placeholder="https://exemple.com/podcast.xml" /></label></> : sourceMode === 'radio' ? <><p>Recherchez une station dans l’annuaire communautaire Radio Browser.</p><label className="directory-search">Nom de la radio<input value={radioQuery} onChange={(event) => setRadioQuery(event.target.value)} placeholder="FIP, NTS, KEXP…" autoFocus /></label>{searchingRadios && <span className="live-search-status" role="status">Recherche…</span>}
          <div className="radio-results">{radioResults.map((radio) => <button key={radio.id} onClick={() => addRadio(radio)}><Radio size={16} /><span><strong>{radio.title}</strong><small>{radio.description}</small></span><Plus size={15} /></button>)}</div></> : <><p>Recherchez un DJ pour importer plusieurs sets, ou collez directement une URL YouTube, SoundCloud ou Mixcloud.</p><label className="directory-search">Nom du DJ<input value={djQuery} onChange={(event) => setDjQuery(event.target.value)} placeholder="Bicep, Floating Points…" autoFocus /></label>{searchingDj && <span className="live-search-status" role="status">Recherche…</span>}
          <div className="dj-search-results">{djResults.map((result) => <label key={result.id} className="dj-result"><input type="checkbox" checked={selectedDjResults.includes(result.id)} onChange={() => setSelectedDjResults((items) => items.includes(result.id) ? items.filter((id) => id !== result.id) : [...items, result.id])} /><span>{result.artworkUrl ? <img src={result.artworkUrl} alt="" /> : <Disc3 size={16} />}</span><strong>{result.title}<small>{result.uploader || 'YouTube'}{result.duration ? ` · ${formatTime(result.duration)}` : ''}</small></strong></label>)}</div>
          {selectedDjResults.length > 0 && <button className="inline-search import-selected" onClick={importSelectedDjSets} disabled={addingFeed}>{addingFeed ? 'Import en cours…' : `Importer ${selectedDjResults.length} set(s)`}</button>}
          <label>Ou URL d’un set<input type="url" value={feedUrl} onChange={(event) => setFeedUrl(event.target.value)} placeholder="https://soundcloud.com/… ou https://youtube.com/…" /></label></>}
          {feedError && <div className="form-error">{feedError}</div>}
          {sourceMode !== 'radio' && <button className="modal-submit" onClick={sourceMode === 'rss' || sourceMode === 'show' ? addFeed : addDjSet} disabled={addingFeed}>{sourceMode === 'rss' || sourceMode === 'show' ? addingFeed ? 'Import en cours…' : `Importer ${sourceMode === 'show' ? 'l’émission' : 'le podcast'}` : addingFeed ? 'Résolution…' : 'Importer le DJ set'}</button>}
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
          {activeOutput.kind === 'bose' && <label className="output-volume"><span>Volume de {activeOutput.name}<b>{boseVolume}%</b></span><input type="range" min="0" max="100" value={boseVolume} onChange={(event) => changeBoseVolume(Number(event.target.value))} /></label>}
          {activeOutput.kind === 'cast' && <label className="output-volume"><span>Volume de {activeOutput.name}<b>{castVolume}%</b></span><input type="range" min="0" max="100" value={castVolume} onChange={(event) => void changeCastVolume(Number(event.target.value))} /></label>}
          {(castMessage || boseMessage) && <p className="output-message" role="status">{boseMessage || castMessage}</p>}
        </section>
      </div>}
      {nowPlaying && <div className="mini-player-global" onClick={(e) => { if (!(e.target as HTMLElement).closest('button')) openNowPlayingEpisode() }} style={{ cursor: 'pointer' }}>
        <div className="mini-art">{nowPlaying.artworkUrl ? <img src={nowPlaying.artworkUrl} alt="" /> : <AudioLines size={19} />}</div>
        <div className="mini-meta"><strong>{nowPlaying.title}</strong><span>{nowPlaying.artist}</span></div>
        <button onClick={() => skip('previous')} aria-label="Morceau précédent"><SkipBack size={17} fill="currentColor" /></button>
        <button onClick={() => void toggleCurrentPlayback()}>{globalPlaying ? <Pause size={18} /> : <Play size={18} fill="currentColor" />}</button>
        <button onClick={() => skip('next')} aria-label="Morceau suivant"><SkipForward size={17} fill="currentColor" /></button>
        <button onClick={openOutputPicker} aria-label={`Sortie audio : ${activeOutput.name}`} aria-pressed={activeOutput.kind !== 'phone'} className={activeOutput.kind !== 'phone' ? 'active' : ''}><Cast size={17} /></button>
        <button onClick={() => downloadEpisode(nowPlaying.id, nowPlaying.title, nowPlaying.url)} disabled={!nowPlaying.url}><Download size={17} /></button>
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
      </div>}
      {downloadMessage && <span className="sr-only" role="status">{downloadMessage}</span>}
    </div>
  )
}

export default App
