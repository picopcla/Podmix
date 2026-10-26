// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { getStorageFailures, safeSetItem } from '../../src/safeStorage'
import { saveCatalog } from '../../src/storage'
import { getApiUrl, setApiUrl } from '../../src/api'

function full() {
  return vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new DOMException('quota', 'QuotaExceededError')
  })
}

describe('safeSetItem', () => {
  it('renvoie true quand la valeur est enregistrée', () => {
    expect(safeSetItem('podmix-settings-v1', '{}')).toBe(true)
    expect(localStorage.getItem('podmix-settings-v1')).toBe('{}')
  })
  it('renvoie false si le quota refuse, sans rien effacer', () => {
    localStorage.setItem('keep', 'x')
    const spy = full()
    expect(safeSetItem('podmix-track-favorites-v1', '[1]')).toBe(false)
    spy.mockRestore()
    expect(localStorage.getItem('keep')).toBe('x')
  })
  it('relance les erreurs autres que le quota', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('autre') })
    expect(() => safeSetItem('k', 'v')).toThrow('autre')
    spy.mockRestore()
  })
  it('une réussite sur une autre clé ne masque pas un échec critique non résolu', () => {
    const spy = full()
    expect(safeSetItem('podmix-track-favorites-v1', '[1]')).toBe(false)
    spy.mockRestore()
    expect(safeSetItem('podmix-settings-v1', '{}')).toBe(true)
    expect(getStorageFailures()).toEqual(['favoris'])
    expect(safeSetItem('podmix-track-favorites-v1', '[1]')).toBe(true)
    expect(getStorageFailures()).toEqual([])
  })
  it('un échec non critique ne déclenche pas de bandeau', () => {
    const spy = full()
    expect(safeSetItem('podmix-active-timestamp-source-v1', 'x')).toBe(false)
    spy.mockRestore()
    expect(getStorageFailures()).toEqual([])
  })
  it('saveCatalog conserve le catalogue complet déjà stocké si l’écriture est refusée et signale l’échec', () => {
    const source = { id: 's', kind: 'podcast', title: 'P', description: 'desc complète', artworkUrl: '', feedUrl: 'u', episodes: [{ id: 'e', title: 'E', description: 'description longue', publishedAt: '2026-01-01', duration: '1', audioUrl: 'a', artworkUrl: '' }] }
    // @ts-expect-error objet de test minimal
    expect(saveCatalog([source])).toBe(true)
    const stored = localStorage.getItem('podmix-catalog-v1')
    const spy = full()
    // @ts-expect-error objet de test minimal
    expect(saveCatalog([{ ...source, title: 'Autre' }])).toBe(false)
    spy.mockRestore()
    expect(localStorage.getItem('podmix-catalog-v1')).toBe(stored)
    expect(getStorageFailures()).toContain('bibliothèque')
    // @ts-expect-error objet de test minimal
    expect(saveCatalog([source])).toBe(true)
    expect(getStorageFailures()).not.toContain('bibliothèque')
  })
  it('saveCatalog n’écrit jamais une version réduite sans descriptions', () => {
    const calls: string[] = []
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation((_k: string, v: string) => {
      calls.push(v)
      throw new DOMException('quota', 'QuotaExceededError')
    })
    const source = { id: 's', kind: 'podcast', title: 'P', description: 'desc', artworkUrl: '', feedUrl: 'u', episodes: [{ id: 'e', title: 'E', description: 'description a garder', publishedAt: '2026-01-01', duration: '1', audioUrl: 'a', artworkUrl: '' }] }
    // @ts-expect-error objet de test minimal
    saveCatalog([source])
    spy.mockRestore()
    expect(calls).toHaveLength(1)
    expect(calls[0]).toContain('description a garder')
  })
  it('setApiUrl ne renvoie pas une adresse non enregistrée et garde l’ancienne', () => {
    expect(setApiUrl('http://ancienne.test:8099')).toBe('http://ancienne.test:8099')
    const spy = full()
    expect(() => setApiUrl('http://nouvelle.test:8099')).toThrow(/n’est pas enregistrée/)
    spy.mockRestore()
    expect(getApiUrl()).toBe('http://ancienne.test:8099')
  })
})
