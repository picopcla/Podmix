// Vérification bornée de l'état Google Cast.
// Sur certains appareils, Play services laisse la requête d'état pendante
// indéfiniment ; sans garde, la sélection d'une enceinte tournait puis
// restait silencieuse. 'unknown-timeout' est distinct de 'disconnected' :
// un délai dépassé ne prouve ni connexion ni déconnexion et ne doit
// réinitialiser ni déconnecter aucune session.

export type CastStateCheck = 'connected' | 'disconnected' | 'unknown-timeout'

const TIMEOUT = Symbol('cast-state-timeout')

export async function checkCastState(
  player: { getCastState(): Promise<{ connected: boolean }> },
  timeoutMs = 5_000,
): Promise<CastStateCheck> {
  const result = await Promise.race([
    player.getCastState().then((state): CastStateCheck => (state.connected ? 'connected' : 'disconnected')),
    new Promise<typeof TIMEOUT>((resolve) => {
      setTimeout(() => resolve(TIMEOUT), timeoutMs)
    }),
  ])
  return result === TIMEOUT ? 'unknown-timeout' : result
}
