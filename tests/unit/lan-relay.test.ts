import { describe, expect, it } from 'vitest'
import { httpVariantOfPublicRelay, isPrivateLanUrl, selectBoseRelayUrl } from '../../src/lanRelay'
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
  // Mesure reelle (SoundTouch 30, fw 27.0.6, 15/11/2026) : l'URL HTTPS du relais est refusee
  // (SetAVTransportURI -> HTTP 500), la meme URL en http:// est acceptee et lue.
  it('falls back to the HTTP variant of the public relay when LAN is absent, invalid or CGNAT', () => {
    const expected = 'http://podmix.mb4.fr/podmix-cast/v1/cast/token'
    for (const lanRelayUrl of [undefined,'not a URL','http://100.116.28.23:8099/v1/cast/token','https://192.168.1.50/audio']) {
      expect(selectBoseRelayUrl({ relayUrl, lanRelayUrl })).toBe(expected)
    }
  })
  it('still prefers a real private LAN relay over the public HTTP variant', () => {
    const lanRelayUrl = 'http://192.168.1.50:8099/v1/cast/token'
    expect(selectBoseRelayUrl({ relayUrl, lanRelayUrl })).toBe(lanRelayUrl)
  })
  it('only converts HTTPS to HTTP for a public DNS name on the default port', () => {
    expect(httpVariantOfPublicRelay('https://podmix.mb4.fr/podmix-cast/v1/cast/token')).toBe('http://podmix.mb4.fr/podmix-cast/v1/cast/token')
    for (const url of [
      'https://192.168.1.50/audio', 'https://8.8.8.8/audio', 'https://100.116.28.23/audio',
      'https://localhost/audio', 'https://nas.local/audio', 'https://hote/audio',
      'https://podmix.mb4.fr:8443/audio', 'https://user:pass@podmix.mb4.fr/audio', 'https://podmix.mb4.fr/audio#x',
      'http://podmix.mb4.fr/audio', 'not a url', '', undefined, null,
    ]) expect(httpVariantOfPublicRelay(url as string | undefined | null)).toBeUndefined()
  })
  it('fails clearly when no HTTP relay can be built', () => {
    expect(() => selectBoseRelayUrl({ relayUrl: 'https://192.168.1.50/relay' })).toThrow('HTTP')
    expect(() => selectBoseRelayUrl({ relayUrl: 'https://podmix.mb4.fr:8443/relay' })).toThrow('HTTP')
  })
  it('can use an explicitly supplied public HTTP relay without rewriting', () => {
    expect(selectBoseRelayUrl({ relayUrl: 'http://audio.example.org/relay' })).toBe('http://audio.example.org/relay')
  })
  it('rejects credentials and fragments', () => {
    for (const url of ['http://user:pass@host/audio','http://host/audio#x']) expect(() => selectBoseRelayUrl({ relayUrl: url })).toThrow()
  })
})
