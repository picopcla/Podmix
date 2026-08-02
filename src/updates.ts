export type UpdateManifest = {
  versionCode: number
  versionName: string
  minimumVersionCode: number
  apkUrl: string
  publishedAt: string
  sha256: string
  notes: string[]
}

export const currentVersion = import.meta.env.VITE_APP_VERSION ?? '1.0.0'
const manifestUrl = import.meta.env.VITE_UPDATE_MANIFEST_URL ?? ''

function versionCode(version: string): number {
  const [major = 0, minor = 0, patch = 0] = version.split('.').map(Number)
  return major * 10000 + minor * 100 + patch
}

export async function checkForUpdate(): Promise<UpdateManifest | null> {
  if (!manifestUrl) return null
  const response = await fetch(`${manifestUrl}?t=${Date.now()}`, { cache: 'no-store' })
  if (!response.ok) throw new Error(`Service de mise à jour indisponible (${response.status})`)
  const update = await response.json() as UpdateManifest
  return update.versionCode > versionCode(currentVersion) ? update : null
}

export function openUpdate(update: UpdateManifest) {
  window.open(update.apkUrl, '_blank', 'noopener,noreferrer')
}
