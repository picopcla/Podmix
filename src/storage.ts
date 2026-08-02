import type { CatalogSource, OfflineEpisode, Track } from './domain'

const DATABASE = 'podmix-studio'
const STORE = 'sessions'
const SESSION_KEY = 'local-workbench'
const CATALOG_KEY = 'podmix-catalog-v1'
const OFFLINE_KEY = 'podmix-offline-v1'

type Session = { id: string; audioName: string; tracks: Track[]; updatedAt: string }

function database(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, 1)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE)) request.result.createObjectStore(STORE, { keyPath: 'id' })
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

export async function loadSession(): Promise<Session | undefined> {
  const db = await database()
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(STORE, 'readonly')
    const request = transaction.objectStore(STORE).get(SESSION_KEY)
    request.onsuccess = () => resolve(request.result as Session | undefined)
    request.onerror = () => reject(request.error)
    transaction.oncomplete = () => db.close()
  })
}

export async function saveSession(audioName: string, tracks: Track[]) {
  const db = await database()
  return new Promise<void>((resolve, reject) => {
    const transaction = db.transaction(STORE, 'readwrite')
    transaction.objectStore(STORE).put({
      id: SESSION_KEY,
      audioName,
      tracks,
      updatedAt: new Date().toISOString(),
    } satisfies Session)
    transaction.oncomplete = () => { db.close(); resolve() }
    transaction.onerror = () => { db.close(); reject(transaction.error) }
  })
}

export function loadCatalog(): CatalogSource[] {
  try {
    const sources = JSON.parse(localStorage.getItem(CATALOG_KEY) ?? '[]') as CatalogSource[]
    // 1.0.11 could keep the default "podcast" type when Add was opened from
    // the Emissions filter. Preserve the imported episodes while correcting
    // the source reported by the affected installation.
    return sources.map((source) => {
      if (source.kind === 'podcast' && source.title.trim().toLocaleLowerCase('fr') === 'legend') {
        return { ...source, kind: 'show', musical: false }
      }
      // The add-source dialog could retain the previous "show" mode when it
      // was reopened from the unfiltered library. Repair the affected source
      // without relying only on its display title.
      if (source.kind === 'show' && source.feedUrl?.replace(/\/+$/, '') === 'https://feed.podbean.com/richve/feed.xml') {
        return { ...source, kind: 'podcast' }
      }
      return source
    })
  } catch {
    return []
  }
}

export function saveCatalog(sources: CatalogSource[]) {
  const compact = sources.map((source) => ({
    ...source,
    description: source.description.slice(0, 2_000),
    episodes: source.episodes.map((episode) => ({
      ...episode,
      description: episode.description.slice(0, 4_000),
      tracks: episode.tracks?.map((track) => ({
        ...track,
        evidence: track.evidence?.slice(0, 8).map((item) => item.slice(0, 400)),
      })),
      analysis: episode.analysis ? {
        ...episode.analysis,
        error: episode.analysis.error?.slice(0, 1_000),
      } : undefined,
    })),
  }))
  const minimal = compact.map((source) => ({
    ...source,
    description: '',
    episodes: source.episodes.map((episode) => ({ ...episode, description: '' })),
  }))

  for (const candidate of [compact, minimal]) {
    try {
      localStorage.setItem(CATALOG_KEY, JSON.stringify(candidate))
      return
    } catch (error) {
      if (!(error instanceof DOMException) || error.name !== 'QuotaExceededError') return
    }
  }
}

export function loadOfflineEpisodes(): OfflineEpisode[] {
  try {
    const episodes: unknown = JSON.parse(localStorage.getItem(OFFLINE_KEY) ?? '[]')
    return Array.isArray(episodes) ? episodes as OfflineEpisode[] : []
  } catch {
    return []
  }
}

export function saveOfflineEpisodes(episodes: OfflineEpisode[]) {
  localStorage.setItem(OFFLINE_KEY, JSON.stringify(episodes))
}
