// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createBoseCastSession, setApiUrl } from '../../src/api'

function sessionPayload(lanRelayUrl?: string) {
  return {
    id: 'tok',
    relayUrl: 'https://podmix.mb4.fr/podmix-cast/v1/cast/tok',
    ...(lanRelayUrl ? { lanRelayUrl } : {}),
    expiresAt: '2026-11-12T00:00:00Z',
    startSeconds: 0,
  }
}

function mockFetch(payload: object, status = 201) {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(payload), { status })))
}

describe('createBoseCastSession (fetch mocké, aucun service réel)', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('écarte un lanRelayUrl Tailscale 100.64/10 : repli relais public', async () => {
    setApiUrl('http://serveur.test:8099')
    mockFetch(sessionPayload('http://100.116.28.23:8099/v1/cast/tok'))
    const session = await createBoseCastSession('https://example.com/a.mp3', 't')
    expect(session.lanRelayUrl).toBeUndefined()
    expect(session.relayUrl).toBe('https://podmix.mb4.fr/podmix-cast/v1/cast/tok')
  })

  it('conserve un lanRelayUrl RFC 1918 (vrai LAN domestique)', async () => {
    setApiUrl('http://serveur.test:8099')
    mockFetch(sessionPayload('http://192.168.1.50:8099/v1/cast/tok'))
    const session = await createBoseCastSession('https://example.com/a.mp3', 't')
    expect(session.lanRelayUrl).toBe('http://192.168.1.50:8099/v1/cast/tok')
  })

  it('propage une erreur serveur sans masquer le message', async () => {
    setApiUrl('http://serveur.test:8099')
    mockFetch({ message: 'Relais Bose indisponible (500)' }, 500)
    await expect(createBoseCastSession('https://example.com/a.mp3', 't'))
      .rejects.toThrow('Relais Bose indisponible (500)')
  })
})
