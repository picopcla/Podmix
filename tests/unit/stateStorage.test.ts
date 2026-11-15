// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createDurableRepository, STATE_KEYS, type DurableStore } from '../../src/durableStorage'
import { getStorageFailures, reportStorageSuccess } from '../../src/safeStorage'

// Petites donnees utilisateur (favoris, reglages, ordre de l'accueil) : IndexedDB fait foi, la copie localStorage
// n'est qu'un miroir best-effort. Cas reel (15/11/2026) : quota localStorage du WebView plein -> bandeau
// « Sauvegarde impossible : favoris ». Aucun test ne supprime ni ne tronque de donnees.
class Store implements DurableStore {
  values = new Map<string, unknown>(); failRead = false; failWrite = false; writes: string[] = []
  async read(key: string) { if (this.failRead) throw new DOMException('blocked', 'UnknownError'); return this.values.get(key) }
  async write(key: string, value: string) {
    if (this.failWrite) throw new DOMException('quota', 'QuotaExceededError')
    this.writes.push(key); this.values.set(key, value)
  }
}

const FAV = 'podmix-track-favorites-v1'
const SETTINGS = 'podmix-settings-v1'
const ORDER = 'podmix-home-section-order-v1'
const quotaFull = () => vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('full', 'QuotaExceededError') })

beforeEach(() => { localStorage.clear(); for (const k of STATE_KEYS) reportStorageSuccess(k) })
afterEach(() => { vi.restoreAllMocks() })

describe('user state durable persistence (favorites, settings, home order)', () => {
  it('copies each legacy value exactly and leaves the legacy copy untouched', async () => {
    const s = new Store(), r = createDurableRepository(s)
    localStorage.setItem(FAV, '["a","b"]'); localStorage.setItem(SETTINGS, '{"theme":"dark","x":1}'); localStorage.setItem(ORDER, '["radios","podcasts"]')
    await r.initialize()
    expect(r.read(FAV)).toBe('["a","b"]'); expect(r.read(SETTINGS)).toBe('{"theme":"dark","x":1}'); expect(r.read(ORDER)).toBe('["radios","podcasts"]')
    expect(s.values.get(FAV)).toBe('["a","b"]'); expect(s.values.get(SETTINGS)).toBe('{"theme":"dark","x":1}')
    expect(localStorage.getItem(FAV)).toBe('["a","b"]'); expect(localStorage.getItem(SETTINGS)).toBe('{"theme":"dark","x":1}')
    expect(getStorageFailures()).toEqual([])
  })

  it('writes nothing at startup when there is nothing to migrate', async () => {
    const s = new Store(), r = createDurableRepository(s)
    await r.initialize()
    expect(s.writes.filter((k) => (STATE_KEYS as readonly string[]).includes(k))).toEqual([])
    expect(r.read(FAV)).toBeNull()
  })

  it('saves favorites when localStorage is full, without any failure banner and without touching the old copy', async () => {
    const s = new Store(), r = createDurableRepository(s)
    localStorage.setItem(FAV, '["old"]')
    await r.initialize()
    quotaFull()
    expect(await r.save(FAV, '["old","new"]')).toBe(true)
    expect(s.values.get(FAV)).toBe('["old","new"]')
    expect(r.read(FAV)).toBe('["old","new"]')
    expect(getStorageFailures()).toEqual([])
    vi.restoreAllMocks()
    expect(localStorage.getItem(FAV)).toBe('["old"]') // ancienne copie conservee telle quelle
  })

  it('clears a previous failure banner once the IndexedDB record is verified', async () => {
    const s = new Store(), r = createDurableRepository(s)
    s.failWrite = true
    expect(await r.save(FAV, '["x"]')).toBe(false)
    expect(getStorageFailures()).toContain('favoris')
    s.failWrite = false
    expect(await r.save(FAV, '["x"]')).toBe(true)
    expect(getStorageFailures()).toEqual([])
  })

  it('mirrors to localStorage when it has room so a rollback still sees recent data', async () => {
    const s = new Store(), r = createDurableRepository(s)
    await r.initialize()
    await r.save(SETTINGS, '{"a":1}')
    expect(localStorage.getItem(SETTINGS)).toBe('{"a":1}')
  })

  it('keeps the latest value in memory and the old data on disk when IndexedDB fails', async () => {
    const s = new Store(), r = createDurableRepository(s)
    localStorage.setItem(FAV, '["old"]')
    await r.initialize()
    s.failWrite = true
    expect(await r.save(FAV, '["old","new"]')).toBe(false)
    expect(getStorageFailures()).toContain('favoris')
    expect(r.read(FAV)).toBe('["old","new"]')
    expect(localStorage.getItem(FAV)).toBe('["old"]')
    expect(s.values.get(FAV)).toBe('["old"]')
  })

  it('ignores a malformed legacy value silently and still saves a valid one', async () => {
    const s = new Store(), r = createDurableRepository(s)
    localStorage.setItem(SETTINGS, '{not json'); localStorage.setItem(FAV, '"not an array"')
    await r.initialize()
    expect(getStorageFailures()).toEqual([])
    expect(localStorage.getItem(SETTINGS)).toBe('{not json') // jamais reecrit ni supprime
    expect(await r.save(SETTINGS, '{"ok":true}')).toBe(true)
    expect(s.values.get(SETTINGS)).toBe('{"ok":true}')
  })

  it('never overwrites an existing IndexedDB record it could not read', async () => {
    const s = new Store(), r = createDurableRepository(s)
    s.values.set(FAV, '["precious"]'); s.failRead = true
    await r.initialize()
    s.failRead = false
    expect(await r.save(FAV, '[]')).toBe(false)
    expect(s.values.get(FAV)).toBe('["precious"]')
  })

  it('refuses a payload of the wrong shape instead of storing it', async () => {
    const s = new Store(), r = createDurableRepository(s)
    await r.initialize()
    expect(await r.save(FAV, '{"a":1}')).toBe(false)
    expect(await r.save(ORDER, '[1,2]')).toBe(false)
    expect(await r.save(SETTINGS, '[]')).toBe(false)
    expect(s.writes.filter((k) => (STATE_KEYS as readonly string[]).includes(k))).toEqual([])
  })
})
