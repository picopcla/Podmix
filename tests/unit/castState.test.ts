// @vitest-environment jsdom

import { describe, expect, it } from 'vitest'
import { checkCastState } from '../../src/castState'

describe('checkCastState', () => {
  it('renvoie connected quand la session est active', async () => {
    const player = { getCastState: async () => ({ connected: true }) }
    expect(await checkCastState(player, 200)).toBe('connected')
  })

  it('renvoie disconnected quand la déconnexion est réelle', async () => {
    const player = { getCastState: async () => ({ connected: false }) }
    expect(await checkCastState(player, 200)).toBe('disconnected')
  })

  it('renvoie unknown-timeout quand la promesse reste pendante', async () => {
    const player = { getCastState: () => new Promise<{ connected: boolean }>(() => undefined) }
    expect(await checkCastState(player, 50)).toBe('unknown-timeout')
  })

  it('propage le rejet natif sans le transformer', async () => {
    const player = {
      getCastState: async () => {
        throw new Error('Play services indisponible')
      },
    }
    await expect(checkCastState(player, 200)).rejects.toThrow('Play services indisponible')
  })
})
