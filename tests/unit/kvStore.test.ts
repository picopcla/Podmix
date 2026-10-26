// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fingerprint, initKvStore, kvFlush, kvGetItem, kvPending, kvRemoveItem, kvSetItem, kvSetItemConfirmed, type KvBackend } from '../../src/kvStore'
import { safeSetItem } from '../../src/safeStorage'

type Entry = Parameters<KvBackend['put']>[0]
function memoryBackend(initial: Entry[] = [], hooks: { failPut?: (key: string) => boolean; corrupt?: (key: string) => boolean } = {}) {
  const data = new Map(initial.map((entry) => [entry.key, entry]))
  const backend: KvBackend = {
    getAll: async () => [...data.values()],
    get: async (key) => {
      const entry = data.get(key)
      return entry && hooks.corrupt?.(key) ? { ...entry, value: entry.value + 'X' } : entry
    },
    put: async (entry) => {
      if (hooks.failPut?.(entry.key)) throw new DOMException('quota', 'QuotaExceededError')
      data.set(entry.key, entry)
    },
    remove: async (key) => { data.delete(key) },
  }
  return { backend, data }
}

const seed = () => {
  localStorage.setItem('podmix-catalog-v1', JSON.stringify([{ id: 's', episodes: [{ id: 'e', description: 'é'.repeat(50) }] }]))
  localStorage.setItem('podmix-history-v1', JSON.stringify([{ id: 'h', title: 't' }]))
  localStorage.setItem('podmix-listening-sessions-v1', JSON.stringify([{ sessionId: 'a', id: 'h' }]))
  localStorage.setItem('podmix-track-favorites-v1', '["f1"]')
  localStorage.setItem('autre-appli', 'ne pas toucher')
}
const snapshot = () => JSON.stringify(Object.entries(localStorage).sort())

describe('kvStore : migration localStorage vers IndexedDB', () => {
  beforeEach(() => { localStorage.clear(); vi.restoreAllMocks() })

  it('copie et verifie chaque cle podmix-*, sans toucher au localStorage', async () => {
    seed()
    const before = snapshot()
    const { backend, data } = memoryBackend()
    const result = await initKvStore({ backend, force: true })
    expect(result.mode).toBe('indexeddb')
    expect(result.migrated.sort()).toEqual(['podmix-catalog-v1', 'podmix-history-v1', 'podmix-listening-sessions-v1', 'podmix-track-favorites-v1'])
    expect(result.kept).toEqual([])
    for (const key of result.migrated) expect(data.get(key)?.value).toBe(localStorage.getItem(key))
    expect(data.has('autre-appli')).toBe(false)
    expect(snapshot()).toBe(before)
    expect(kvGetItem('podmix-track-favorites-v1')).toBe('["f1"]')
  })

  it('relancer ne recopie pas et ne fait rien perdre', async () => {
    seed()
    const { backend } = memoryBackend()
    await initKvStore({ backend, force: true })
    const again = await initKvStore({ backend, force: true })
    expect(again.migrated).toEqual([])
    expect(again.alreadyThere).toHaveLength(4)
    expect(kvGetItem('podmix-history-v1')).toBe(localStorage.getItem('podmix-history-v1'))
  })

  it('une ecriture refusee laisse la cle dans le localStorage, puis la migration reprend au lancement suivant', async () => {
    seed()
    const first = memoryBackend([], { failPut: (key) => key === 'podmix-catalog-v1' })
    const r1 = await initKvStore({ backend: first.backend, force: true })
    expect(r1.kept).toEqual(['podmix-catalog-v1'])
    expect(kvGetItem('podmix-catalog-v1')).toBe(localStorage.getItem('podmix-catalog-v1'))
    expect(kvSetItem('podmix-catalog-v1', '[]')).toBe('local')
    const second = memoryBackend(await first.backend.getAll())
    const r2 = await initKvStore({ backend: second.backend, force: true })
    expect(r2.migrated).toEqual(['podmix-catalog-v1'])
    expect(second.data.get('podmix-catalog-v1')?.value).toBe(localStorage.getItem('podmix-catalog-v1'))
  })

  it('une copie qui ne se relit pas identique n est pas retenue', async () => {
    seed()
    const { backend, data } = memoryBackend([], { corrupt: (key) => key === 'podmix-history-v1' })
    const result = await initKvStore({ backend, force: true })
    expect(result.kept).toEqual(['podmix-history-v1'])
    expect(data.get('podmix-history-v1')?.verified).toBe(false)
    expect(kvGetItem('podmix-history-v1')).toBe(localStorage.getItem('podmix-history-v1'))
  })

  it('IndexedDB indisponible : mode localStorage, rien ne casse', async () => {
    seed()
    const result = await initKvStore({ backend: Promise.reject(new Error('indisponible')), force: true })
    expect(result.mode).toBe('localstorage')
    expect(kvGetItem('podmix-track-favorites-v1')).toBe('["f1"]')
    expect(safeSetItem('podmix-track-favorites-v1', '["f2"]')).toBe(true)
    expect(localStorage.getItem('podmix-track-favorites-v1')).toBe('["f2"]')
  })
})

