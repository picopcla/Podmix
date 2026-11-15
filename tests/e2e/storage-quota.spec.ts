import { expect, test, type Page } from '@playwright/test'

// Cas reel (15/11/2026) : le quota localStorage du WebView est plein (anciennes copies de la bibliotheque conservees),
// donc l'ecriture des favoris echouait et l'interface affichait « Sauvegarde impossible : favoris ».
// Les favoris, reglages et l'ordre de l'accueil sont maintenant ecrits dans IndexedDB ; l'ancienne copie localStorage
// n'est jamais supprimee ni reecrite quand elle est pleine. Ce test remplit reellement le quota du navigateur.

const FAVORITES_KEY = 'podmix-track-favorites-v1'

function silentWav() {
  const sampleRate = 8_000
  const dataSize = sampleRate * 2
  const wav = Buffer.alloc(44 + dataSize)
  wav.write('RIFF', 0); wav.writeUInt32LE(36 + dataSize, 4); wav.write('WAVEfmt ', 8)
  wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22)
  wav.writeUInt32LE(sampleRate, 24); wav.writeUInt32LE(sampleRate * 2, 28)
  wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34); wav.write('data', 36); wav.writeUInt32LE(dataSize, 40)
  return wav
}

async function seedAndFillQuota(page: Page) {
  await page.addInitScript(() => {
    try {
      localStorage.setItem('podmix-catalog-v1', JSON.stringify([{
        id: 'source-quota', kind: 'podcast', title: 'Podcast quota', description: 'Source de non-régression', artworkUrl: '',
        episodes: [{
          id: 'episode-quota', title: 'Épisode quota', description: 'Épisode de test', publishedAt: '2026-11-15',
          duration: '00:02', audioUrl: '/test-tone.wav', artworkUrl: '',
          tracks: [{ id: 1, time: 0.25, artist: 'Artiste test', title: 'Morceau quota', confidence: 100, source: 'manual' }],
        }],
      }]))
      localStorage.setItem('podmix-track-favorites-v1', JSON.stringify(['seed-favorite']))
    } catch { /* deja plein au rechargement : IndexedDB fait foi */ }
    // Remplit reellement le quota localStorage, jusqu'a moins de quelques octets libres.
    let index = 0
    for (let size = 200_000; size >= 1; size = Math.floor(size / 2)) {
      for (;;) {
        try { localStorage.setItem(`zz-fill-${index}`, 'x'.repeat(size)); index += 1 } catch { break }
      }
      if (size === 1) break
    }
  })
  await page.route('**/test-tone.wav', (route) => route.fulfill({ status: 200, contentType: 'audio/wav', body: silentWav() }))
}

const readIndexedDb = (page: Page, key: string) => page.evaluate((recordKey) => new Promise<unknown>((resolve, reject) => {
  const open = indexedDB.open('podmix-library-history', 1)
  open.onsuccess = () => {
    const request = open.result.transaction('records', 'readonly').objectStore('records').get(recordKey)
    request.onsuccess = () => { open.result.close(); resolve(request.result) }
    request.onerror = () => reject(request.error)
  }
  open.onerror = () => reject(open.error)
}), key)

test('les favoris sont enregistrés et survivent au rechargement même quand le quota localStorage est plein', async ({ page }) => {
  await seedAndFillQuota(page)
  await page.goto('/')
  // Le navigateur est bien a court de place : une ecriture localStorage echoue.
  expect(await page.evaluate(() => {
    try { localStorage.setItem('zz-probe', 'x'.repeat(5_000)); return false } catch { return true }
  })).toBe(true)

  await page.locator('.media-card').filter({ hasText: 'Podcast quota' }).click()
  await page.locator('.episode-info').filter({ hasText: 'Épisode quota' }).click()
  const row = page.locator('.episode-track-row').filter({ hasText: 'Morceau quota' })
  await row.getByRole('button', { name: 'Ajouter Morceau quota aux favoris' }).click()
  await expect(row.getByRole('button', { name: 'Retirer Morceau quota des favoris' })).toBeVisible()

  // Enregistre dans IndexedDB (verifie), sans bandeau d'echec.
  await expect.poll(async () => JSON.parse(String(await readIndexedDb(page, FAVORITES_KEY) ?? '[]')) as string[]).toContain('episode-quota::track::1::0')
  await expect(page.locator('.storage-warning')).toHaveCount(0)

  // L'ancienne copie localStorage n'a ni ete supprimee ni reecrite.
  expect(await page.evaluate((key) => localStorage.getItem(key), FAVORITES_KEY)).toBe('["seed-favorite"]')

  // Apres rechargement (quota toujours plein), le favori est toujours la, sans bandeau.
  await page.reload()
  await page.locator('.media-card').filter({ hasText: 'Podcast quota' }).click()
  await page.locator('.episode-info').filter({ hasText: 'Épisode quota' }).click()
  await expect(page.locator('.episode-track-row').filter({ hasText: 'Morceau quota' })
    .getByRole('button', { name: 'Retirer Morceau quota des favoris' })).toBeVisible()
  await expect(page.locator('.storage-warning')).toHaveCount(0)
  expect(await page.evaluate((key) => localStorage.getItem(key), FAVORITES_KEY)).toBe('["seed-favorite"]')
})
