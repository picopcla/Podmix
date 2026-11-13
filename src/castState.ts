// Bornage d'une lecture d'etat. Un delai depasse ne prouve pas une deconnexion.
export class CastStateTimeout extends Error {
  constructor() {
    super("Impossible de vérifier l'état Cast (délai dépassé)")
    this.name = 'CastStateTimeout'
  }
}

export async function awaitCastState<T>(request: () => Promise<T>, timeoutMs = 5_000): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    return await Promise.race([
      request(),
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new CastStateTimeout()), timeoutMs)
      }),
    ])
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
}

export type CastStateCheck = 'connected' | 'disconnected' | 'unknown-timeout'
export async function checkCastState(
  player: { getCastState(): Promise<{ connected: boolean }> }, timeoutMs = 5_000,
): Promise<CastStateCheck> {
  try {
    const state = await awaitCastState(() => player.getCastState(), timeoutMs)
    return state.connected ? 'connected' : 'disconnected'
  } catch (error) {
    if (error instanceof CastStateTimeout) return 'unknown-timeout'
    throw error
  }
}
