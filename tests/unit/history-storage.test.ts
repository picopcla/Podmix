// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { getStorageFailures, reportStorageFailure } from '../../src/safeStorage'
import {
  createHistoryRepository,
  type HistoryStore,
  LISTENING_SESSIONS_KEY,
  PLAYBACK_HISTORY_KEY,
} from '../../src/history'

const history = [{
  id: 'episode-1', title: 'Episode conserve', artist: 'Podcast', url: 'https://example.test/audio.mp3',
  position: 42, duration: 3600, playedAt: '2026-11-04T20:00:00.000Z',
}]
const sessions = [{
  ...history[0], sessionId: 'session-1', startedAt: '2026-11-04T19:00:00.000Z', updatedAt: '2026-11-04T20:00:00.000Z',
}]

class MemoryHistoryStore implements HistoryStore {
  values = new Map<string, unknown[]>()
  failWrites = false

  async read(key: string) { return this.values.get(key) }
  async write(key: string, value: unknown[]) {
    if (this.failWrites) throw new DOMException('quota', 'QuotaExceededError')
    this.values.set(key, structuredClone(value))
  }
}

describe('historique IndexedDB', () => {
  it('migre exactement les deux historiques legacy sans modifier leurs copies localStorage', async () => {
    const legacyHistory = JSON.stringify(history)
    const legacySessions = JSON.stringify(sessions)
    localStorage.setItem(PLAYBACK_HISTORY_KEY, legacyHistory)
    localStorage.setItem(LISTENING_SESSIONS_KEY, legacySessions)
    const store = new MemoryHistoryStore()

    await expect(createHistoryRepository(store).load()).resolves.toEqual({ history, sessions })
    expect(store.values.get(PLAYBACK_HISTORY_KEY)).toStrictEqual(history)
    expect(store.values.get(LISTENING_SESSIONS_KEY)).toStrictEqual(sessions)
    expect(localStorage.getItem(PLAYBACK_HISTORY_KEY)).toBe(legacyHistory)
    expect(localStorage.getItem(LISTENING_SESSIONS_KEY)).toBe(legacySessions)
  })

  it('relit IndexedDB au lancement suivant quand les copies legacy sont indisponibles', async () => {
    const store = new MemoryHistoryStore()
    store.values.set(PLAYBACK_HISTORY_KEY, structuredClone(history))
    store.values.set(LISTENING_SESSIONS_KEY, structuredClone(sessions))

    await expect(createHistoryRepository(store).load()).resolves.toEqual({ history, sessions })
  })

  it('conserve les nouvelles reprises et sessions après un redémarrage de l’application', async () => {
    const store = new MemoryHistoryStore()
    const nextHistory = [{ ...history[0], position: 84, playedAt: '2026-11-04T21:00:00.000Z' }]
    const nextSessions = [{ ...sessions[0], position: 84, playedAt: '2026-11-04T21:00:00.000Z', updatedAt: '2026-11-04T21:00:00.000Z' }]

    await expect(createHistoryRepository(store).saveHistory(nextHistory)).resolves.toBe(true)
    await expect(createHistoryRepository(store).saveSessions(nextSessions)).resolves.toBe(true)
    await expect(createHistoryRepository(store).load()).resolves.toEqual({ history: nextHistory, sessions: nextSessions })
  })

  it('conserve les deux copies legacy et leur repli quand IndexedDB refuse une migration', async () => {
    const legacyHistory = JSON.stringify(history)
    const legacySessions = JSON.stringify(sessions)
    localStorage.setItem(PLAYBACK_HISTORY_KEY, legacyHistory)
    localStorage.setItem(LISTENING_SESSIONS_KEY, legacySessions)
    const store = new MemoryHistoryStore()
    store.failWrites = true

    await expect(createHistoryRepository(store).load()).resolves.toEqual({ history, sessions })
    expect(store.values.size).toBe(0)
    expect(localStorage.getItem(PLAYBACK_HISTORY_KEY)).toBe(legacyHistory)
    expect(localStorage.getItem(LISTENING_SESSIONS_KEY)).toBe(legacySessions)
    expect(getStorageFailures()).toEqual(['historique d’écoute'])
  })

  it('ne retire le bandeau historique qu’après une écriture IndexedDB réussie de la clé concernée', async () => {
    const store = new MemoryHistoryStore()
    store.failWrites = true
    const repository = createHistoryRepository(store)
    reportStorageFailure(LISTENING_SESSIONS_KEY)

    await expect(repository.saveHistory(history)).resolves.toBe(false)
    expect(getStorageFailures()).toEqual(['historique d’écoute'])
    store.failWrites = false
    await expect(repository.saveHistory(history)).resolves.toBe(true)
    expect(getStorageFailures()).toEqual(['historique d’écoute'])
    await expect(repository.saveSessions(sessions)).resolves.toBe(true)
    expect(getStorageFailures()).toEqual([])

    store.failWrites = true
    await expect(repository.saveHistory(history)).resolves.toBe(false)
    expect(getStorageFailures()).toEqual(['historique d’écoute'])
  })
})
