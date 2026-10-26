/**
 * Stockage durable des donnees Podmix.
 *
 * Probleme corrige : le localStorage d'une WebView a un quota de quelques Mo ;
 * quand il est plein, plus rien ne s'enregistre. Les donnees (bibliotheque,
 * historiques, favoris, reglages...) vivent donc dans IndexedDB, dont le quota
 * depend de l'espace disque du telephone et non de quelques Mo.
 *
 * INVARIANTS
 * 1. IndexedDB fait autorite, SAUF si le localStorage a change depuis la derniere
 *    ecriture IndexedDB de la cle (retour a une ancienne version, repli apres
 *    echec) : on compare l'empreinte du localStorage notee dans l'entree
 *    (`lsSeen`) a son contenu actuel, sans horloge. Si elles different, la valeur
 *    du localStorage est adoptee et l'ancienne valeur IndexedDB est gardee dans
 *    une entree `podmix-recovered:<cle>:<instant>`. Une cle absente du localStorage ne supprime jamais rien.
 * 2. Une copie n'est reconnue qu'une fois relue identique (`verified`). Une
 *    entree non verifiee (interruption) est recopiee au lancement suivant.
 * 3. Le localStorage n'est jamais vide ni purge. Pour une cle geree par IndexedDB,
 *    cette version n'y ecrit plus rien : seule une ancienne version peut le
 *    modifier (d'ou l'adoption de l'invariant 1). Seules les cles dont la copie
 *    a echoue (mode « legacy ») continuent d'etre ecrites dans le localStorage.
 *    Limite connue : une ancienne version qui remet exactement l'ancien contenu
 *    est indiscernable d'un localStorage inchange.
 * 4. "Accepte" n'est pas "enregistre" : une ecriture est enregistree quand la
 *    transaction IndexedDB est terminee ; avant, elle est en attente (kvPending)
 *    et une coupure brutale peut la perdre. Un echec final est signale.
 * 5. Une cle supprimee laisse une pierre tombale pour ne pas ressusciter depuis
 *    l'ancienne copie ; si elle ne peut pas etre ecrite, l'echec est signale.
 */

export const DB_NAME = 'podmix-kv'
const DB_VERSION = 1
const STORE = 'kv'
const PREFIX = 'podmix-'

type Entry = {
  key: string
  value: string
  migratedFrom?: 'localStorage'
  deleted?: boolean
  /** true une fois la valeur relue identique (copie) ou ecrite par l'application. */
  verified?: boolean
  /** Empreinte du localStorage de cette cle au moment de l'ecriture IndexedDB. */
  lsSeen?: string
  at: string
}

export type KvBackend = {
  getAll(): Promise<Entry[]>
  get(key: string): Promise<Entry | undefined>
  put(entry: Entry): Promise<void>
  remove(key: string): Promise<void>
}

export function indexedDbBackend(factory: IDBFactory = indexedDB): Promise<KvBackend> {
  return new Promise((resolve, reject) => {
    const open = factory.open(DB_NAME, DB_VERSION)
    open.onupgradeneeded = () => {
      if (!open.result.objectStoreNames.contains(STORE)) open.result.createObjectStore(STORE, { keyPath: 'key' })
    }
    open.onerror = () => reject(open.error)
    open.onblocked = () => reject(new Error('IndexedDB bloquee'))
    open.onsuccess = () => {
      const db = open.result
      const run = <T,>(mode: IDBTransactionMode, action: (store: IDBObjectStore) => IDBRequest<T> | undefined) =>
        new Promise<T | undefined>((done, fail) => {
          const tx = db.transaction(STORE, mode)
          const request = action(tx.objectStore(STORE))
          tx.oncomplete = () => done(request?.result)
          tx.onerror = () => fail(tx.error)
          tx.onabort = () => fail(tx.error ?? new Error('transaction annulee'))
        })
      resolve({
        getAll: async () => ((await run<Entry[]>('readonly', (store) => store.getAll())) ?? []),
        get: (key) => run<Entry | undefined>('readonly', (store) => store.get(key)),
        put: async (entry) => { await run('readwrite', (store) => store.put(entry)) },
        remove: async (key) => { await run('readwrite', (store) => store.delete(key)) },
      })
    }
  })
}

