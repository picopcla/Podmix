// @vitest-environment jsdom

import { describe, expect, it } from 'vitest'
import { awaitCastState, CastStateTimeout, checkCastState } from '../../src/castState'

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

describe('awaitCastState', () => {
  it('preserve le meme objet et les metadonnees de session', async () => {
    const session = { connected: true, deviceName: 'QA enceinte', sessionId: 'qa-id' }
    expect(await awaitCastState(async () => session, 100)).toBe(session)
  })
  it('rejette une attente infinie sans inventer une deconnexion', async () => {
    await expect(awaitCastState(() => new Promise(() => undefined), 10)).rejects.toBeInstanceOf(CastStateTimeout)
  })
  it('preserve un rejet natif tel quel', async () => {
    const error = new Error('Erreur native QA')
    await expect(awaitCastState(async () => { throw error }, 100)).rejects.toBe(error)
  })
  it('accepte un etat valide arrive avant echeance', async () => {
    const state = { connected: true, deviceName: 'QA' }
    expect(await awaitCastState(() => new Promise(resolve => setTimeout(() => resolve(state), 5)), 100)).toBe(state)
  })
})