describe('kvStore : ecriture, relecture, quota', () => {
  beforeEach(() => { localStorage.clear(); vi.restoreAllMocks() })

  it('sauvegarde et relit meme quand le localStorage refuse tout (plein)', async () => {
    seed()
    const { backend, data } = memoryBackend()
    await initKvStore({ backend, force: true })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('plein', 'QuotaExceededError') })
    const big = JSON.stringify(Array.from({ length: 20000 }, (_, i) => ({ id: `e${i}`, d: 'x'.repeat(200) })))
    expect(safeSetItem('podmix-catalog-v1', big)).toBe(true)
    expect(safeSetItem('podmix-track-favorites-v1', '["f1","f2"]')).toBe(true)
    await kvFlush()
    expect(kvGetItem('podmix-catalog-v1')).toBe(big)
    expect(data.get('podmix-catalog-v1')?.value).toBe(big)
    // Nouveau lancement : relecture depuis le stockage durable.
    vi.restoreAllMocks()
    const relaunch = await initKvStore({ backend, force: true })
    expect(relaunch.mode).toBe('indexeddb')
    expect(kvGetItem('podmix-catalog-v1')).toBe(big)
    expect(kvGetItem('podmix-track-favorites-v1')).toBe('["f1","f2"]')
  })

  it('la derniere valeur gagne quand plusieurs ecritures se suivent', async () => {
    const { backend, data } = memoryBackend()
    await initKvStore({ backend, force: true })
    for (let i = 0; i < 50; i += 1) safeSetItem('podmix-settings-v1', JSON.stringify({ i }))
    await kvFlush()
    expect(data.get('podmix-settings-v1')?.value).toBe('{"i":49}')
  })

  it('une cle supprimee ne ressuscite pas depuis l ancienne copie localStorage', async () => {
    seed()
    const { backend } = memoryBackend()
    await initKvStore({ backend, force: true })
    kvRemoveItem('podmix-history-v1')
    await kvFlush()
    expect(kvGetItem('podmix-history-v1')).toBeNull()
    await initKvStore({ backend, force: true })
    expect(kvGetItem('podmix-history-v1')).toBeNull()
    expect(localStorage.getItem('podmix-history-v1')).not.toBeNull()
  })

  it('si IndexedDB et localStorage refusent, l echec est signale', async () => {
    const { backend } = memoryBackend([], { failPut: () => true })
    const onFailure = vi.fn()
    await initKvStore({ backend, onFailure, force: true })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('plein', 'QuotaExceededError') })
    safeSetItem('podmix-track-favorites-v1', '["f1"]')
    await kvFlush()
    expect(onFailure).toHaveBeenCalledWith('podmix-track-favorites-v1')
  })
})

