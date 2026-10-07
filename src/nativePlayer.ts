import { createPlayerStateOrder } from './playerStateOrder'
import { Capacitor, registerPlugin } from '@capacitor/core'
import type { PluginListenerHandle } from '@capacitor/core'

export type PlayerState = {
  stateSequence?: number
  playing: boolean
  positionSeconds: number
  durationSeconds: number
  playbackState: number
  title: string
  artist: string
  queueIndex: number
  queueSize: number
  hasNext: boolean
  hasPrevious: boolean
  mediaId: string
  error?: string
  playRequested?: boolean
  absolutePositionSeconds?: number
  bufferedPositionSeconds?: number
  playbackSuppressionReason?: number
  positionOffsetSeconds?: number
}

export type LoadOptions = {
  id?: string
  podmixSourceId?: string
  podmixEpisodeId?: string
  url: string
  title?: string
  artist?: string
  artworkUrl?: string
  autoplay?: boolean
  startPositionSeconds?: number
  endPositionSeconds?: number
  continuousEpisode?: boolean
  trackNavigation?: boolean
  live?: boolean
}

export type LibraryItem = Omit<LoadOptions, 'url'> & {
  id: string
  url?: string
  parentId: string
  groupId?: string
  favoriteId?: string
  browsable: boolean
  playable: boolean
  kind?: string
}

// Keep each Capacitor bridge message small without truncating the catalogue.
// The native side appends these chunks to a staging file and atomically commits
// it only after every source, episode and track has arrived.
const NATIVE_LIBRARY_CHUNK_SIZE = 100

function normalizedNativeLibrary(items: LibraryItem[]) {
  const snapshot: LibraryItem[] = []
  const includedIds = new Set<string>()

  for (const item of items) {
    const normalized = item.url ? { ...item, url: secureMediaUrl(item.url) } : item
    if (normalized.parentId !== 'podmix-root' && !includedIds.has(normalized.parentId)) continue
    snapshot.push(normalized)
    includedIds.add(normalized.id)
  }

  return snapshot
}

type LibrarySyncResult = { count: number; bytes?: number }
let nativeLibrarySyncQueue: Promise<void> = Promise.resolve()

export type DownloadState = {
  id: string
  requestId?: number
  status: 'queued' | 'downloading' | 'paused' | 'completed' | 'failed' | 'not_found' | 'unknown'
  bytesDownloaded?: number
  totalBytes?: number
  localUri?: string
  reason?: number
  removed?: boolean
}

export type ApkDownloadState = {
  requestId?: number
  status: 'queued' | 'downloading' | 'paused' | 'completed' | 'failed' | 'not_found' | 'unknown'
  bytesDownloaded?: number
  totalBytes?: number
  progress?: number
  localUri?: string
  path?: string
}

export type NativeTracklistResult = {
  sourceUrl: string
  pageTitle: string
  durationSeconds: number
  tracks: Array<{ artist: string; title: string; providedTime: number }>
}

export type NativeTracklistSearchResult = {
  candidates: Array<{ url: string; title: string; domain: string; address?: string }>
}

export type MusicRecognitionResult = {
  matched: boolean
  title: string
  artist: string
  album?: string
  artworkUrl?: string
  spotifyUrl?: string
  deezerUrl?: string
  appleMusicUrl?: string
}

export type BoseDevice = {
  ip: string
  name: string
  type?: string
  id?: string
}

export type CastDevice = {
  id: string
  name: string
  description?: string
  deviceType?: string
  connected?: boolean
}

