import { decodeTextEntities } from './text'

export const PLAYBACK_HISTORY_KEY = 'podmix-history-v1'
import { reportStorageFailure, reportStorageSuccess, safeSetItem } from './safeStorage'
export const LISTENING_SESSIONS_KEY = 'podmix-listening-sessions-v1'
export const COMPLETED_EPISODES_KEY = 'podmix-completed-episodes-v1'
const HISTORY_DATABASE = 'podmix-history'
const HISTORY_STORE = 'history'
const COMPLETED_EPISODES_DATABASE = 'podmix-completed-episodes'
const COMPLETED_EPISODES_STORE = 'completed-episodes'
const COMPLETED_EPISODES_RECORD = 'ids'

export type PlaybackHistoryItem = {
  id: string
  title: string
  artist: string
  url: string
  position: number
  duration?: number
  playedAt: string
}

export type ListeningSession = PlaybackHistoryItem & {
  sessionId: string
  startedAt: string
  updatedAt: string
}

const finiteNumber = (value: unknown) => typeof value === 'number' && Number.isFinite(value) ? Math.max(0, value) : 0
const validDate = (value: unknown, fallback = new Date(0).toISOString()) =>
  typeof value === 'string' && Number.isFinite(Date.parse(value)) ? value : fallback

export function normalizeHistoryItem(value: unknown): PlaybackHistoryItem | undefined {
  if (!value || typeof value !== 'object') return undefined
  const item = value as Partial<PlaybackHistoryItem>
  if (typeof item.id !== 'string' || !item.id || typeof item.title !== 'string' || typeof item.url !== 'string') return undefined
  const duration = finiteNumber(item.duration)
  return {
    id: item.id,
    title: decodeTextEntities(item.title),
    artist: typeof item.artist === 'string' ? decodeTextEntities(item.artist) : '',
    url: item.url,
    position: finiteNumber(item.position),
    ...(duration > 0 ? { duration } : {}),
    playedAt: validDate(item.playedAt),
  }
}

function readArray(key: string): unknown[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(key) ?? '[]')
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

export function loadPlaybackHistory(): PlaybackHistoryItem[] {
  return normalizePlaybackHistory(readArray(PLAYBACK_HISTORY_KEY))
}

function normalizePlaybackHistory(items: unknown[]): PlaybackHistoryItem[] {
  const seen = new Set<string>()
  return items
    .map(normalizeHistoryItem)
    .filter((item): item is PlaybackHistoryItem => Boolean(item))
    .sort((left, right) => Date.parse(right.playedAt) - Date.parse(left.playedAt))
    .filter((item) => !seen.has(item.id) && Boolean(seen.add(item.id)))
    .slice(0, 1_000)
}

export function savePlaybackHistory(items: PlaybackHistoryItem[]) {
  safeSetItem(PLAYBACK_HISTORY_KEY, JSON.stringify(items.slice(0, 1_000)))
}

/** Les éléments terminés ne font pas partie de « Reprendre ». */
export function loadCompletedEpisodeIds(): string[] {
  return normalizeCompletedEpisodeIds(readArray(COMPLETED_EPISODES_KEY))
}

export function saveCompletedEpisodeIds(ids: string[]) {
  safeSetItem(COMPLETED_EPISODES_KEY, JSON.stringify(normalizeCompletedEpisodeIds(ids)))
}

function normalizeCompletedEpisodeIds(values: unknown[]): string[] {
  return [...new Set(values.filter((value): value is string => typeof value === 'string' && value.length > 0))].slice(0, 2_000)
}

function sameCompletedEpisodeIds(left: string[], right: string[]): boolean {
  return left.length === right.length && left.every((id, index) => id === right[index])
}

function isValidCompletedEpisodeIds(value: unknown): value is string[] {
  return Array.isArray(value)
    && value.every((id) => typeof id === 'string' && id.length > 0)
    && sameCompletedEpisodeIds(value, normalizeCompletedEpisodeIds(value))
}

export function loadListeningSessions(): ListeningSession[] {
  return normalizeListeningSessions(readArray(LISTENING_SESSIONS_KEY), loadPlaybackHistory())
}

