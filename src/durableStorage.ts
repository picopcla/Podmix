import { reportStorageFailure, reportStorageSuccess, safeSetItem } from './safeStorage'

export const DURABLE_KEYS = ['podmix-catalog-v1', 'podmix-history-v1', 'podmix-listening-sessions-v1', 'podmix-offline-v1'] as const
export type DurableKey = typeof DURABLE_KEYS[number]
// Petites donnees de l'utilisateur ecrites jusqu'ici UNIQUEMENT dans localStorage. Quand le quota du WebView
// est plein (anciennes copies de la bibliotheque conservees), leur ecriture echouait et l'interface affichait
// « Sauvegarde impossible : favoris ». IndexedDB fait foi ; la copie localStorage n'est plus qu'un miroir best-effort.
export const STATE_KEYS = ['podmix-track-favorites-v1', 'podmix-home-section-order-v1', 'podmix-settings-v1'] as const
export type StateKey = typeof STATE_KEYS[number]
type AnyKey = DurableKey | StateKey
const ALL_KEYS: readonly AnyKey[] = [...DURABLE_KEYS, ...STATE_KEYS]
const isStateKey = (key: AnyKey): key is StateKey => (STATE_KEYS as readonly string[]).includes(key)
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

const validState = (key: StateKey, value: string): boolean => {
  try {
    const parsed: unknown = JSON.parse(value)
    if (key === 'podmix-settings-v1') return !!parsed && typeof parsed === 'object' && !Array.isArray(parsed)
    return Array.isArray(parsed) && parsed.every(item => typeof item === 'string')
  } catch { return false }
}

const valid = (key: AnyKey, value: unknown): value is string => {
  if (typeof value !== 'string') return false
  if (isStateKey(key)) return validState(key, value)
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
  const cache = new Map<AnyKey, string>()
  const readable = new Set<AnyKey>()
  const tails = new Map<AnyKey, Promise<unknown>>()
  const errors = new Map<AnyKey, string>()
  const unsafe = new Set<AnyKey>()
  // Distinguish a first-launch empty seed from an explicitly saved empty list.
  const newRecords = new Set<AnyKey>()
  const legacyRead = (key: AnyKey) => { try { return localStorage.getItem(key) } catch { return null } }
  let initializing: Promise<void> | undefined
  const queue = <T>(key: AnyKey, work: () => Promise<T>) => {
    const next = (tails.get(key) ?? Promise.resolve()).then(work, work)
    tails.set(key, next.catch(() => undefined))
    return next
  }
  const failure = (key: AnyKey, error: unknown) => {
    errors.set(key, error instanceof Error ? error.name : 'StorageError')
    reportStorageFailure(key)
  }
  const commit = async (key: AnyKey, value: string) => {
    await store.write(key, value)
    if (await store.read(key) !== value) throw new Error('IndexedDB verification failed')
    errors.delete(key)
    reportStorageSuccess(key)
  }
  return {
    initialize() {
      // Called before React mounts, so no initial empty state can overwrite saved data.
      initializing ??= Promise.all(ALL_KEYS.map(key => queue(key, async () => {
        let legacy: string | null = null
        let legacyError: unknown
        try { legacy = localStorage.getItem(key) } catch (error) { legacyError = error }
        if (legacy !== null) cache.set(key, legacy)
        try {
          const stored = await store.read(key)
          if (stored !== undefined && !valid(key, stored)) throw new Error('Invalid IndexedDB record')
          if (stored !== undefined) cache.set(key, stored)
          else if (isStateKey(key)) {
            if (legacyError) throw legacyError // An unreadable legacy copy is not an absent copy.
            // Nothing to migrate: no seed write. A malformed legacy value is ignored (the app already fell
            // back to its defaults for it) and left untouched; a later save only writes the verified IDB record.
            if (legacy !== null && valid(key, legacy)) {
              await commit(key, legacy)
              cache.set(key, legacy)
            } else cache.delete(key)
          } else {
            if (legacyError) throw legacyError // An unreadable legacy copy is not an absent copy.
            if (legacy === null) newRecords.add(key)
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
    read(key: AnyKey): string | null {
      return cache.get(key) ?? legacyRead(key)
    },
    isNewRecord(key: AnyKey): boolean { return newRecords.has(key) },
    save(key: AnyKey, value: string): Promise<boolean> {
      // Snapshot the value now, before asynchronous work. Keep failed updates in memory.
      cache.set(key, value)
      newRecords.delete(key)
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
          // State keys only: best-effort compatibility mirror (an older build, or a rollback, still reads recent
          // data). A full localStorage cannot invalidate the verified IDB record, so its failure is ignored.
          if (isStateKey(key)) { try { localStorage.setItem(key, value) } catch { /* Previous copy stays intact. */ } }
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

export const isNewDurableRecord = repository.isNewRecord

/**
 * Ecrit une petite donnee utilisateur (favoris, reglages, ordre de l'accueil) dans IndexedDB, sans dependre du quota
 * localStorage. Avant l'initialisation du stockage durable (tests, repli), l'ancien chemin localStorage est conserve.
 */
export function saveStateItem(key: StateKey, value: string): void {
  if (isDurableStorageActive()) void saveDurableItem(key, value)
  else safeSetItem(key, value)
}
