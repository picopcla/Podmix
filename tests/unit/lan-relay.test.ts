import { describe, expect, it } from 'vitest'
import { isPrivateLanUrl, selectBoseRelayUrl } from '../../src/lanRelay'
// Transport constraints from actual SoundTouch fw 27.0.6 reports, not app output.
// https://github.com/gesellix/Bose-SoundTouch/pull/532
const relayUrl = 'https://podmix.mb4.fr/podmix-cast/v1/cast/token'
describe('SoundTouch UPnP relay transport', () => {
  it('recognizes private LAN but not Tailscale', () => {
    for (const host of ['192.168.1.50','10.0.0.8','172.16.0.2','172.31.255.255']) expect(isPrivateLanUrl(`http://${host}/audio`)).toBe(true)
    for (const host of ['100.116.28.23','172.32.0.1','8.8.8.8']) expect(isPrivateLanUrl(`http://${host}/audio`)).toBe(false)
  })
  it('uses the actual HTTP LAN endpoint', () => {
    const lanRelayUrl = 'http://192.168.1.50:8099/v1/cast/token'
    expect(selectBoseRelayUrl({ relayUrl, lanRelayUrl })).toBe(lanRelayUrl)
  })
  it('does not silently send HTTPS when LAN is absent, invalid or CGNAT', () => {
    for (const lanRelayUrl of [undefined,'not a URL','http://100.116.28.23/audio','https://192.168.1.50/audio']) {
      expect(() => selectBoseRelayUrl({ relayUrl, lanRelayUrl })).toThrow('HTTP')
    }
  })
  it('can use an explicitly supplied public HTTP relay without rewriting TLS', () => {
    expect(selectBoseRelayUrl({ relayUrl: 'http://audio.example.org/relay' })).toBe('http://audio.example.org/relay')
    expect(() => selectBoseRelayUrl({ relayUrl })).toThrow('HTTPS')
  })
  it('rejects credentials and fragments', () => {
    for (const url of ['http://user:pass@host/audio','http://host/audio#x']) expect(() => selectBoseRelayUrl({ relayUrl: url })).toThrow()
  })
})
