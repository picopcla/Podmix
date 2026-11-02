// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { getStorageFailures, reportStorageFailure } from '../../src/safeStorage'
import { createCatalogRepository, type CatalogStore } from '../../src/storage'

const source = {
  id: 'source-1', kind: 'podcast' as const, title: 'Catalogue conservé', description: 'Description complète', artworkUrl: '', feedUrl: 'https://example.test/feed.xml',
  episodes: [{
    id: 'episode-1', title: 'Épisode conservé', description: 'Description complète de l’épisode', publishedAt: '2026-11-02', duration: '1:00', audioUrl: 'https://example.test/audio.mp3', artworkUrl: '',
    tracks: [{ id: 1, time: 0, artist: 'Artiste', title: 'Titre', confidence: 100, source: 'manual' as const, evidence: ['preuve complète'] }],
  }],
}

class MemoryCatalogStore implements CatalogStore {
  value: typeof source[] | undefined
  failWrites = false

  async read() { return this.value }
  async write(value: typeof source[]) {
    if (this.failWrites) throw new DOMException('quota', 'QuotaExceededError')
    this.value = structuredClone(value)
  }
}

describe('catalogue IndexedDB', () => {
  it('migre exactement le catalogue legacy sans supprimer sa copie localStorage', async () => {
    const legacy = JSON.stringify([source])
    localStorage.setItem('podmix-catalog-v1', legacy)
    const store = new MemoryCatalogStore()
    const repository = createCatalogRepository(store)

    await expect(repository.load()).resolves.toEqual([source])
    expect(store.value).toEqual([source])
    expect(localStorage.getItem('podmix-catalog-v1')).toBe(legacy)
  })

  it('relit IndexedDB au lancement suivant sans écraser le catalogue migré', async () => {
    const store = new MemoryCatalogStore()
    const firstLaunch = createCatalogRepository(store)
    localStorage.setItem('podmix-catalog-v1', JSON.stringify([source]))
    await firstLaunch.load()
    localStorage.setItem('podmix-catalog-v1', '[]')

    await expect(createCatalogRepository(store).load()).resolves.toEqual([source])
  })

  it('signale le refus sans altérer ni IndexedDB ni la copie legacy, puis efface seulement bibliothèque après succès', async () => {
    const legacy = JSON.stringify([source])
    localStorage.setItem('podmix-catalog-v1', legacy)
    const store = new MemoryCatalogStore()
    store.value = structuredClone([source])
    store.failWrites = true
    const repository = createCatalogRepository(store)
    reportStorageFailure('podmix-track-favorites-v1')

    await expect(repository.save([{ ...source, title: 'Modification non enregistrée' }])).resolves.toBe(false)
    expect(store.value).toEqual([source])
    expect(localStorage.getItem('podmix-catalog-v1')).toBe(legacy)
    expect(getStorageFailures()).toEqual(['bibliothèque', 'favoris'])

    store.failWrites = false
    await expect(repository.save([{ ...source, title: 'Modification enregistrée' }])).resolves.toBe(true)
    expect(getStorageFailures()).toEqual(['favoris'])
  })

  it('enregistre un catalogue trop grand pour localStorage sans le réécrire dans localStorage', async () => {
    const legacy = JSON.stringify([source])
    localStorage.setItem('podmix-catalog-v1', legacy)
    const store = new MemoryCatalogStore()
    const repository = createCatalogRepository(store)
    const large = [{ ...source, description: 'x'.repeat(20_000) }]

    await expect(repository.save(large)).resolves.toBe(true)
    expect(store.value).toEqual(large)
    expect(localStorage.getItem('podmix-catalog-v1')).toBe(legacy)
  })
})
