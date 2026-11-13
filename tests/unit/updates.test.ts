// @vitest-environment jsdom

import { afterEach, describe, expect, it, vi } from 'vitest'

// Isole le pont natif : le test porte uniquement sur la comparaison de versions.
vi.mock('../../src/nativePlayer', () => ({
  podmixPlayer: {
    downloadApk: vi.fn(),
    getApkStatus: vi.fn(),
    installApk: vi.fn(),
  },
}))

import { checkForUpdate } from '../../src/updates'

const manifest229 = {
  versionCode: 10229,
  versionName: '1.0.229-ng',
  minimumVersionCode: 1,
  apkUrl: 'https://example.com/podmix-1.0.229-ng.apk',
  publishedAt: '2026-11-10T00:00:00Z',
  sha256: 'abc',
  notes: [],
}

function stubManifest(manifest: unknown) {
  vi.stubEnv('VITE_UPDATE_MANIFEST_URL', 'https://example.com/update.json')
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => manifest })))
}

describe('checkForUpdate', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
  })

  it('ne propose pas une version inférieure au code natif installé', async () => {
    stubManifest(manifest229)
    // Cas réel du 13/11 : 1.0.230-ng (code natif 10230) installé,
    // manifeste public resté à 10229 → aucune proposition attendue.
    expect(await checkForUpdate(10230)).toBeNull()
  })

  it('retombe sur la constante de build sans code natif (web)', async () => {
    stubManifest(manifest229)
    // VITE_APP_VERSION non défini ici → '1.0.0' → 10229 est bien supérieur.
    expect(await checkForUpdate(undefined)).toEqual(manifest229)
  })

  it('propose une version strictement supérieure au code natif', async () => {
    stubManifest({ ...manifest229, versionCode: 10231, versionName: '1.0.231-ng' })
    const update = await checkForUpdate(10230)
    expect(update?.versionCode).toBe(10231)
  })

  it('ignore la vérification sans URL de manifeste configurée', async () => {
    vi.stubEnv('VITE_UPDATE_MANIFEST_URL', '')
    expect(await checkForUpdate(10230)).toBeNull()
  })
})