type Report = (key: string) => void
type State = {
  backend?: KvBackend
  cache: Map<string, string>
  /** Cles restees en mode localStorage (copie impossible ou non verifiee). */
  legacy: Set<string>
  onFailure?: Report
  onSuccess?: Report
  queue: Map<string, string | null>
  flushing: boolean
  ready: boolean
  initializing: boolean
  /** Ecritures acceptees pendant l'initialisation, appliquees a sa fin. */
  preInit: Map<string, string | null>
  /** Empreinte du localStorage deja reconciliee, par cle : ce que flush() note dans lsSeen (jamais une relecture). */
  lsBase: Map<string, string>
  /** Issue de la derniere ecriture IndexedDB de la cle (effacee a chaque nouvelle acceptation). */
  outcome: Map<string, 'ok' | 'failed'>
  /** Numero de la derniere ecriture en attente, par cle (une ecriture plus recente remplace la precedente). */
  tokens: Map<string, number>
  /** Appelants qui attendent la fin de LEUR ecriture : cle -> numero -> resolution. */
  waiters: Map<string, Map<number, (ok: boolean) => void>>
  /** Derniere valeur connue comme ecrite durablement (null : absente). */
  durable: Map<string, string | null>
  /** Cles dont l'adoption a echoue : empreinte du localStorage deja vu (a noter des qu'une ecriture plus recente la remplace). */
  adoptFailed: Map<string, string>
  seq: number
}

const state: State = { cache: new Map(), legacy: new Set(), queue: new Map(), flushing: false, ready: false, initializing: false, preInit: new Map(), lsBase: new Map(), outcome: new Map(), tokens: new Map(), waiters: new Map(), durable: new Map(), adoptFailed: new Map(), seq: 0 }
let initPromise: Promise<MigrationResult> | undefined

export type MigrationResult = {
  mode: 'indexeddb' | 'localstorage'
  migrated: string[]
  alreadyThere: string[]
  /** Cles dont le localStorage etait plus recent que IndexedDB (retour de version, repli) : adoptees. */
  adopted: string[]
  kept: string[]
  error?: string
}

/** Empreinte d'une valeur localStorage (longueur + deux hachages) ; pas de valeur stockee en double. */
export function fingerprint(value: string | null): string {
  if (value === null) return 'null'
  let a = 0x811c9dc5
  let b = 5381
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index)
    a = Math.imul(a ^ code, 0x01000193) >>> 0
    b = (Math.imul(b, 33) ^ code) >>> 0
  }
  return `${value.length}:${a.toString(16)}:${b.toString(16)}`
}

function settle(key: string, token: number | undefined, ok: boolean) {
  if (token === undefined) return
  const resolve = state.waiters.get(key)?.get(token)
  if (resolve) { state.waiters.get(key)?.delete(token); resolve(ok) }
}

/** Une ecriture plus recente remplace celle qui attendait encore : l'ancienne n'a jamais ete ecrite. */
function supersede(key: string) {
  const old = state.tokens.get(key)
  settle(key, old, false)
}

function applyPreInitToLocal(storage: Storage) {
  for (const [key, value] of state.preInit) {
    const token = state.tokens.get(key)
    state.tokens.delete(key)
    try { if (value === null) storage.removeItem(key); else storage.setItem(key, value); settle(key, token, true) } catch { state.onFailure?.(key); settle(key, token, false) }
  }
  state.preInit = new Map()
}

function lsGet(storage: Storage, key: string): string | null {
  try { return storage.getItem(key) } catch { return null }
}

/** A appeler avant le rendu. Un seul appel actif (les suivants reutilisent le meme). Ne bloque pas sur une erreur. */
export function initKvStore(
  options: { backend?: KvBackend | Promise<KvBackend>; storage?: Storage; onFailure?: Report; onSuccess?: Report; force?: boolean } = {},
): Promise<MigrationResult> {
  if (initPromise && !options.force) return initPromise
  state.initializing = true
  initPromise = runInit(options).finally(() => { state.initializing = false })
  return initPromise
}

