// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { getStorageFailures, safeSetItem } from '../../src/safeStorage'

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
})
