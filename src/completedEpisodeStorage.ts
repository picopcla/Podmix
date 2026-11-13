import { COMPLETED_EPISODES_KEY, loadCompletedEpisodeIds } from './history'
import { reportStorageFailure, reportStorageSuccess } from './safeStorage'

// Keep the database/record names used by the earlier completed-episodes fix.
export type CompletedEpisodesStore = { read(): Promise<unknown>; write(ids: string[]): Promise<void> }
const normalize = (value: unknown): string[] => Array.isArray(value)
  ? [...new Set(value.filter((id): id is string => typeof id === 'string' && id.length > 0))].slice(0, 2_000) : []
const valid = (value: unknown): value is string[] => Array.isArray(value)
  && value.every(id => typeof id === 'string' && id.length > 0)
  && JSON.stringify(value) === JSON.stringify(normalize(value))

export function indexedDbCompletedEpisodesStore(): CompletedEpisodesStore {
  const open = () => new Promise<IDBDatabase>((resolve, reject) => {
    let blocked = false
    const request = indexedDB.open('podmix-completed-episodes', 1)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains('completed-episodes')) request.result.createObjectStore('completed-episodes')
    }
    request.onsuccess = () => { if (blocked) request.result.close(); else resolve(request.result) }
    request.onerror = () => reject(request.error)
    request.onblocked = () => { blocked = true; reject(new Error('Completed episodes database blocked')) }
  })
  return {
    async read() {
      const db = await open()
      return new Promise((resolve, reject) => {
        const tx = db.transaction('completed-episodes', 'readonly')
        const req = tx.objectStore('completed-episodes').get('ids')
        tx.oncomplete = () => { db.close(); resolve(req.result) }
        tx.onabort = tx.onerror = () => { db.close(); reject(tx.error ?? req.error) }
      })
    },
    async write(ids) {
      const db = await open()
      return new Promise<void>((resolve, reject) => {
        const tx = db.transaction('completed-episodes', 'readwrite')
        tx.objectStore('completed-episodes').put(ids, 'ids')
        tx.oncomplete = () => { db.close(); resolve() }
        tx.onabort = tx.onerror = () => { db.close(); reject(tx.error) }
      })
    },
  }
}

export function createCompletedEpisodesRepository(store: CompletedEpisodesStore) {
  // Serialize reads, migration and verification: an older slow save must never win.
  let tail: Promise<unknown> = Promise.resolve()
  const queued = <T>(work: () => Promise<T>): Promise<T> => {
    const next = tail.then(work, work)
    tail = next.catch(() => undefined)
    return next
  }
  const read = async () => {
    const stored = await store.read()
    if (stored !== undefined && !valid(stored)) throw new Error('Invalid completed episodes record')
    // The UI only adds completed IDs. Union also preserves additions from an older APK.
    return normalize([...(stored ?? []), ...loadCompletedEpisodeIds()])
  }
  const verifiedWrite = async (ids: string[]) => {
    await store.write(ids)
    const stored = await store.read()
    if (!valid(stored) || JSON.stringify(stored) !== JSON.stringify(ids)) throw new Error('Completed episodes write not verified')
    reportStorageSuccess(COMPLETED_EPISODES_KEY)
  }
  return {
    load: () => queued(async () => {
      try {
        const ids = await read()
        await verifiedWrite(ids)
        // Migration never removes or rewrites the original localStorage copy.
        return { ids, ready: true }
      } catch {
        reportStorageFailure(COMPLETED_EPISODES_KEY)
        return { ids: loadCompletedEpisodeIds(), ready: false }
      }
    }),
    save: (value: string[]) => {
      const requested = normalize(value)
      return queued(async () => {
        try {
          const existing = await read() // Never overwrite a record that could not be read.
          const ids = normalize([...requested, ...existing])
          await verifiedWrite(ids)
          // Best-effort compatibility copy. Its quota cannot invalidate verified IDB.
          try { localStorage.setItem(COMPLETED_EPISODES_KEY, JSON.stringify(ids)) } catch { /* Previous copy stays intact. */ }
          return true
        } catch {
          reportStorageFailure(COMPLETED_EPISODES_KEY)
          return false
        }
      })
    },
  }
}
const repository = createCompletedEpisodesRepository(indexedDbCompletedEpisodesStore())
export const loadCompletedEpisodesPersistence = repository.load
export const saveCompletedEpisodesPersistence = repository.save