type PodmixPlayerPlugin = {
  load(options: LoadOptions): Promise<PlayerState>
  play(): Promise<PlayerState>
  pause(): Promise<PlayerState>
  seekTo(options: { positionSeconds: number }): Promise<PlayerState>
  getState(): Promise<PlayerState>
  getPendingPlaybackTarget(): Promise<{ sourceId: string; episodeId: string }>
  setVolume(options: { volume: number }): Promise<{ volume: number }>
  getVolume(): Promise<{ volume: number }>
  recognizeMusic(options: { apiUrl: string }): Promise<MusicRecognitionResult>
  getAppInfo(): Promise<{ versionName: string; versionCode: number; lastUpdateTime: number }>
  cacheArtwork(options: { url: string }): Promise<{ uri: string; dataUrl?: string }>
  beginLibrarySync(options: { syncId: string }): Promise<{ ok: boolean }>
  appendLibraryChunk(options: { syncId: string; items: LibraryItem[] }): Promise<{ count: number }>
  commitLibrarySync(options: { syncId: string; expectedCount: number }): Promise<LibrarySyncResult>
  syncFavorites(options: { ids: string[] }): Promise<{ count: number }>
  syncResume(options: { items: Array<{ id: string; episodeId: string; title: string; artist: string; url: string; artworkUrl: string; positionSeconds: number; durationSeconds: number }>; completedEpisodeIds?: string[] }): Promise<{ count: number }>
  getCompletedEpisodeIds(): Promise<{ ids: string[] }>
  getFavorites(): Promise<{ ids: string[]; initialized: boolean }>
  syncSubscriptions(options: { items: Array<{ id: string; title: string; feedUrl: string }> }): Promise<{ count: number }>
  getStorage(): Promise<{ downloadedBytes: number; availableBytes: number; totalBytes: number }>
  setQueue(options: { items: Array<LoadOptions & { id: string }>; startIndex?: number; startPositionSeconds?: number; autoplay?: boolean }): Promise<PlayerState>
  next(): Promise<PlayerState>
  previous(): Promise<PlayerState>
  setRepeatMode(options: { mode: 0 | 1 | 2 }): Promise<PlayerState>
  download(options: { id: string; url: string; title?: string }): Promise<DownloadState>
  getDownload(options: { id: string }): Promise<DownloadState>
  listDownloads(): Promise<{ downloads: DownloadState[] }>
  removeDownload(options: { id: string }): Promise<DownloadState>
  extractTracks(options: { episodeId: string; audioPath: string; tracks: Array<{ id: string; start: number; end: number }> }): Promise<{ tracks: Array<{ trackId: string; path: string; status: string }> }>
  downloadApk(options: { url: string; versionName: string }): Promise<{ requestId: number; path: string }>
  getApkStatus(options: { requestId: number }): Promise<ApkDownloadState>
  installApk(options: { path?: string; localUri?: string }): Promise<{ ok: boolean }>
  openCastPicker(): Promise<{ pickerOpened?: boolean; connected?: boolean }>
  discoverCastDevices(): Promise<{ devices: CastDevice[] }>
  connectCastDevice(options: { id: string }): Promise<{ connected: boolean; deviceName?: string }>
  disconnectCast(): Promise<PlayerState & { connected: boolean }>
  cast(options: { url: string; title?: string; artist?: string; artworkUrl?: string; contentType?: string; positionSeconds?: number; positionOffsetSeconds?: number; endPositionSeconds?: number }): Promise<{ connected: boolean; deviceName?: string }>
  getCastState(): Promise<{ connected: boolean; deviceName?: string }>
  setCastVolume(options: { volume: number }): Promise<{ volume: number }>
  getCastVolume(): Promise<{ volume: number }>
  bosePlay(options: { ip: string; url: string; title?: string; positionSeconds?: number }): Promise<{ ok: boolean }>
  boseDiscover(): Promise<{ devices: BoseDevice[] }>
  boseKey(options: { ip: string; key: string }): Promise<{ ok: boolean }>
  boseSetVolume(options: { ip: string; volume: number }): Promise<{ ok: boolean }>
  boseGetState(options: { ip: string }): Promise<{ ok: boolean; name?: string; volume?: number; playing?: boolean; playStatus?: string; positionSeconds?: number; source?: string; title?: string; location?: string }>
  searchTracklists1001(options: { query: string }): Promise<NativeTracklistSearchResult>
  fetchTracklist1001(options: { url: string; address?: string }): Promise<NativeTracklistResult>
  fetchPublishedTracklist(options: { url: string }): Promise<NativeTracklistResult>
  startWebTimestampWorker(options: { apiUrl: string }): Promise<{ started: boolean }>
  addListener(eventName: 'stateChanged', listener: (state: PlayerState) => void): Promise<PluginListenerHandle>
}