function normalizeListeningSessions(stored: unknown[], fallbackHistory: PlaybackHistoryItem[]): ListeningSession[] {
  if (!stored.length) {
    return fallbackHistory.map((item, index) => ({
      ...item,
      sessionId: `legacy-${Date.parse(item.playedAt)}-${index}`,
      startedAt: item.playedAt,
      updatedAt: item.playedAt,
    }))
  }
  return stored.flatMap((value) => {
    const item = normalizeHistoryItem(value)
    if (!item || !value || typeof value !== 'object') return []
    const session = value as Partial<ListeningSession>
    if (typeof session.sessionId !== 'string' || !session.sessionId) return []
    return [{ ...item, sessionId: session.sessionId, startedAt: validDate(session.startedAt, item.playedAt), updatedAt: validDate(session.updatedAt, item.playedAt) }]
  }).sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt)).slice(0, 2_000)
}

export function updateListeningSessions(sessions: ListeningSession[], item: PlaybackHistoryItem, now = new Date()): ListeningSession[] {
  const timestamp = now.toISOString()
  const current = sessions[0]
  const coalesce = current?.id === item.id && now.getTime() - Date.parse(current.updatedAt) < 30 * 60 * 1_000
  const next: ListeningSession = coalesce
    ? { ...current, ...item, updatedAt: timestamp, playedAt: timestamp }
    : { ...item, sessionId: `${now.getTime().toString(36)}-${crypto.randomUUID?.() ?? Math.random().toString(36).slice(2)}`, startedAt: timestamp, updatedAt: timestamp, playedAt: timestamp }
  return [next, ...(coalesce ? sessions.slice(1) : sessions)].slice(0, 2_000)
}

export function saveListeningSessions(sessions: ListeningSession[]) {
  safeSetItem(LISTENING_SESSIONS_KEY, JSON.stringify(sessions.slice(0, 2_000)))
}

export type HistoryStore = {
  read(key: string): Promise<unknown[] | undefined>
  write(key: string, value: unknown[]): Promise<void>
}

export type CompletedEpisodesStore = {
  read(): Promise<unknown[] | undefined>
  write(value: string[]): Promise<void>
}

function indexedDbHistoryStore(): HistoryStore {
  const openDatabase = () => new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(HISTORY_DATABASE, 1)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(HISTORY_STORE)) request.result.createObjectStore(HISTORY_STORE)
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })

  return {
    async read(key) {
      const db = await openDatabase()
      return new Promise((resolve, reject) => {
        const transaction = db.transaction(HISTORY_STORE, 'readonly')
        const request = transaction.objectStore(HISTORY_STORE).get(key)
        request.onsuccess = () => resolve(request.result as unknown[] | undefined)
        request.onerror = () => reject(request.error)
        transaction.oncomplete = () => db.close()
        transaction.onerror = () => db.close()
      })
    },
    async write(key, value) {
      const db = await openDatabase()
      return new Promise<void>((resolve, reject) => {
        const transaction = db.transaction(HISTORY_STORE, 'readwrite')
        transaction.objectStore(HISTORY_STORE).put(value, key)
        transaction.oncomplete = () => { db.close(); resolve() }
        transaction.onerror = () => { db.close(); reject(transaction.error) }
      })
    },
  }
}

function indexedDbCompletedEpisodesStore(): CompletedEpisodesStore {
  const openDatabase = () => new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(COMPLETED_EPISODES_DATABASE, 1)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(COMPLETED_EPISODES_STORE)) request.result.createObjectStore(COMPLETED_EPISODES_STORE)
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })

  return {
    async read() {
      const db = await openDatabase()
      return new Promise((resolve, reject) => {
        const transaction = db.transaction(COMPLETED_EPISODES_STORE, 'readonly')
        const request = transaction.objectStore(COMPLETED_EPISODES_STORE).get(COMPLETED_EPISODES_RECORD)
        request.onsuccess = () => resolve(request.result as unknown[] | undefined)
        request.onerror = () => reject(request.error)
        transaction.oncomplete = () => db.close()
        transaction.onerror = () => db.close()
      })
    },
    async write(value) {
      const db = await openDatabase()
      return new Promise<void>((resolve, reject) => {
        const transaction = db.transaction(COMPLETED_EPISODES_STORE, 'readwrite')
        transaction.objectStore(COMPLETED_EPISODES_STORE).put(value, COMPLETED_EPISODES_RECORD)
        transaction.oncomplete = () => { db.close(); resolve() }
        transaction.onerror = () => { db.close(); reject(transaction.error) }
      })
    },
  }
}

