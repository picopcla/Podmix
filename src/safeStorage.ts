/**
 * Ecriture localStorage qui ne casse pas l'interface quand le quota est atteint.
 * Aucune donnee existante n'est supprimee : si l'espace manque, l'ecriture
 * demandee est simplement ignoree (l'etat reste en memoire dans l'application).
 */
let quotaWarned = false

export function safeSetItem(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch (error) {
    const quota = error instanceof DOMException
      && (error.name === 'QuotaExceededError' || error.name === 'NS_ERROR_DOM_QUOTA_REACHED' || error.code === 22)
    if (!quota) throw error
    if (!quotaWarned) {
      quotaWarned = true
      console.warn(`Stockage local plein : ecriture ignoree (${key}), aucune donnee supprimee.`)
    }
  }
}
