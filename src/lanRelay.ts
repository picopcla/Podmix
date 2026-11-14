// Sélection du relais audio pour une enceinte Bose SoundTouch.
// C'est l'enceinte qui télécharge le flux : l'URL doit être joignable
// depuis le Wi-Fi domestique, pas depuis le téléphone ni le serveur.

// Vrai seulement pour une adresse IPv4 littérale RFC 1918 (LAN domestique
// typique). Une adresse CGNAT 100.64.0.0/10 (Tailscale) ou un nom d'hôte
// non résoluble ici ne prouvent pas que l'enceinte pourra joindre le relais.
export function isPrivateLanUrl(value: string | undefined | null): boolean {
  if (!value) return false
  let host: string
  try {
    host = new URL(value).hostname
  } catch {
    return false
  }
  const match = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(host)
  if (!match) return false
  const a = Number(match[1])
  const b = Number(match[2])
  if (a === 10) return true
  if (a === 192 && b === 168) return true
  if (a === 172 && b >= 16 && b <= 31) return true
  return false
}

// Le mode UPnP SoundTouch exige HTTP, pas un repli HTTPS silencieux.
export function selectBoseRelayUrl(relay: { relayUrl: string; lanRelayUrl?: string }): string {
  for (const candidate of [relay.lanRelayUrl, relay.relayUrl]) {
    if (!candidate) continue
    try {
      const url = new URL(candidate)
      if (url.protocol !== 'http:' || url.username || url.password || url.hash) continue
      if (candidate === relay.lanRelayUrl && !isPrivateLanUrl(candidate)) continue
      return candidate
    } catch { /* Essayer l'autre adresse, sans convertir HTTPS en HTTP. */ }
  }
  throw new Error('Aucun relais HTTP compatible SoundTouch n’est disponible. Le relais HTTPS ne peut pas être lu par ce mode Bose.')
}