const NativePlayer = registerPlugin<PodmixPlayerPlugin>('PodmixPlayer')
const nativeStateOrder = createPlayerStateOrder<PlayerState>()
async function orderedNativeState(request: Promise<PlayerState>) {
  return nativeStateOrder.resolve(await request)
}
const webAudio = new Audio()
webAudio.volume = Math.max(0, Math.min(1, Number(localStorage.getItem('podmix-player-volume') ?? 1)))
let webTitle = ''
let webArtist = ''
let webQueue: Array<LoadOptions & { id: string }> = []
let webQueueIndex = 0
let webRepeatMode = 0
let webAdvancing = false

function webItemStart(item?: LoadOptions) {
  return Math.max(0, item?.startPositionSeconds ?? 0)
}

async function loadWebQueueItem(item: LoadOptions & { id: string }, autoplay: boolean) {
  const safeItem = secureLoadOptions(item)
  webAudio.src = safeItem.url
  webTitle = item.title ?? 'Podmix'
  webArtist = item.artist ?? ''
  webAudio.load()
  const start = webItemStart(item)
  if (start > 0) webAudio.currentTime = start
  if (autoplay) await webAudio.play()
}

webAudio.addEventListener('timeupdate', () => {
  const item = webQueue[webQueueIndex]
  const end = item?.endPositionSeconds
  if (!end || webAudio.currentTime < end || webAdvancing) return
  webAdvancing = true
  if (webQueueIndex < webQueue.length - 1) {
    webQueueIndex += 1
    void loadWebQueueItem(webQueue[webQueueIndex], true).finally(() => { webAdvancing = false })
  } else if (webRepeatMode === 2) {
    webQueueIndex = 0
    void loadWebQueueItem(webQueue[webQueueIndex], true).finally(() => { webAdvancing = false })
  } else if (webRepeatMode === 1) {
    void loadWebQueueItem(webQueue[webQueueIndex], true).finally(() => { webAdvancing = false })
  } else {
    webAudio.pause()
    webAdvancing = false
  }
})

webAudio.addEventListener('ended', () => {
  if (webAdvancing || webQueueIndex >= webQueue.length - 1) return
  webAdvancing = true
  webQueueIndex += 1
  void loadWebQueueItem(webQueue[webQueueIndex], true)
    .finally(() => { webAdvancing = false })
})

function secureMediaUrl(url: string): string {
  try {
    const parsed = new URL(url)
    if (parsed.protocol === 'http:' && parsed.hostname === 'audio.thisisdistorted.com') {
      parsed.protocol = 'https:'
      return parsed.toString()
    }
  } catch {
    // Let the player report malformed URLs with its normal error path.
  }
  return url
}

function secureLoadOptions<T extends LoadOptions>(options: T): T {
  return { ...options, url: secureMediaUrl(options.url) }
}

function webState(): PlayerState {
  const item = webQueue[webQueueIndex]
  const start = webItemStart(item)
  const end = item?.endPositionSeconds
  const duration = end && end > start
    ? end - start
    : Number.isFinite(webAudio.duration) ? Math.max(0, webAudio.duration - start) : 0
  return {
    playing: !webAudio.paused,
    positionSeconds: Math.max(0, (webAudio.currentTime || 0) - start),
    durationSeconds: duration,
    playbackState: webAudio.readyState,
    title: webTitle,
    artist: webArtist,
    queueIndex: webQueueIndex,
    queueSize: webQueue.length,
    hasNext: webQueueIndex < webQueue.length - 1,
    hasPrevious: webQueueIndex > 0,
  mediaId: webQueue[webQueueIndex]?.id ?? '',
  error: '',
  }
}

