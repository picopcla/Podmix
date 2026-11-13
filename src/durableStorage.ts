import { reportStorageFailure, reportStorageSuccess } from './safeStorage'

export const DURABLE_KEYS = ['podmix-catalog-v1', 'podmix-history-v1', 'podmix-listening-sessions-v1', 'podmix-offline-v1'] as const
export type DurableKey = typeof DURABLE_KEYS[number]
export type DurableStore = { read(key: string): Promise<unknown>; write(key: string, value: string): Promise<void> }

export function indexedDbDurableStore(): DurableStore {
  const open = () => new Promise<IDBDatabase>((resolve, reject) => {
    let blocked = false
    const request = indexedDB.open('podmix-library-history', 1)
    request.onupgradeneeded = () => request.result.createObjectStore('records')
    request.onsuccess = () => { if (blocked) request.result.close(); else resolve(request.result) }
    request.onerror = () => reject(request.error)
    request.onblocked = () => { blocked = true; reject(new Error('IndexedDB blocked')) }
  })
  return {
    async read(key) {
      const db = await open()
      return new Promise((resolve, reject) => {
        const tx = db.transaction('records', 'readonly')
        const request = tx.objectStore('records').get(key)
        tx.oncomplete = () => { db.close(); resolve(request.result) }
        tx.onabort = tx.onerror = () => { db.close(); reject(tx.error ?? request.error) }
      })
    },
    async write(key, value) {
      const db = await open()
      return new Promise<void>((resolve, reject) => {
        const tx = db.transaction('records', 'readwrite')
        tx.objectStore('records').put(value, key)
        tx.oncomplete = () => { db.close(); resolve() }
        tx.onabort = tx.onerror = () => { db.close(); reject(tx.error) }
      })
    },
  }
}

const valid = (key: DurableKey, value: unknown): value is string => {
  if (typeof value !== 'string') return false
  try {
    const rows: unknown = JSON.parse(value)
    return Array.isArray(rows) && rows.every(row => {
      if (!row || typeof row !== 'object' || typeof row.id !== 'string' || typeof row.title !== 'string') return false
      if (key === 'podmix-catalog-v1') return typeof row.description === 'string' && Array.isArray(row.episodes)
        && row.episodes.every((episode: unknown) => !!episode && typeof episode === 'object')
      if (key === 'podmix-offline-v1') return typeof row.remoteUrl === 'string' && typeof row.status === 'string'
      return typeof row.url === 'string' && (key !== 'podmix-listening-sessions-v1' || typeof row.sessionId === 'string')
    })
  } catch { return false }
}

export function createDurableRepository(store: DurableStore) {
  const cache = new Map<DurableKey, string>()
  const readable = new Set<DurableKey>()
  const tails = new Map<DurableKey, Promise<unknown>>()
  const errors = new Map<DurableKey, string>()
  const unsafe = new Set<DurableKey>()
  const legacyRead = (key: DurableKey) => { try { return localStorage.getItem(key) } catch { return null } }
  let initializing: Promise<void> | undefined
  const queue = <T>(key: DurableKey, work: () => Promise<T>) => {
    const next = (tails.get(key) ?? Promise.resolve()).then(work, work)
    tails.set(key, next.catch(() => undefined))
    return next
  }
  const failure = (key: DurableKey, error: unknown) => {
    errors.set(key, error instanceof Error ? error.name : 'StorageError')
    reportStorageFailure(key)
  }
  const commit = async (key: DurableKey, value: string) => {
    await store.write(key, value)
    if (await store.read(key) !== value) throw new Error('IndexedDB verification failed')
    errors.delete(key)
    reportStorageSuccess(key)
  }
  return {
    initialize() {
      // Called before React mounts, so no initial empty state can overwrite saved data.
      initializing ??= Promise.all(DURABLE_KEYS.map(key => queue(key, async () => {
        let legacy: string | null = null
        let legacyError: unknown
        try { legacy = localStorage.getItem(key) } catch (error) { legacyError = error }
        if (legacy !== null) cache.set(key, legacy)
        try {
          const stored = await store.read(key)
          if (stored !== undefined && !valid(key, stored)) throw new Error('Invalid IndexedDB record')
          if (stored !== undefined) cache.set(key, stored)
          else {
            if (legacyError) throw legacyError // An unreadable legacy copy is not an absent copy.
            const value = legacy ?? '[]'
            if (!valid(key, value)) throw new Error('Invalid legacy record')
            await commit(key, value)
            cache.set(key, value)
          }
          readable.add(key)
        } catch (error) { unsafe.add(key); failure(key, error) }
      }))).then(() => undefined)
      return initializing
    },
    read(key: DurableKey): string | null {
      return cache.get(key) ?? legacyRead(key)
    },
    save(key: DurableKey, value: string): Promise<boolean> {
      // Snapshot the value now, before asynchronous work. Keep failed updates in memory.
      cache.set(key, value)
      return queue(key, async () => {
        try {
          if (unsafe.has(key)) throw new Error('Startup storage check failed; preserved copies are read-only until restart')
          if (!valid(key, value)) throw new Error('Invalid save payload')
          if (!readable.has(key)) {
            // A failed read is never permission to overwrite an unknown saved record.
            const stored = await store.read(key)
            if (stored !== undefined) throw new Error('Recovery required before overwriting unread state')
            readable.add(key)
          }
          await commit(key, value)
          // Preserve the original localStorage copy, including when IDB succeeds.
          // No eviction, trimming or audio deletion is used to get more room.
          return true
        } catch (error) { failure(key, error); return false }
      })
    },
    diagnostics() {
      return DURABLE_KEYS.map(key => ({ key, characters: (cache.get(key) ?? legacyRead(key) ?? '').length,
        readable: readable.has(key), error: errors.get(key) ?? null }))
    },
  }
}
const repository = createDurableRepository(indexedDbDurableStore())
let active = false
export const isDurableStorageActive = () => active
export function initializeDurableStorage() { active = true; return repository.initialize() }
export const readDurableItem = repository.read
export const saveDurableItem = repository.save
export const durableStorageDiagnostics = repository.diagnostics
