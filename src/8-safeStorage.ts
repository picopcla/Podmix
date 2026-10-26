import { useEffect, useState, useSyncExternalStore } from 'react'
import { kvPending, kvSetItem } from './kvStore'

/**
 * Ecriture localStorage qui ne casse pas l'interface quand le quota est atteint.
 *
 * Garanties :
 * - aucune donnee existante n'est supprimee ni purgee ;
 * - un echec n'est jamais presente comme une sauvegarde reussie : safeSetItem
 *   renvoie false et, pour les donnees de l'utilisateur, l'echec est expose a
 *   l'interface (voir useStorageFailures) jusqu'a la prochaine ecriture reussie ;
 * - l'etat reste en memoire dans l'application, la prochaine ecriture reussie
 *   enregistre la valeur la plus recente de chaque cle.
 * Cela evite le plantage, mais ne cree pas de place : tant que le stockage
 * reste plein, les ecritures refusees ne sont pas enregistrees.
 */

type KeyInfo = { label: string; critical: boolean }

const KNOWN_KEYS: Record<string, KeyInfo> = {
  'podmix-track-favorites-v1': { label: 'favoris', critical: true },
  'podmix-settings-v1': { label: 'réglages', critical: true },
  'podmix-home-section-order-v1': { label: 'ordre de l’accueil', critical: true },
  'podmix-history-v1': { label: 'historique d’écoute', critical: true },
  'podmix-listening-sessions-v1': { label: 'historique d’écoute', critical: true },
  'podmix-completed-episodes-v1': { label: 'épisodes terminés', critical: true },
  'podmix-offline-v1': { label: 'téléchargements', critical: true },
  'podmix-catalog-v1': { label: 'bibliothèque', critical: true },
  'podmix-api-url': { label: 'adresse du serveur', critical: true },
  // Etat de travail reconstructible : un echec n'enleve aucune donnee de l'utilisateur.
  'podmix-active-timestamp-source-v1': { label: 'file d’analyse', critical: false },
  'podmix-new-episode-timestamp-queue-v2': { label: 'file d’analyse', critical: false },
  'podmix-rss-timestamp-baseline-sources-v1': { label: 'file d’analyse', critical: false },
  'podmix-last-feed-refresh': { label: 'dernière actualisation', critical: false },
  'podmix-player-volume': { label: 'volume', critical: false },
  'podmix-bose-ip': { label: 'enceinte Bose', critical: false },
  'podmix-bose-session-v1': { label: 'enceinte Bose', critical: false },
  'podmix-bose-output-selection-v1': { label: 'enceinte Bose', critical: false },
}

// Une cle inconnue est traitee comme donnee de l'utilisateur (prudence).
function infoFor(key: string): KeyInfo {
  return KNOWN_KEYS[key] ?? { label: 'données', critical: true }
}

const failedKeys = new Map<string, string>()
const listeners = new Set<() => void>()
let snapshot = ''
let warned = false

function publish() {
  const next = [...new Set(failedKeys.values())].sort().join('|')
  if (next === snapshot) return
  snapshot = next
  listeners.forEach((listener) => listener())
}

export function reportStorageFailure(key: string): void {
  const info = infoFor(key)
  if (!warned) {
    warned = true
    console.warn(`Stockage local plein : écriture refusée (${key}). Rien n’est supprimé ; elle n’est pas enregistrée.`)
  }
  if (info.critical) failedKeys.set(key, info.label)
  publish()
}

export function reportStorageSuccess(key: string): void {
  if (failedKeys.delete(key)) publish()
}

export function isQuotaError(error: unknown): boolean {
  return error instanceof DOMException
    && (error.name === 'QuotaExceededError' || error.name === 'NS_ERROR_DOM_QUOTA_REACHED' || error.code === 22)
}

/** Renvoie true si la valeur a ete enregistree, false si le quota l'a refusee. */
export function safeSetItem(key: string, value: string): boolean {
  // Donnees Podmix : stockage durable (IndexedDB) quand il est disponible.
  // true = acceptee ; la confirmation (ou l'echec) arrive a la fin de la transaction (voir kvStore).
  if (kvSetItem(key, value) === true) return true
  try {
    localStorage.setItem(key, value)
    reportStorageSuccess(key)
    return true
  } catch (error) {
    if (!isQuotaError(error)) throw error
    reportStorageFailure(key)
    return false
  }
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

/** Libelles des donnees de l'utilisateur dont la derniere sauvegarde a echoue. */
export function useStorageFailures(): string[] {
  const value = useSyncExternalStore(subscribe, () => snapshot, () => '')
  return value ? value.split('|') : []
}

export function getStorageFailures(): string[] {
  return snapshot ? snapshot.split('|') : []
}

/** true si des ecritures acceptees ne sont pas encore confirmees depuis plus d'une seconde (etat « en attente »). */
export function useWritesPending(): boolean {
  const [pending, setPending] = useState(false)
  useEffect(() => {
    let previous = 0
    const timer = window.setInterval(() => {
      const now = kvPending()
      setPending(now > 0 && previous > 0)
      previous = now
    }, 700)
    return () => window.clearInterval(timer)
  }, [])
  return pending
}