async function loadOrMigrate(store: HistoryStore, key: string): Promise<unknown[]> {
  try {
    const stored = await store.read(key)
    if (stored !== undefined) return stored
  } catch {
    // The untouched localStorage copy remains the recovery path.
  }

  const legacy = readArray(key)
  try {
    await store.write(key, legacy)
    reportStorageSuccess(key)
  } catch {
    reportStorageFailure(key)
  }
  return legacy
}

export function createHistoryRepository(store: HistoryStore) {
  return {
    async load(): Promise<{ history: PlaybackHistoryItem[]; sessions: ListeningSession[] }> {
      const legacyHistory = await loadOrMigrate(store, PLAYBACK_HISTORY_KEY)
      const legacySessions = await loadOrMigrate(store, LISTENING_SESSIONS_KEY)
      const history = normalizePlaybackHistory(legacyHistory)
      return { history, sessions: normalizeListeningSessions(legacySessions, history) }
    },
    async saveHistory(items: PlaybackHistoryItem[]): Promise<boolean> {
      try {
        await store.write(PLAYBACK_HISTORY_KEY, items.slice(0, 1_000))
        reportStorageSuccess(PLAYBACK_HISTORY_KEY)
        return true
      } catch {
        reportStorageFailure(PLAYBACK_HISTORY_KEY)
        return false
      }
    },
    async saveSessions(sessions: ListeningSession[]): Promise<boolean> {
      try {
        await store.write(LISTENING_SESSIONS_KEY, sessions.slice(0, 2_000))
        reportStorageSuccess(LISTENING_SESSIONS_KEY)
        return true
      } catch {
        reportStorageFailure(LISTENING_SESSIONS_KEY)
        return false
      }
    },
  }
}

export function createCompletedEpisodesRepository(store: CompletedEpisodesStore) {
  const legacyIds = () => loadCompletedEpisodeIds()

  return {
    async load(): Promise<string[]> {
      try {
        const stored = await store.read()
        if (isValidCompletedEpisodeIds(stored)) return stored

        const legacy = legacyIds()
        await store.write(legacy)
        const verified = await store.read()
        if (!isValidCompletedEpisodeIds(verified) || !sameCompletedEpisodeIds(verified, legacy)) throw new Error('Copie IndexedDB des episodes termines invalide')
        reportStorageSuccess(COMPLETED_EPISODES_KEY)
        return legacy
      } catch {
        reportStorageFailure(COMPLETED_EPISODES_KEY)
        return legacyIds()
      }
    },
    async save(ids: string[]): Promise<boolean> {
      const normalized = normalizeCompletedEpisodeIds(ids)
      try {
        await store.write(normalized)
        const verified = await store.read()
        if (!isValidCompletedEpisodeIds(verified) || !sameCompletedEpisodeIds(verified, normalized)) throw new Error('Ecriture IndexedDB des episodes termines non verifiee')
        reportStorageSuccess(COMPLETED_EPISODES_KEY)

        // Double-ecriture de compatibilite : une version anterieure voit les nouveaux etats.
        // Un quota localStorage ne remet pas en cause la copie IndexedDB deja verifiee.
        try {
          localStorage.setItem(COMPLETED_EPISODES_KEY, JSON.stringify(normalized))
        } catch {
          // La valeur legacy precedente reste le filet de retour arriere quand le quota bloque sa mise a jour.
        }
        return true
      } catch {
        reportStorageFailure(COMPLETED_EPISODES_KEY)
        return false
      }
    },
  }
}

const historyRepository = createHistoryRepository(indexedDbHistoryStore())
const completedEpisodesRepository = createCompletedEpisodesRepository(indexedDbCompletedEpisodesStore())

export function loadHistoryPersistence() {
  return historyRepository.load()
}

export function saveHistoryPersistence(items: PlaybackHistoryItem[]) {
  return historyRepository.saveHistory(items)
}

export function saveListeningSessionsPersistence(sessions: ListeningSession[]) {
  return historyRepository.saveSessions(sessions)
}

export function loadCompletedEpisodesPersistence() {
  return completedEpisodesRepository.load()
}

export function saveCompletedEpisodesPersistence(ids: string[]) {
  return completedEpisodesRepository.save(ids)
}

export function searchListeningSessions(sessions: ListeningSession[], query: string) {
  const normalized = query.trim().toLocaleLowerCase('fr')
  if (!normalized) return sessions
  return sessions.filter((item) => `${item.title} ${item.artist}`.toLocaleLowerCase('fr').includes(normalized))
}