describe('kvStore : autorite, retour de version, interruptions, durabilite', () => {
  beforeEach(() => { localStorage.clear(); vi.restoreAllMocks() })
  const ls = () => localStorage

  it('IndexedDB garde l autorite quand le localStorage n a pas change', async () => {
    seed()
    const { backend } = memoryBackend()
    await initKvStore({ backend, force: true })
    safeSetItem('podmix-track-favorites-v1', '["nouveau"]')
    await kvFlush()
    const relaunch = await initKvStore({ backend, force: true })
    expect(relaunch.adopted).toEqual([])
    expect(kvGetItem('podmix-track-favorites-v1')).toBe('["nouveau"]')
  })

  it('retour a une ancienne version puis remise a jour : sa modification est adoptee, rien n est perdu', async () => {
    seed()
    const { backend, data } = memoryBackend()
    await initKvStore({ backend, force: true })
    safeSetItem('podmix-track-favorites-v1', '["depuis-la-nouvelle"]')
    await kvFlush()
    // L'ancienne version ecrit dans le localStorage (non versionne, sans horloge) :
    ls().setItem('podmix-track-favorites-v1', '["depuis-l-ancienne"]')
    ls().setItem('podmix-nouvelle-cle-v1', 'x')
    const up = await initKvStore({ backend, force: true })
    expect(up.adopted).toEqual(['podmix-track-favorites-v1'])
    expect(up.migrated).toContain('podmix-nouvelle-cle-v1')
    expect(kvGetItem('podmix-track-favorites-v1')).toBe('["depuis-l-ancienne"]')
    const recovered = [...data.values()].filter((entry) => entry.key.startsWith('podmix-recovered:podmix-track-favorites-v1:'))
    expect(recovered.map((entry) => entry.value)).toEqual(['["depuis-la-nouvelle"]'])
    safeSetItem('podmix-track-favorites-v1', '["apres"]'); await kvFlush()
    expect([...data.values()].some((entry) => entry.key.startsWith('podmix-recovered:') && entry.value === '["depuis-la-nouvelle"]')).toBe(true)
    // Et une troisieme fois, plus rien a adopter.
    const again = await initKvStore({ backend, force: true })
    expect(again.adopted).toEqual([])
    expect(kvGetItem('podmix-track-favorites-v1')).toBe('["apres"]')
  })

  it('une cle supprimee par l ancienne version ne supprime rien dans IndexedDB', async () => {
    seed()
    const { backend } = memoryBackend()
    await initKvStore({ backend, force: true })
    ls().removeItem('podmix-history-v1')
    await initKvStore({ backend, force: true })
    expect(kvGetItem('podmix-history-v1')).toBe(JSON.stringify([{ id: 'h', title: 't' }]))
  })

  it('echec IndexedDB : pas de repli localStorage, echec signale, valeur gardee en memoire, reprise a la prochaine ecriture', async () => {
    seed()
    let fail = false
    const { backend } = memoryBackend([], { failPut: () => fail })
    const onFailure = vi.fn()
    await initKvStore({ backend, onFailure, force: true })
    const before = ls().getItem('podmix-track-favorites-v1')
    fail = true
    safeSetItem('podmix-track-favorites-v1', '["B"]'); await kvFlush()
    expect(onFailure).toHaveBeenCalledWith('podmix-track-favorites-v1')
    expect(kvGetItem('podmix-track-favorites-v1')).toBe('["B"]')
    expect(ls().getItem('podmix-track-favorites-v1')).toBe(before)
    fail = false
    safeSetItem('podmix-track-favorites-v1', '["C"]'); await kvFlush()
    const relaunch = await initKvStore({ backend, force: true })
    expect(relaunch.adopted).toEqual([])
    expect(kvGetItem('podmix-track-favorites-v1')).toBe('["C"]')
  })

  it('ABA : une ecriture refusee ne cree plus de localStorage "identique" qui masquerait l etat IndexedDB', async () => {
    seed()
    const original = ls().getItem('podmix-track-favorites-v1')!
    let fail = false
    const { backend } = memoryBackend([], { failPut: () => fail })
    const onFailure = vi.fn()
    await initKvStore({ backend, onFailure, force: true })
    safeSetItem('podmix-track-favorites-v1', '["B"]'); await kvFlush()
    fail = true
    safeSetItem('podmix-track-favorites-v1', original); await kvFlush()
    expect(onFailure).toHaveBeenCalledWith('podmix-track-favorites-v1')
    expect(ls().getItem('podmix-track-favorites-v1')).toBe(original) // inchange : rien n'est ecrit dans le localStorage
  })

  it('interruption entre copie et verification : l entree non verifiee est refaite, pas crue', async () => {
    seed()
    const value = localStorage.getItem('podmix-track-favorites-v1')!
    const { backend, data } = memoryBackend([{ key: 'podmix-track-favorites-v1', value: 'TRONQUE', verified: false, lsSeen: fingerprint(value), at: 'x' }, { key: 'podmix-bose-ip', value: 'TRONQUE2', verified: false, at: 'x' }])
    const result = await initKvStore({ backend, force: true })
    expect(result.migrated).toContain('podmix-track-favorites-v1')
    expect(kvGetItem('podmix-track-favorites-v1')).toBe(value)
    expect(data.get('podmix-track-favorites-v1')?.verified).toBe(true)
    expect(kvGetItem('podmix-bose-ip')).toBeNull() // copie non verifiee dont la source n'existe plus : jamais exposee
  })

  it('une ecriture concurrente du localStorage pendant l adoption reste adoptee au lancement suivant', async () => {
    seed()
    const { backend } = memoryBackend()
    await initKvStore({ backend, force: true })
    ls().setItem('podmix-track-favorites-v1', '["B"]')
    const slow: KvBackend = { ...backend, put: async (entry) => {
      if (entry.key.startsWith('podmix-recovered:')) ls().setItem('podmix-track-favorites-v1', '["C"]')
      await backend.put(entry)
    } }
    await initKvStore({ backend: slow, force: true })
    expect(kvGetItem('podmix-track-favorites-v1')).toBe('["B"]')
    const next = await initKvStore({ backend, force: true })
    expect(next.adopted).toEqual(['podmix-track-favorites-v1'])
    expect(kvGetItem('podmix-track-favorites-v1')).toBe('["C"]')
  })

  it('adoption qui echoue : la valeur la plus recente est servie et une suppression laisse une pierre tombale', async () => {
    seed()
    const mem = memoryBackend()
    await initKvStore({ backend: mem.backend, force: true })
    ls().setItem('podmix-track-favorites-v1', '["B"]')
    const failing: KvBackend = { ...mem.backend, put: async (entry) => { if (entry.key.startsWith('podmix-recovered:')) throw new Error('refus'); await mem.backend.put(entry) } }
    const result = await initKvStore({ backend: failing, force: true })
    expect(result.kept).toEqual(['podmix-track-favorites-v1'])
    expect(kvGetItem('podmix-track-favorites-v1')).toBe('["B"]')
    kvRemoveItem('podmix-track-favorites-v1'); await kvFlush()
    expect(mem.data.get('podmix-track-favorites-v1')?.deleted).toBe(true)
  })

  it('ecriture acceptee pendant l initialisation : visible tout de suite et conservee apres l init', async () => {
    seed()
    const mem = memoryBackend()
    const slowGetAll: KvBackend = { ...mem.backend, getAll: async () => { await new Promise((r) => setTimeout(r, 20)); return mem.backend.getAll() } }
    const init = initKvStore({ backend: slowGetAll, force: true })
    expect(safeSetItem('podmix-track-favorites-v1', '["pendant-init"]')).toBe(true)
    expect(kvGetItem('podmix-track-favorites-v1')).toBe('["pendant-init"]')
    await init; await kvFlush()
    expect(kvGetItem('podmix-track-favorites-v1')).toBe('["pendant-init"]')
    expect(mem.data.get('podmix-track-favorites-v1')?.value).toBe('["pendant-init"]')
  })

  it('une cle restee en mode localStorage est lue depuis le localStorage (pas un cache perime)', async () => {
    seed()
    const { backend } = memoryBackend([], { failPut: (key) => key === 'podmix-track-favorites-v1' })
    await initKvStore({ backend, force: true })
    expect(safeSetItem('podmix-track-favorites-v1', '["B"]')).toBe(true)
    expect(kvGetItem('podmix-track-favorites-v1')).toBe('["B"]')
    kvRemoveItem('podmix-track-favorites-v1')
    expect(kvGetItem('podmix-track-favorites-v1')).toBeNull()
  })

  it('succes confirme seulement apres la transaction, pas a l acceptation', async () => {
    const gate = { release: () => {} }
    const wait = new Promise<void>((resolve) => { gate.release = resolve })
    const base = memoryBackend()
    const slow: KvBackend = { ...base.backend, put: async (entry) => { if (entry.key === 'podmix-settings-v1') await wait; await base.backend.put(entry) } }
    const onSuccess = vi.fn()
    await initKvStore({ backend: slow, onSuccess, force: true })
    expect(safeSetItem('podmix-settings-v1', '{}')).toBe(true)
    await new Promise((resolve) => setTimeout(resolve, 10))
    expect(onSuccess).not.toHaveBeenCalledWith('podmix-settings-v1')
    expect(kvPending()).toBeGreaterThan(0)
    gate.release(); await kvFlush()
    expect(onSuccess).toHaveBeenCalledWith('podmix-settings-v1')
    expect(kvPending()).toBe(0)
    expect(base.data.get('podmix-settings-v1')?.verified).toBe(true)
  })

  it('ecritures concurrentes sur plusieurs cles : chacune garde sa derniere valeur', async () => {
    const { backend, data } = memoryBackend()
    await initKvStore({ backend, force: true })
    for (let i = 0; i < 30; i += 1) {
      safeSetItem('podmix-settings-v1', `{"i":${i}}`)
      safeSetItem('podmix-track-favorites-v1', `["${i}"]`)
      safeSetItem('podmix-history-v1', `[${i}]`)
    }
    await kvFlush()
    expect(data.get('podmix-settings-v1')?.value).toBe('{"i":29}')
    expect(data.get('podmix-track-favorites-v1')?.value).toBe('["29"]')
    expect(data.get('podmix-history-v1')?.value).toBe('[29]')
  })

  it('une tombstone qui ne s ecrit pas est signalee et ne touche pas au localStorage', async () => {
    seed()
    let fail = false
    const { backend } = memoryBackend([], { failPut: () => fail })
    const onFailure = vi.fn()
    await initKvStore({ backend, onFailure, force: true })
    fail = true
    kvRemoveItem('podmix-history-v1'); await kvFlush()
    expect(onFailure).toHaveBeenCalledWith('podmix-history-v1')
    expect(ls().getItem('podmix-history-v1')).not.toBeNull()
  })

  it('deux initKvStore simultanes partagent la meme initialisation', async () => {
    seed()
    const { backend } = memoryBackend()
    const [a, b] = [initKvStore({ backend, force: true }), initKvStore({ backend })]
    expect(await a).toBe(await b)
  })

  it('ecriture pendant l init sur une cle devenue legacy : visible apres l init, ecrite dans le localStorage', async () => {
    seed()
    const mem = memoryBackend([], { failPut: (key) => key === 'podmix-track-favorites-v1' })
    const slow: KvBackend = { ...mem.backend, getAll: async () => { await new Promise((r) => setTimeout(r, 20)); return mem.backend.getAll() } }
    const init = initKvStore({ backend: slow, force: true })
    safeSetItem('podmix-track-favorites-v1', '["B"]')
    await init
    expect(kvGetItem('podmix-track-favorites-v1')).toBe('["B"]')
    expect(ls().getItem('podmix-track-favorites-v1')).toBe('["B"]')
  })

  it('kvPending et kvFlush tiennent compte des ecritures acceptees pendant l init', async () => {
    seed()
    const mem = memoryBackend()
    const slow: KvBackend = { ...mem.backend, getAll: async () => { await new Promise((r) => setTimeout(r, 20)); return mem.backend.getAll() } }
    const init = initKvStore({ backend: slow, force: true })
    safeSetItem('podmix-settings-v1', '{"x":1}')
    expect(kvPending()).toBeGreaterThan(0)
    await kvFlush()
    expect(mem.data.get('podmix-settings-v1')?.value).toBe('{"x":1}')
    await init
    expect(kvPending()).toBe(0)
  })

  it('une modification externe du localStorage pendant la session n est pas absorbee dans lsSeen', async () => {
    seed()
    const mem = memoryBackend()
    await initKvStore({ backend: mem.backend, force: true })
    ls().setItem('podmix-track-favorites-v1', '["D-externe"]')
    safeSetItem('podmix-track-favorites-v1', '["C"]'); await kvFlush()
    const next = await initKvStore({ backend: mem.backend, force: true })
    expect(next.adopted).toEqual(['podmix-track-favorites-v1'])
    expect(kvGetItem('podmix-track-favorites-v1')).toBe('["D-externe"]')
    expect([...mem.data.values()].some((e) => e.key.startsWith('podmix-recovered:') && e.value === '["C"]')).toBe(true)
  })

  it('setApiUrl attend la confirmation durable : echec IndexedDB = erreur et ancienne adresse conservee', async () => {
    const { setApiUrl, getApiUrl } = await import('../../src/api')
    let fail = false
    const { backend, data } = memoryBackend([], { failPut: () => fail })
    await initKvStore({ backend, force: true })
    expect(await setApiUrl('http://ancienne.test:8099')).toBe('http://ancienne.test:8099')
    expect(data.get('podmix-api-url')?.value).toBe('http://ancienne.test:8099')
    fail = true
    await expect(setApiUrl('http://nouvelle.test:8099')).rejects.toThrow(/non enregistrée/)
    expect(getApiUrl()).toBe('http://ancienne.test:8099')
    fail = false
    expect(await setApiUrl('http://nouvelle.test:8099')).toBe('http://nouvelle.test:8099')
    const relaunch = await initKvStore({ backend, force: true })
    expect(relaunch.adopted).toEqual([])
    expect(getApiUrl()).toBe('http://nouvelle.test:8099')
  })

  it('confirmation par ecriture : une ecriture concurrente B ne confirme pas A', async () => {
    const { backend, data } = memoryBackend()
    await initKvStore({ backend, force: true })
    kvSetItem('podmix-autre', 'x') // une ecriture est deja en cours : A reste en file
    const a = kvSetItemConfirmed('podmix-api-url', 'A')
    kvSetItem('podmix-api-url', 'B') // remplace A avant qu'elle soit ecrite
    expect(await a).toBe(false)
    await kvFlush()
    expect(data.get('podmix-api-url')?.value).toBe('B')
    expect(kvGetItem('podmix-api-url')).toBe('B')
  })

  it('echec : retour a la derniere valeur DURABLE, pas a une valeur seulement acceptee', async () => {
    let fail = false
    const { backend, data } = memoryBackend([], { failPut: () => fail })
    await initKvStore({ backend, force: true })
    expect(await kvSetItemConfirmed('podmix-api-url', 'O')).toBe(true)
    fail = true
    expect(kvSetItem('podmix-api-url', 'A')).toBe(true) // acceptee, puis echec
    expect(await kvSetItemConfirmed('podmix-api-url', 'B')).toBe(false)
    await kvFlush()
    expect(data.get('podmix-api-url')?.value).toBe('O')
    expect(kvGetItem('podmix-api-url')).toBe('O')
  })

  it('adoption echouee puis ecriture C confirmee : C survit au lancement suivant', async () => {
    const old = { key: 'podmix-api-url', value: 'A', verified: true, lsSeen: fingerprint('A'), at: 'x' }
    localStorage.setItem('podmix-api-url', 'B')
    let fail = true
    const mem = memoryBackend([old], { failPut: () => fail })
    const r1 = await initKvStore({ backend: mem.backend, force: true })
    expect(r1.kept).toEqual(['podmix-api-url'])
    expect(kvGetItem('podmix-api-url')).toBe('B')
    fail = false
    expect(await kvSetItemConfirmed('podmix-api-url', 'C')).toBe(true)
    await initKvStore({ backend: mem.backend, force: true })
    expect(kvGetItem('podmix-api-url')).toBe('C')
  })

  it('cle restee en mode legacy : une ecriture confirmee pendant l init est un succes (pas un faux echec)', async () => {
    localStorage.setItem('podmix-api-url', 'L')
    const mem = memoryBackend([], { failPut: (key) => key === 'podmix-api-url' })
    const init = initKvStore({ backend: mem.backend, force: true })
    const w = kvSetItemConfirmed('podmix-api-url', 'N')
    await init
    expect(await w).toBe(true)
    expect(localStorage.getItem('podmix-api-url')).toBe('N')
  })
})