export const podmixPlayer = {
  isNative: Capacitor.isNativePlatform(),
  async load(options: LoadOptions) {
    const safeOptions = secureLoadOptions(options)
    if (Capacitor.isNativePlatform()) return orderedNativeState(NativePlayer.load(safeOptions))
    webQueue = []
    webQueueIndex = 0
    webAudio.src = safeOptions.url
    webTitle = options.title ?? 'Podmix'
    webArtist = options.artist ?? ''
    webAudio.load()
    if (options.autoplay) await webAudio.play()
    return webState()
  },
  async play() {
    if (Capacitor.isNativePlatform()) return orderedNativeState(NativePlayer.play())
    const item = webQueue[webQueueIndex]
    // A paused HTMLAudioElement keeps consuming the old radio connection's
    // buffered bytes. Radios are not podcasts: resuming one must reconnect to
    // the stream head instead of replaying delayed audio.
    if (item?.live && webAudio.paused) {
      await loadWebQueueItem(item, false)
    }
    await webAudio.play()
    return webState()
  },
  async pause() {
    if (Capacitor.isNativePlatform()) return orderedNativeState(NativePlayer.pause())
    webAudio.pause()
    return webState()
  },
  async seekTo(positionSeconds: number) {
    if (Capacitor.isNativePlatform()) return orderedNativeState(NativePlayer.seekTo({ positionSeconds }))
    webAudio.currentTime = webItemStart(webQueue[webQueueIndex]) + Math.max(0, positionSeconds)
    return webState()
  },
  async getState() {
    return Capacitor.isNativePlatform() ? orderedNativeState(NativePlayer.getState()) : webState()
  },
  async setVolume(options: { volume: number }) {
    const volume = Math.max(0, Math.min(1, options.volume))
    if (Capacitor.isNativePlatform()) return NativePlayer.setVolume({ volume })
    webAudio.volume = volume
    localStorage.setItem('podmix-player-volume', String(volume))
    return { volume }
  },
  async getVolume() {
    if (Capacitor.isNativePlatform()) return NativePlayer.getVolume()
    return { volume: webAudio.volume }
  },
  async recognizeMusic(options: { apiUrl: string }) {
    if (!Capacitor.isNativePlatform()) throw new Error('La reconnaissance au microphone nécessite l’application Android')
    return NativePlayer.recognizeMusic(options)
  },
  async getAppInfo() {
    if (Capacitor.isNativePlatform()) return NativePlayer.getAppInfo()
    return {
      versionName: import.meta.env.VITE_APP_VERSION ?? '1.0.0',
      versionCode: 0,
      lastUpdateTime: Date.now(),
    }
  },
  async cacheArtwork(url: string) {
    if (!Capacitor.isNativePlatform()) return { uri: url }
    return NativePlayer.cacheArtwork({ url })
  },
  async onStateChanged(listener: (state: PlayerState) => void) {
    if (!Capacitor.isNativePlatform()) return { remove: async () => undefined }
    return NativePlayer.addListener('stateChanged', (state) => {
      if (nativeStateOrder.accept(state)) listener(state)
    })
  },
  async syncLibrary(items: LibraryItem[]) {
    if (!Capacitor.isNativePlatform()) return { count: items.length }
    const normalized = normalizedNativeLibrary(items)
    const task = nativeLibrarySyncQueue
      .catch(() => undefined)
      .then(async (): Promise<LibrarySyncResult> => {
        const syncId = `${Date.now()}-${Math.random().toString(36).slice(2)}`
        await NativePlayer.beginLibrarySync({ syncId })
        for (let offset = 0; offset < normalized.length; offset += NATIVE_LIBRARY_CHUNK_SIZE) {
          await NativePlayer.appendLibraryChunk({
            syncId,
            items: normalized.slice(offset, offset + NATIVE_LIBRARY_CHUNK_SIZE),
          })
        }
        const result = await NativePlayer.commitLibrarySync({
          syncId,
          expectedCount: normalized.length,
        })
        if (result.count !== normalized.length) {
          throw new Error(`Bibliothèque Android Auto incomplète : ${result.count}/${normalized.length}`)
        }
        return result
      })
    nativeLibrarySyncQueue = task.then(() => undefined, () => undefined)
    return task
  },
  async syncFavorites(ids: string[]) {
    if (!Capacitor.isNativePlatform()) return { count: ids.length }
    return NativePlayer.syncFavorites({ ids })
  },
  async syncResume(items: Array<{ id: string; episodeId: string; title: string; artist: string; url: string; artworkUrl: string; positionSeconds: number; durationSeconds: number }>, completedEpisodeIds: string[] = []) {
    if (!Capacitor.isNativePlatform()) return { count: items.length }
    return NativePlayer.syncResume({ items, completedEpisodeIds })
  },
  async getCompletedEpisodeIds() {
    if (!Capacitor.isNativePlatform()) return [] as string[]
    const result = await NativePlayer.getCompletedEpisodeIds()
    return result.ids
  },
  async getFavorites() {
    if (!Capacitor.isNativePlatform()) return { ids: [] as string[], initialized: false }
    return NativePlayer.getFavorites()
  },
  async syncSubscriptions(items: Array<{ id: string; title: string; feedUrl: string }>) {
    if (!Capacitor.isNativePlatform()) return { count: items.length }
    return NativePlayer.syncSubscriptions({ items })
  },
  async getStorage() {
    if (!Capacitor.isNativePlatform()) {
      const estimate = await navigator.storage?.estimate()
      return { downloadedBytes: estimate?.usage ?? 0, availableBytes: Math.max(0, (estimate?.quota ?? 0) - (estimate?.usage ?? 0)), totalBytes: estimate?.quota ?? 0 }
    }
    return NativePlayer.getStorage()
  },
  async getPendingPlaybackTarget() {
    if (!Capacitor.isNativePlatform()) return { sourceId: '', episodeId: '' }
    return NativePlayer.getPendingPlaybackTarget()
  },
  async setQueue(items: Array<LoadOptions & { id: string }>, startIndex = 0, autoplay = false, startPositionSeconds = 0) {
    const safeItems = items.map(secureLoadOptions)
    if (Capacitor.isNativePlatform()) {
      return orderedNativeState(NativePlayer.setQueue({ items: safeItems, startIndex, startPositionSeconds, autoplay }))
    }
    webQueue = safeItems
    webQueueIndex = Math.min(Math.max(0, startIndex), Math.max(0, items.length - 1))
    const item = webQueue[webQueueIndex]
    if (!item) return webState()
    await loadWebQueueItem(item, false)
    if (!item.startPositionSeconds && startPositionSeconds > 0) {
      webAudio.currentTime = startPositionSeconds
    }
    if (autoplay) await webAudio.play()
    return webState()
  },
  async next() {
    if (Capacitor.isNativePlatform()) return orderedNativeState(NativePlayer.next())
    if (webQueueIndex < webQueue.length - 1) {
      webQueueIndex += 1
      await loadWebQueueItem(webQueue[webQueueIndex], true)
    }
    return webState()
  },
  async previous() {
    if (Capacitor.isNativePlatform()) return orderedNativeState(NativePlayer.previous())
    if (webQueueIndex > 0) {
      webQueueIndex -= 1
      await loadWebQueueItem(webQueue[webQueueIndex], true)
    }
    return webState()
  },
  async setRepeatMode(mode: 0 | 1 | 2) {
    webRepeatMode = mode
    if (Capacitor.isNativePlatform()) return orderedNativeState(NativePlayer.setRepeatMode({ mode }))
    return webState()
  },
  async download(id: string, url: string, title?: string) {
    if (!Capacitor.isNativePlatform()) throw new Error('Les téléchargements durables nécessitent l’application Android')
    return NativePlayer.download({ id, url: secureMediaUrl(url), title })
  },
  async getDownload(id: string) {
    if (!Capacitor.isNativePlatform()) return { id, status: 'not_found' as const }
    return NativePlayer.getDownload({ id })
  },
  async listDownloads() {
    if (!Capacitor.isNativePlatform()) return { downloads: [] as DownloadState[] }
    return NativePlayer.listDownloads()
  },
  async removeDownload(id: string) {
    if (!Capacitor.isNativePlatform()) return { id, status: 'not_found' as const, removed: false }
    return NativePlayer.removeDownload({ id })
  },
  async downloadApk(url: string, versionName: string) {
    if (!Capacitor.isNativePlatform()) throw new Error('La mise à jour native nécessite l\'application Android')
    return NativePlayer.downloadApk({ url, versionName })
  },
  async getApkStatus(requestId: number) {
    if (!Capacitor.isNativePlatform()) return { status: 'not_found' as const, progress: 0 }
    return NativePlayer.getApkStatus({ requestId })
  },
  async installApk(path?: string, localUri?: string) {
    if (!Capacitor.isNativePlatform()) throw new Error('L\'installation native nécessite l\'application Android')
    return NativePlayer.installApk({ path, localUri })
  },
  async extractTracks(options: { episodeId: string; audioPath: string; tracks: Array<{ id: string; start: number; end: number }> }) {
    if (!Capacitor.isNativePlatform()) return { tracks: [] }
    return NativePlayer.extractTracks(options)
  },
  async openCastPicker() {
    if (!Capacitor.isNativePlatform()) throw new Error('Google Cast nécessite l’application Android')
    return NativePlayer.openCastPicker()
  },
  async discoverCastDevices() {
    if (!Capacitor.isNativePlatform()) return { devices: [] as CastDevice[] }
    return NativePlayer.discoverCastDevices()
  },
  async connectCastDevice(id: string) {
    if (!Capacitor.isNativePlatform()) throw new Error('Google Cast nécessite l’application Android')
    return NativePlayer.connectCastDevice({ id })
  },
  async disconnectCast() {
    if (!Capacitor.isNativePlatform()) return { ...webState(), connected: false }
    return orderedNativeState(NativePlayer.disconnectCast())
  },
  async cast(options: LoadOptions & {
    artworkUrl?: string
    contentType?: string
    live?: boolean
    positionSeconds?: number
    positionOffsetSeconds?: number
    endPositionSeconds?: number
  }) {
    if (!Capacitor.isNativePlatform()) throw new Error('Google Cast nécessite l’application Android')
    return NativePlayer.cast(secureLoadOptions(options))
  },
  async getCastState() {
    if (!Capacitor.isNativePlatform()) return { connected: false }
    return NativePlayer.getCastState()
  },
  async setCastVolume(options: { volume: number }) {
    if (!Capacitor.isNativePlatform()) throw new Error('Google Cast nécessite l\'application Android')
    return NativePlayer.setCastVolume(options)
  },
  async getCastVolume() {
    if (!Capacitor.isNativePlatform()) throw new Error('Google Cast nécessite l\'application Android')
    return NativePlayer.getCastVolume()
  },
  async bosePlay(ip: string, url: string, title?: string, positionSeconds = 0) {
    if (!Capacitor.isNativePlatform()) throw new Error('Bose SoundTouch nécessite l’application Android')
    return NativePlayer.bosePlay({ ip, url: secureMediaUrl(url), title, positionSeconds })
  },
  async boseDiscover() {
    if (!Capacitor.isNativePlatform()) return { devices: [] as BoseDevice[] }
    return NativePlayer.boseDiscover()
  },
  async boseKey(ip: string, key: string) {
    if (!Capacitor.isNativePlatform()) throw new Error('Bose SoundTouch nécessite l’application Android')
    return NativePlayer.boseKey({ ip, key })
  },
  async boseSetVolume(ip: string, volume: number) {
    if (!Capacitor.isNativePlatform()) throw new Error('Bose SoundTouch nécessite l’application Android')
    return NativePlayer.boseSetVolume({ ip, volume })
  },
  async boseGetState(ip: string) {
    if (!Capacitor.isNativePlatform()) throw new Error('Bose SoundTouch nécessite l’application Android')
    return NativePlayer.boseGetState({ ip })
  },
  async fetchTracklist1001(url: string, address?: string) {
    if (!Capacitor.isNativePlatform()) throw new Error('La récupération 1001Tracklists directe nécessite Android')
    return NativePlayer.fetchTracklist1001({ url, address })
  },
  async fetchPublishedTracklist(url: string) {
    if (!Capacitor.isNativePlatform()) throw new Error('La récupération des liens publiés nécessite Android')
    return NativePlayer.fetchPublishedTracklist({ url })
  },
  async startWebTimestampWorker(apiUrl: string) {
    if (!Capacitor.isNativePlatform()) return { started: false }
    return NativePlayer.startWebTimestampWorker({ apiUrl })
  },
  async searchTracklists1001(query: string) {
    if (!Capacitor.isNativePlatform()) throw new Error('La recherche 1001Tracklists directe nécessite Android')
    return NativePlayer.searchTracklists1001({ query })
  },
}
