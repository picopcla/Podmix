import { describe, expect, it } from 'vitest'
import { isPrivateLanUrl, selectBoseRelayUrl } from '../../src/lanRelay'

describe('isPrivateLanUrl', () => {
  it('accepte les adresses RFC 1918', () => {
    expect(isPrivateLanUrl('http://192.168.1.50:8099/v1/cast/x')).toBe(true)
    expect(isPrivateLanUrl('http://10.0.0.8:8099/x')).toBe(true)
    expect(isPrivateLanUrl('http://172.16.0.2/x')).toBe(true)
    expect(isPrivateLanUrl('http://172.31.255.255/x')).toBe(true)
  })
  it('rejette Tailscale CGNAT 100.64.0.0/10', () => {
    expect(isPrivateLanUrl('http://100.116.28.23:8099/v1/cast/x')).toBe(false)
    expect(isPrivateLanUrl('http://100.96.25.96/x')).toBe(false)
  })
  it('rejette les adresses publiques, hors plage 172.16/12 et invalides', () => {
    expect(isPrivateLanUrl('http://172.32.0.1/x')).toBe(false)
    expect(isPrivateLanUrl('https://podmix.mb4.fr/x')).toBe(false)
    expect(isPrivateLanUrl('http://8.8.8.8/x')).toBe(false)
    expect(isPrivateLanUrl('')).toBe(false)
    expect(isPrivateLanUrl(undefined)).toBe(false)
    expect(isPrivateLanUrl('pas une url')).toBe(false)
  })
})

describe('selectBoseRelayUrl', () => {
  const relayUrl = 'https://podmix.mb4.fr/podmix-cast/v1/cast/token'
  it('préfère le relais LAN privé', () => {
    expect(selectBoseRelayUrl({ relayUrl, lanRelayUrl: 'http://192.168.1.50:8099/v1/cast/token' }))
      .toBe('http://192.168.1.50:8099/v1/cast/token')
  })
  it('ignore un relais « LAN » en adresse Tailscale', () => {
    expect(selectBoseRelayUrl({ relayUrl, lanRelayUrl: 'http://100.116.28.23:8099/v1/cast/token' }))
      .toBe(relayUrl)
  })
  it('utilise le relais public sans lanRelayUrl', () => {
    expect(selectBoseRelayUrl({ relayUrl })).toBe(relayUrl)
  })
})
