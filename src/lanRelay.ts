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

// Version HTTP du relais public, en dernier recours.
//
// Mesuré sur une SoundTouch 30 (firmware 27.0.6) le 15/11/2026 : SetAVTransportURI
// avec l'URL HTTPS du relais répond HTTP 500 (l'enceinte ne négocie pas TLS), alors
// que la même URL en http:// (même nom d'hôte public, port par défaut) est acceptée
// et lue (PLAY_STATE, temps qui avance). Le relais sert de l'audio en HTTP simple.
//
// Garde-fous : on ne convertit QUE du HTTPS vers un nom de domaine (jamais une
// adresse IP, jamais localhost), sur le port par défaut (pas de port explicite qui
// n'existerait pas en HTTP), sans identifiants ni fragment. Le jeton de session
// est dans le chemin et reste inchangé.
export function httpVariantOfPublicRelay(value: string | undefined | null): string | undefined {
  if (!value) return undefined
  try {
    const url = new URL(value)
    if (url.protocol !== 'https:' || url.username || url.password || url.hash || url.port) return undefined
    const host = url.hostname.toLowerCase()
    if (!host.includes('.') || host === 'localhost' || host.endsWith('.local')) return undefined
    if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host) || host.includes(':')) return undefined
    url.protocol = 'http:'
    return url.toString()
  } catch {
    return undefined
  }
}

// Le mode UPnP SoundTouch exige HTTP. Ordre : relais LAN privé RFC 1918, relais
// HTTP explicite, puis la variante HTTP du relais public (voir ci-dessus).
export function selectBoseRelayUrl(relay: { relayUrl: string; lanRelayUrl?: string }): string {
  for (const candidate of [relay.lanRelayUrl, relay.relayUrl]) {
    if (!candidate) continue
    try {
      const url = new URL(candidate)
      if (url.protocol !== 'http:' || url.username || url.password || url.hash) continue
      if (candidate === relay.lanRelayUrl && !isPrivateLanUrl(candidate)) continue
      return candidate
    } catch { /* Essayer l'adresse suivante. */ }
  }
  const publicHttp = httpVariantOfPublicRelay(relay.relayUrl)
  if (publicHttp) return publicHttp
  throw new Error('Aucun relais HTTP compatible SoundTouch n’est disponible. Le relais HTTPS ne peut pas être lu par ce mode Bose.')
}
