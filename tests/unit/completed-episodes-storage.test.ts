// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getStorageFailures, reportStorageSuccess } from '../../src/safeStorage'
import {
  COMPLETED_EPISODES_KEY,
  createCompletedEpisodesRepository,
  type CompletedEpisodesStore,
} from '../../src/history'

class MemoryCompletedEpisodesStore implements CompletedEpisodesStore {
  value: string[] | undefined
  failWrites = false

  async read() { return this.value === undefined ? undefined : structuredClone(this.value) }
  async write(value: string[]) {
    if (this.failWrites) throw new DOMException('quota', 'QuotaExceededError')
    this.value = structuredClone(value)
  }
}

beforeEach(() => {
  localStorage.clear()
  reportStorageSuccess(COMPLETED_EPISODES_KEY)
})

describe('épisodes terminés IndexedDB', () => {
  it('migre les identifiants legacy validés et conserve la chaîne legacy strictement inchangée', async () => {
    const legacy = JSON.stringify(['episode-2', 'episode-1', 'episode-2', '', 4])
    localStorage.setItem(COMPLETED_EPISODES_KEY, legacy)
    const store = new MemoryCompletedEpisodesStore()

    await expect(createCompletedEpisodesRepository(store).load()).resolves.toEqual(['episode-2', 'episode-1'])
    expect(store.value).toStrictEqual(['episode-2', 'episode-1'])
    expect(localStorage.getItem(COMPLETED_EPISODES_KEY)).toBe(legacy)
  })

  it('privilégie une copie IndexedDB migrée valide sans l’écraser par une copie legacy différente', async () => {
    localStorage.setItem(COMPLETED_EPISODES_KEY, JSON.stringify(['legacy-plus-récent']))
    const store = new MemoryCompletedEpisodesStore()
    store.value = ['indexeddb-fiable']

    await expect(createCompletedEpisodesRepository(store).load()).resolves.toEqual(['indexeddb-fiable'])
    expect(store.value).toStrictEqual(['indexeddb-fiable'])
  })

  it('double-écrit les nouveaux états vers IndexedDB et legacy pour permettre un retour arrière', async () => {
    localStorage.setItem(COMPLETED_EPISODES_KEY, JSON.stringify(['avant']))
    const store = new MemoryCompletedEpisodesStore()
    const repository = createCompletedEpisodesRepository(store)
    await repository.load()

    await expect(repository.save(['après', 'avant'])).resolves.toBe(true)
    expect(store.value).toStrictEqual(['après', 'avant'])
    expect(localStorage.getItem(COMPLETED_EPISODES_KEY)).toBe(JSON.stringify(['après', 'avant']))
  })

  it('conserve le legacy et signale le bandeau quand IndexedDB refuse la migration', async () => {
    const legacy = JSON.stringify(['conservé'])
    localStorage.setItem(COMPLETED_EPISODES_KEY, legacy)
    const store = new MemoryCompletedEpisodesStore()
    store.failWrites = true

    await expect(createCompletedEpisodesRepository(store).load()).resolves.toEqual(['conservé'])
    expect(store.value).toBeUndefined()
    expect(localStorage.getItem(COMPLETED_EPISODES_KEY)).toBe(legacy)
    expect(getStorageFailures()).toEqual(['épisodes terminés'])
  })

  it('garde le legacy précédent si sa double-écriture atteint le quota mais confirme la sauvegarde IndexedDB', async () => {
    const legacy = JSON.stringify(['avant'])
    localStorage.setItem(COMPLETED_EPISODES_KEY, legacy)
    const store = new MemoryCompletedEpisodesStore()
    const repository = createCompletedEpisodesRepository(store)
    await repository.load()
    const original = Storage.prototype.setItem
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (key: string, value: string) {
      if (key === COMPLETED_EPISODES_KEY) throw new DOMException('quota', 'QuotaExceededError')
      return original.call(this, key, value)
    })

    await expect(repository.save(['dans-indexeddb'])).resolves.toBe(true)
    spy.mockRestore()
    expect(store.value).toStrictEqual(['dans-indexeddb'])
    expect(localStorage.getItem(COMPLETED_EPISODES_KEY)).toBe(legacy)
    expect(getStorageFailures()).not.toContain('épisodes terminés')
  })

  it('retire le bandeau seulement après une écriture IndexedDB réussie', async () => {
    const store = new MemoryCompletedEpisodesStore()
    store.failWrites = true
    const repository = createCompletedEpisodesRepository(store)

    await expect(repository.save(['échec'])).resolves.toBe(false)
    expect(getStorageFailures()).toContain('épisodes terminés')
    store.failWrites = false
    await expect(repository.save(['succès'])).resolves.toBe(true)
    expect(getStorageFailures()).not.toContain('épisodes terminés')
  })
})
