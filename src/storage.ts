import type { CatalogSource, OfflineEpisode, Track } from './domain'
import { reportStorageFailure, reportStorageSuccess, safeSetItem } from './safeStorage'

const DATABASE = 'podmix-studio'
const STORE = 'sessions'
const SESSION_KEY = 'local-workbench'
const CATALOG_KEY = 'podmix-catalog-v1'
const CATALOG_STORE = 'catalogs'
const CATALOG_RECORD_ID = 'current'
const OFFLINE_KEY = 'podmix-offline-v1'

type Session = { id: string; audioName: string; tracks: Track[]; updatedAt: string }
type CatalogRecord = { id: typeof CATALOG_RECORD_ID; sources: CatalogSource[] }

export type CatalogStore = {
  read(): Promise<CatalogSource[] | undefined>
  write(sources: CatalogSource[]): Promise<void>
}

function database(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, 2)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE)) request.result.createObjectStore(STORE, { keyPath: 'id' })
      if (!request.result.objectStoreNames.contains(CATALOG_STORE)) request.result.createObjectStore(CATALOG_STORE, { keyPath: 'id' })
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

function normalizeCatalog(sources: CatalogSource[]): CatalogSource[] {
  return sources.map((source) => {
    // 1.0.11 could keep the default "podcast" type when Add was opened from
    // the Emissions filter. Preserve the imported episodes while correcting
    // the source reported by the affected installation.
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
}

function loadLegacyCatalog(): CatalogSource[] {
  try {
    const sources: unknown = JSON.parse(localStorage.getItem(CATALOG_KEY) ?? '[]')
    return Array.isArray(sources) ? sources as CatalogSource[] : []
  } catch {
    return []
  }
}

function indexedDbCatalogStore(): CatalogStore {
  return {
    async read() {
      const db = await database()
      return new Promise((resolve, reject) => {
        const transaction = db.transaction(CATALOG_STORE, 'readonly')
        const request = transaction.objectStore(CATALOG_STORE).get(CATALOG_RECORD_ID)
        request.onsuccess = () => resolve((request.result as CatalogRecord | undefined)?.sources)
        request.onerror = () => reject(request.error)
        transaction.oncomplete = () => db.close()
        transaction.onerror = () => { db.close(); reject(transaction.error) }
      })
    },
    async write(sources) {
      const db = await database()
      return new Promise<void>((resolve, reject) => {
        const transaction = db.transaction(CATALOG_STORE, 'readwrite')
        transaction.objectStore(CATALOG_STORE).put({ id: CATALOG_RECORD_ID, sources } satisfies CatalogRecord)
        transaction.oncomplete = () => { db.close(); resolve() }
        transaction.onerror = () => { db.close(); reject(transaction.error) }
      })
    },
  }
}

export function createCatalogRepository(store: CatalogStore) {
  return {
    async load(): Promise<CatalogSource[]> {
      try {
        const migrated = await store.read()
        if (migrated) return normalizeCatalog(migrated)
      } catch {
        // The legacy copy remains the recovery path if IndexedDB cannot be read.
      }

      const legacy = loadLegacyCatalog()
      try {
        // Copy first, never remove or overwrite the localStorage recovery copy.
        await store.write(legacy)
        reportStorageSuccess(CATALOG_KEY)
      } catch {
        reportStorageFailure(CATALOG_KEY)
      }
      return normalizeCatalog(legacy)
    },
    async save(sources: CatalogSource[]): Promise<boolean> {
      try {
        await store.write(sources)
        reportStorageSuccess(CATALOG_KEY)
        return true
      } catch {
        reportStorageFailure(CATALOG_KEY)
        return false
      }
    },
  }
}

const catalogRepository = createCatalogRepository(indexedDbCatalogStore())

export function loadCatalog(): Promise<CatalogSource[]> {
  return catalogRepository.load()
}

/**
 * Enregistre le catalogue complet. Si le stockage refuse l'ecriture, l'ancienne
 * version stockee est conservee telle quelle (aucun repli reduit qui ecraserait
 * des donnees) et l'echec est signale a l'interface. Renvoie true si enregistre.
 */
export function saveCatalog(sources: CatalogSource[]): Promise<boolean> {
  return catalogRepository.save(sources)
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
  safeSetItem(OFFLINE_KEY, JSON.stringify(episodes))
}
