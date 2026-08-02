import { Capacitor, registerPlugin } from '@capacitor/core'
import type { PluginListenerHandle } from '@capacitor/core'

export type PlayerState = {
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
}

export type LoadOptions = {
  id?: string
  url: string
  title?: string
  artist?: string
  artworkUrl?: string
  autoplay?: boolean
  startPositionSeconds?: number
  endPositionSeconds?: number
}

export type LibraryItem = Omit<LoadOptions, 'url'> & {
  id: string
  url?: string
  parentId: string
  groupId?: string
  favoriteId?: string
  browsable: boolean
  playable: boolean
}

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

export type NativeTracklistResult = {
  sourceUrl: string
  pageTitle: string
  durationSeconds: number
  tracks: Array<{ artist: string; title: string; providedTime: number }>
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
  syncLibrary(options: { items: LibraryItem[] }): Promise<{ count: number }>
  syncFavorites(options: { ids: string[] }): Promise<{ count: number }>
  getFavorites(): Promise<{ ids: string[]; initialized: boolean }>
  syncSubscriptions(options: { items: Array<{ id: string; title: string; feedUrl: string }> }): Promise<{ count: number }>
  getStorage(): Promise<{ downloadedBytes: number; availableBytes: number; totalBytes: number }>
  setQueue(options: { items: Array<LoadOptions & { id: string }>; startIndex?: number; startPositionSeconds?: number; autoplay?: boolean }): Promise<PlayerState>
  next(): Promise<PlayerState>
  previous(): Promise<PlayerState>
  download(options: { id: string; url: string; title?: string }): Promise<DownloadState>
  getDownload(options: { id: string }): Promise<DownloadState>
  removeDownload(options: { id: string }): Promise<DownloadState>
  openCastPicker(): Promise<{ pickerOpened?: boolean; connected?: boolean }>
  discoverCastDevices(): Promise<{ devices: CastDevice[] }>
  connectCastDevice(options: { id: string }): Promise<{ connected: boolean; deviceName?: string }>
  disconnectCast(): Promise<PlayerState & { connected: boolean }>
  cast(options: { url: string; title?: string; artist?: string; artworkUrl?: string; contentType?: string; positionSeconds?: number; positionOffsetSeconds?: number; endPositionSeconds?: number }): Promise<{ connected: boolean; deviceName?: string }>
  getCastState(): Promise<{ connected: boolean; deviceName?: string }>
  bosePlay(options: { ip: string; url: string; title?: string; positionSeconds?: number }): Promise<{ ok: boolean }>
  boseDiscover(): Promise<{ devices: BoseDevice[] }>
  boseKey(options: { ip: string; key: string }): Promise<{ ok: boolean }>
  boseSetVolume(options: { ip: string; volume: number }): Promise<{ ok: boolean }>
  boseGetState(options: { ip: string }): Promise<{ ok: boolean; name?: string; volume?: number; playing?: boolean; playStatus?: string; positionSeconds?: number; source?: string; title?: string; location?: string }>
  fetchTracklist1001(options: { url: string; address?: string }): Promise<NativeTracklistResult>
  addListener(eventName: 'stateChanged', listener: (state: PlayerState) => void): Promise<PluginListenerHandle>
}

const NativePlayer = registerPlugin<PodmixPlayerPlugin>('PodmixPlayer')
const webAudio = new Audio()
let webTitle = ''
let webArtist = ''
let webQueue: Array<LoadOptions & { id: string }> = []
let webQueueIndex = 0
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
    if (Capacitor.isNativePlatform()) return NativePlayer.load(safeOptions)
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
    if (Capacitor.isNativePlatform()) return NativePlayer.play()
    await webAudio.play()
    return webState()
  },
  async pause() {
    if (Capacitor.isNativePlatform()) return NativePlayer.pause()
    webAudio.pause()
    return webState()
  },
  async seekTo(positionSeconds: number) {
    if (Capacitor.isNativePlatform()) return NativePlayer.seekTo({ positionSeconds })
    webAudio.currentTime = webItemStart(webQueue[webQueueIndex]) + Math.max(0, positionSeconds)
    return webState()
  },
  async getState() {
    return Capacitor.isNativePlatform() ? NativePlayer.getState() : webState()
  },
  async onStateChanged(listener: (state: PlayerState) => void) {
    if (!Capacitor.isNativePlatform()) return { remove: async () => undefined }
    return NativePlayer.addListener('stateChanged', listener)
  },
  async syncLibrary(items: LibraryItem[]) {
    if (!Capacitor.isNativePlatform()) return { count: items.length }
    return NativePlayer.syncLibrary({
      items: items.map((item) => item.url ? { ...item, url: secureMediaUrl(item.url) } : item),
    })
  },
  async syncFavorites(ids: string[]) {
    if (!Capacitor.isNativePlatform()) return { count: ids.length }
    return NativePlayer.syncFavorites({ ids })
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
  async setQueue(items: Array<LoadOptions & { id: string }>, startIndex = 0, autoplay = false, startPositionSeconds = 0) {
    const safeItems = items.map(secureLoadOptions)
    if (Capacitor.isNativePlatform()) {
      return NativePlayer.setQueue({ items: safeItems, startIndex, startPositionSeconds, autoplay })
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
    if (Capacitor.isNativePlatform()) return NativePlayer.next()
    if (webQueueIndex < webQueue.length - 1) {
      webQueueIndex += 1
      await loadWebQueueItem(webQueue[webQueueIndex], true)
    }
    return webState()
  },
  async previous() {
    if (Capacitor.isNativePlatform()) return NativePlayer.previous()
    const start = webItemStart(webQueue[webQueueIndex])
    if (webAudio.currentTime - start > 5) webAudio.currentTime = start
    else if (webQueueIndex > 0) {
      webQueueIndex -= 1
      await loadWebQueueItem(webQueue[webQueueIndex], true)
    }
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
  async removeDownload(id: string) {
    if (!Capacitor.isNativePlatform()) return { id, status: 'not_found' as const, removed: false }
    return NativePlayer.removeDownload({ id })
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
    return NativePlayer.disconnectCast()
  },
  async cast(options: LoadOptions & {
    artworkUrl?: string
    contentType?: string
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
}
