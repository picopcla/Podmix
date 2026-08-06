import { podmixPlayer } from './nativePlayer'
import { Capacitor } from '@capacitor/core'

export type UpdateManifest = {
  versionCode: number
  versionName: string
  minimumVersionCode: number
  apkUrl: string
  publishedAt: string
  sha256: string
  notes: string[]
}

export type UpdateProgress = {
  status: 'queued' | 'downloading' | 'completed' | 'failed' | 'installing'
  progress: number
  bytesDownloaded?: number
  totalBytes?: number
}

export type UpdateProgressCallback = (progress: UpdateProgress) => void

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

export async function downloadAndUpdate(
  update: UpdateManifest,
  onProgress?: UpdateProgressCallback
): Promise<void> {
  if (!Capacitor.isNativePlatform()) {
    window.open(update.apkUrl, '_blank', 'noopener,noreferrer')
    return
  }

  // Télécharger l'APK via DownloadManager natif
  const { requestId } = await podmixPlayer.downloadApk(update.apkUrl, update.versionName)
  
  // Polling pour suivre la progression
  while (true) {
    const status = await podmixPlayer.getApkStatus(requestId)
    
    if (onProgress) {
      onProgress({
        status: status.status as UpdateProgress['status'],
        progress: status.progress ?? 0,
        bytesDownloaded: status.bytesDownloaded,
        totalBytes: status.totalBytes
      })
    }

    if (status.status === 'completed') {
      // Installation
      if (onProgress) {
        onProgress({ status: 'installing', progress: 1 })
      }
      await podmixPlayer.installApk(status.path, status.localUri)
      return
    }

    if (status.status === 'failed') {
      throw new Error('Téléchargement de la mise à jour échoué')
    }

    // Attendre avant le prochain poll
    await new Promise(resolve => setTimeout(resolve, 500))
  }
}

export function openUpdate(update: UpdateManifest) {
  // Legacy: ouvrir directement l'URL (fallback)
  window.open(update.apkUrl, '_blank', 'noopener,noreferrer')
}