async function runInit(
  options: { backend?: KvBackend | Promise<KvBackend>; storage?: Storage; onFailure?: Report; onSuccess?: Report },
): Promise<MigrationResult> {
  const storage = options.storage ?? localStorage
  state.onFailure = options.onFailure
  state.onSuccess = options.onSuccess
  state.cache = new Map(); state.legacy = new Set(); state.queue = new Map(); state.backend = undefined; state.ready = false; state.lsBase = new Map(); state.durable = new Map(); state.adoptFailed = new Map(); state.outcome = new Map()
  const result: MigrationResult = { mode: 'localstorage', migrated: [], alreadyThere: [], adopted: [], kept: [] }
  let backend: KvBackend
  let existing: Entry[]
  try {
    backend = await (options.backend ?? indexedDbBackend())
    existing = await backend.getAll()
  } catch (error) {
    result.error = error instanceof Error ? error.message : 'IndexedDB indisponible'
    state.ready = true
    applyPreInitToLocal(storage)
    return result
  }
  const stored = new Map(existing.map((entry) => [entry.key, entry]))
  const legacyKeys: string[] = []
  try {
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index)
      if (key?.startsWith(PREFIX)) legacyKeys.push(key)
    }
  } catch { /* localStorage illisible : on ne migre rien de plus */ }

  // `lsSeen` est l'empreinte de la valeur COPIEE (pas une relecture du localStorage : une ecriture concurrente ne doit pas etre masquee).
  const writeVerified = async (key: string, value: string, extra: Partial<Entry>) => {
    const base: Entry = { key, value, verified: false, lsSeen: fingerprint(value), at: new Date().toISOString(), ...extra }
    await backend.put(base)
    const check = await backend.get(key)
    if (check?.value !== value) throw new Error('relecture differente')
    await backend.put({ ...base, verified: true })
  }

  // 1. Entrees IndexedDB : verifiees = autorite sauf si le localStorage a change depuis.
  for (const entry of existing) {
    const lsValue = lsGet(storage, entry.key)
    const verified = entry.verified !== false // les entrees sans champ viennent de l'application
    const lsChanged = lsValue !== null && entry.lsSeen !== undefined && fingerprint(lsValue) !== entry.lsSeen
    if (!verified) {
      stored.delete(entry.key) // copie interrompue : jamais exposee ; refaite depuis le localStorage si la source existe
      continue
    }
    if (lsChanged) {
      try {
        // L'ancienne valeur IndexedDB est gardee a part (une ecriture suivante de la cle n'ecrase pas cette copie).
        if (!entry.deleted) await backend.put({ key: `podmix-recovered:${entry.key}:${Date.now()}`, value: entry.value, verified: true, at: new Date().toISOString() })
        await writeVerified(entry.key, lsValue, {})
        state.lsBase.set(entry.key, fingerprint(lsValue))
        state.cache.set(entry.key, lsValue)
        state.durable.set(entry.key, lsValue)
        result.adopted.push(entry.key)
      } catch {
        // Adoption non verifiee : la valeur la plus recente (localStorage) est servie ; la cle reste geree par IndexedDB
        // (suppression par pierre tombale) et l'adoption sera retentee au lancement suivant tant que l'ecart existe.
        state.cache.set(entry.key, lsValue)
        result.kept.push(entry.key) // lsBase inchange : l'ecart sera de nouveau constate au lancement suivant
        state.lsBase.set(entry.key, entry.lsSeen ?? 'null')
        state.adoptFailed.set(entry.key, fingerprint(lsValue))
        state.durable.set(entry.key, entry.deleted ? null : entry.value)
      }
      continue
    }
    state.lsBase.set(entry.key, entry.lsSeen ?? fingerprint(lsValue))
    if (!entry.deleted) state.cache.set(entry.key, entry.value)
    state.durable.set(entry.key, entry.deleted ? null : entry.value)
    result.alreadyThere.push(entry.key)
  }

  // 2. Cles du localStorage absentes (ou non verifiees) dans IndexedDB : copie verifiee.
  for (const key of legacyKeys) {
    if (stored.has(key)) continue
    const value = lsGet(storage, key)
    if (value === null) continue
    try {
      await writeVerified(key, value, { migratedFrom: 'localStorage' })
      state.lsBase.set(key, fingerprint(value))
      state.cache.set(key, value)
      state.durable.set(key, value)
      result.migrated.push(key)
    } catch {
      state.legacy.add(key)
      state.cache.set(key, value)
      result.kept.push(key)
    }
  }
  state.backend = backend
  state.ready = true
  result.mode = 'indexeddb'
  // Ecritures acceptees pendant l'init : elles l'emportent (plus recentes que tout ce qui vient d'etre lu).
  for (const [key, value] of state.preInit) {
    if (state.legacy.has(key)) { // cle restee en mode localStorage : l'operation se fait dans le localStorage
      const token = state.tokens.get(key)
      state.tokens.delete(key)
      try { if (value === null) storage.removeItem(key); else storage.setItem(key, value); settle(key, token, true) } catch { state.onFailure?.(key); settle(key, token, false) }
      if (value === null) state.cache.delete(key); else state.cache.set(key, value)
    } else {
      noteAdoptFailedSuperseded(key)
      if (value === null) state.cache.delete(key); else state.cache.set(key, value)
      state.queue.set(key, value)
    }
  }
  state.preInit = new Map()
  void flush()
  return result
}

/** Ecrire par-dessus une valeur localStorage non adoptee : cette valeur est desormais depassee, on la note comme deja vue. */
function noteAdoptFailedSuperseded(key: string) {
  const seen = state.adoptFailed.get(key)
  if (seen !== undefined) { state.lsBase.set(key, seen); state.adoptFailed.delete(key) }
}

export function kvGetItem(key: string): string | null {
  if (state.initializing && !state.ready && state.preInit.has(key)) return state.preInit.get(key) ?? null
  if (state.ready && state.backend && key.startsWith(PREFIX) && !state.legacy.has(key)) return state.cache.get(key) ?? null
  return localStorage.getItem(key)
}

export function kvPending(): number {
  return state.queue.size + state.preInit.size + (state.flushing ? 1 : 0)
}

async function flush() {
  if (state.flushing || !state.backend) return
  state.flushing = true
  try {
    while (state.queue.size) {
      const [key, value] = state.queue.entries().next().value as [string, string | null]
      state.queue.delete(key)
      const deleted = value === null
      const token = state.tokens.get(key)
      state.tokens.delete(key)
      try {
        await state.backend.put({
          key, value: value ?? '', deleted: deleted || undefined, verified: true,
          lsSeen: state.lsBase.get(key) ?? fingerprint(null), at: new Date().toISOString(),
        })
        state.outcome.set(key, 'ok')
        state.durable.set(key, value)
        settle(key, token, true)
        state.onSuccess?.(key) // transaction terminee : c'est ici que la sauvegarde est confirmee
      } catch {
        // Pas de repli localStorage (il masquerait ou serait masque au redemarrage) : echec signale, valeur gardee en memoire.
        state.outcome.set(key, 'failed')
        state.onFailure?.(key)
        settle(key, token, false)
      }
    }
  } finally {
    state.flushing = false
  }
}

/** 'local' : la cle n'est pas geree par IndexedDB (appelant : ecrire en localStorage). true : acceptee, enregistree a la fin de la transaction. */
export function kvSetItem(key: string, value: string): boolean | 'local' {
  if (state.initializing && !state.ready && key.startsWith(PREFIX)) { supersede(key); state.preInit.set(key, value); state.tokens.set(key, ++state.seq); return true }
  if (!(state.ready && state.backend && key.startsWith(PREFIX)) || state.legacy.has(key)) return 'local'
  supersede(key)
  noteAdoptFailedSuperseded(key)
  state.cache.set(key, value)
  state.outcome.delete(key)
  state.tokens.set(key, ++state.seq)
  state.queue.set(key, value)
  void flush()
  return true
}

export function kvRemoveItem(key: string): void {
  if (state.initializing && !state.ready && key.startsWith(PREFIX)) { supersede(key); state.preInit.set(key, null); state.tokens.set(key, ++state.seq); return }
  if (!(state.ready && state.backend && key.startsWith(PREFIX)) || state.legacy.has(key)) {
    localStorage.removeItem(key)
    return
  }
  supersede(key)
  noteAdoptFailedSuperseded(key)
  state.cache.delete(key)
  state.tokens.set(key, ++state.seq)
  state.queue.set(key, null)
  void flush()
}

/** Attend la fin des ecritures en attente (tests, mise en arriere-plan). */
export async function kvFlush(): Promise<void> {
  if (initPromise && state.initializing) await initPromise.catch(() => undefined)
  while (state.flushing || state.queue.size) {
    void flush()
    await new Promise((resolve) => setTimeout(resolve, 5))
  }
}

/**
 * Accepte l'ecriture puis attend la fin de SA transaction. Renvoie true seulement si elle est confirmee
 * durablement. Pour une cle non geree par IndexedDB, ecrit dans le localStorage (false si refuse).
 * En cas d'echec, la valeur precedente est remise dans le cache (rien n'est presente comme enregistre).
 */
export async function kvSetItemConfirmed(key: string, value: string): Promise<boolean> {
  // Le numero de l'ecriture est connu avant de l'accepter : le flush peut demarrer et finir pendant kvSetItem.
  const token = state.seq + 1
  let resolveWrite: (ok: boolean) => void = () => undefined
  const result = new Promise<boolean>((resolve) => { resolveWrite = resolve })
  let waiters = state.waiters.get(key)
  if (!waiters) { waiters = new Map(); state.waiters.set(key, waiters) }
  waiters.set(token, resolveWrite)
  const accepted = kvSetItem(key, value)
  if (accepted === 'local') {
    waiters.delete(token)
    try { localStorage.setItem(key, value); return true } catch { return false }
  }
  void kvFlush()
  const ok = await result
  if (!ok && state.tokens.get(key) === undefined && state.cache.get(key) === value) {
    // Echec et aucune ecriture plus recente : on revient a la derniere valeur reellement enregistree.
    const durable = state.durable.get(key)
    if (durable === undefined || durable === null) state.cache.delete(key); else state.cache.set(key, durable)
  }
  return ok
}
