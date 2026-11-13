import { expect, test, type Page, type Route } from '@playwright/test'

// Suite E2E remise à niveau pour l'interface et le stockage actuels (1.0.232).
// - Navigation par la barre supérieure (topbar), plus de .mobile-nav/.breadcrumbs.
// - Catalogue, historique, sessions et hors-ligne vivent dans IndexedDB
//   (podmix-library-history/records, podmix-completed-episodes). Les anciennes
//   copies localStorage sont migrées au démarrage : les fixtures continuent de
//   semer localStorage, mais l'état persisté se vérifie dans IndexedDB.
// - Favoris de morceaux et réglages restent en localStorage.

function silentWav(durationSeconds: number) {
  const sampleRate = 8_000
  const dataSize = sampleRate * durationSeconds * 2
  const wav = Buffer.alloc(44 + dataSize)
  wav.write('RIFF', 0)
  wav.writeUInt32LE(36 + dataSize, 4)
  wav.write('WAVEfmt ', 8)
  wav.writeUInt32LE(16, 16)
  wav.writeUInt16LE(1, 20)
  wav.writeUInt16LE(1, 22)
  wav.writeUInt32LE(sampleRate, 24)
  wav.writeUInt32LE(sampleRate * 2, 28)
  wav.writeUInt16LE(2, 32)
  wav.writeUInt16LE(16, 34)
  wav.write('data', 36)
  wav.writeUInt32LE(dataSize, 40)
  return wav
}

/**
 * Sert un WAV factice avec prise en charge des requêtes Range : sans elle,
 * Chromium ne peut pas chercher dans le flux (reprise à une position et
 * curseur de lecture) et redémarre la lecture à zéro.
 */
function fulfillWav(route: Route, durationSeconds: number) {
  const body = silentWav(durationSeconds)
  const range = route.request().headers()['range']
  const match = range ? /bytes=(\d+)-(\d*)/.exec(range) : null
  if (match) {
    const start = Number(match[1])
    const end = match[2] ? Math.min(Number(match[2]), body.length - 1) : body.length - 1
    return route.fulfill({
      status: 206,
      contentType: 'audio/wav',
      headers: { 'Content-Range': `bytes ${start}-${end}/${body.length}`, 'Accept-Ranges': 'bytes' },
      body: body.subarray(start, end + 1),
    })
  }
  return route.fulfill({
    contentType: 'audio/wav',
    headers: { 'Accept-Ranges': 'bytes' },
    body,
  })
}

/** Lit un enregistrement du magasin durable IndexedDB (podmix-library-history/records). */
async function readDurableRecord(page: Page, key: string): Promise<string | null> {
  return page.evaluate(async (recordKey) => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open('podmix-library-history', 1)
      request.onupgradeneeded = () => request.result.createObjectStore('records')
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    return new Promise<string | null>((resolve, reject) => {
      const tx = db.transaction('records', 'readonly')
      const request = tx.objectStore('records').get(recordKey)
      tx.oncomplete = () => { db.close(); resolve((request.result as string | undefined) ?? null) }
      tx.onerror = () => { db.close(); reject(tx.error) }
    })
  }, key)
}

// Isolement réseau : l'app vérifie les mises à jour et rafraîchit les flux au
// chargement. Par défaut tout appel à l'API Podmix est mis en échec contrôlé ;
// chaque test remplace les routes dont il a besoin (les routes enregistrées en
// dernier sont prioritaires).
test.beforeEach(async ({ page }) => {
  await page.route('**/podmix/update.json*', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      versionCode: 1, versionName: '0.0.1', minimumVersionCode: 0,
      apkUrl: 'https://example.test/app.apk', publishedAt: '2026-01-01T00:00:00Z', sha256: '', notes: [],
    }),
  }))
  await page.route('**/podmix-api/**', (route) => route.fulfill({
    status: 503, contentType: 'application/json', body: '{"message":"API simulée indisponible"}',
  }))
})

test('catalogue, podcast, lecture, persistance et réglages', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))

  await page.route('**/v1/catalog/podcasts?q=*', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      items: [{ id: 'asie', title: "Sur les routes de l'Asie", artist: 'Studio voyage', feedUrl: 'https://example.com/asie.xml' }],
    }),
  }))
  await page.route('**/v1/catalog/rss', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      id: 'https://example.com/asie.xml', kind: 'podcast', title: "Sur les routes de l'Asie",
      description: 'Carnets de route', artworkUrl: '', feedUrl: 'https://example.com/asie.xml',
      episodes: [{
        id: 'asie-1', title: 'Première étape', description: 'Le départ', publishedAt: '2026-10-01',
        duration: '0:10', audioUrl: '/asie-1.wav', artworkUrl: '',
      }],
    }),
  }))
  await page.route('**/asie-1.wav', (route) => fulfillWav(route, 10))

  await page.goto('/')
  await expect(page.locator('.topbar')).toBeVisible()
  await expect(page.getByText('Votre bibliothèque est vide')).toBeVisible()

  await page.getByLabel('Ajouter une source').click()
  await expect(page.getByRole('heading', { name: 'Qu’avez-vous envie d’écouter ?' })).toBeVisible()
  await expect(page.locator('.source-mode').getByRole('button', { name: 'Podcast' })).toHaveClass(/active/)
  await page.getByLabel('Rechercher un podcast').fill("Sur les routes de l'Asie")
  const result = page.locator('.podcast-results button').filter({ hasText: "Sur les routes de l'Asie" }).first()
  await expect(result).toBeVisible()
  await result.click()

  await expect(page.locator('.source-heading h2')).toHaveText("Sur les routes de l'Asie")
  await page.locator('.episode-info').filter({ hasText: 'Première étape' }).click()
  await expect(page.locator('.episode-detail .source-heading h2')).toHaveText('Première étape')
  await page.locator('.episode-detail .source-actions').getByRole('button', { name: 'Lire depuis le début' }).click()
  const mini = page.locator('.mini-player-global')
  await expect(mini).toBeVisible()
  await expect(mini).toContainText('Première étape')

  await page.getByRole('button', { name: /Sortie audio :/ }).first().click()
  await expect(page.getByRole('heading', { name: 'Écouter sur' })).toBeVisible()
  await expect(page.getByRole('button', { name: /Ce navigateur/ })).toBeVisible()
  await page.getByRole('button', { name: 'Fermer' }).click()

  // Persistance : le catalogue importé est enregistré dans le stockage durable
  // et survit au rechargement.
  await expect.poll(() => readDurableRecord(page, 'podmix-catalog-v1')).toContain("Sur les routes de l'Asie")
  await page.reload()
  await expect(page.locator('.media-card').filter({ hasText: "Sur les routes de l'Asie" })).toBeVisible()

  await page.getByRole('button', { name: 'Réglages' }).click()
  await expect(page.getByRole('heading', { name: 'Mises à jour' })).toBeVisible()
  await expect(page.getByText(/Version installée : 1\.0\.\d+/)).toBeVisible()
  await expect(page.getByText('Application à jour')).toBeVisible()

  expect(errors).toEqual([])
})

test('navigation par la barre supérieure et PWA', async ({ page }) => {
  await page.goto('/')
  const topbar = page.locator('.topbar .top-actions')
  await expect(topbar.getByRole('button')).toHaveCount(6)
  // Chaque bouton doit tenir entièrement dans la largeur de l'écran : un bouton
  // « présent » mais tronqué serait inaccessible au tactile (constaté à ≤390 px
  // sur l'app actuelle : « Réglages » déborde à 410 px — limitation app signalée,
  // hors périmètre tests ; les projets tournent à 1280 et 412 px).
  const viewportWidth = page.viewportSize()?.width ?? 0
  for (const button of await topbar.getByRole('button').all()) {
    const box = await button.boundingBox()
    expect(box, `bouton topbar tronqué à ${viewportWidth} px`).not.toBeNull()
    expect(box!.x + box!.width).toBeLessThanOrEqual(viewportWidth)
  }
  await expect(topbar.getByRole('button', { name: 'Rechercher' })).toBeVisible()
  await expect(topbar.getByRole('button', { name: 'Ajouter une source' })).toBeVisible()
  await expect(topbar.getByRole('button', { name: 'Reprendre l’écoute' })).toBeVisible()
  await expect(topbar.getByRole('button', { name: 'Voir l’historique' })).toBeVisible()
  await expect(topbar.getByRole('button', { name: 'Voir les favoris' })).toBeVisible()
  await expect(topbar.getByRole('button', { name: 'Réglages' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Analyses' })).toHaveCount(0)

  await topbar.getByRole('button', { name: 'Voir l’historique' }).click()
  // Le titre h1 du héros est masqué en mobile (media query) : l'outil de
  // recherche de l'historique identifie la vue sur les deux formats.
  await expect(page.getByLabel('Rechercher dans l’historique')).toBeVisible()
  await topbar.getByRole('button', { name: 'Voir les favoris' }).click()
  await expect(page.getByText('Aucun favori')).toBeVisible()
  await topbar.getByRole('button', { name: 'Reprendre l’écoute' }).click()
  await expect(page.getByText('Rien à reprendre')).toBeVisible()
  await topbar.getByRole('button', { name: 'Réglages' }).click()
  await expect(page.getByRole('heading', { name: 'Mises à jour' })).toBeVisible()

  const manifest = await page.request.get('/manifest.webmanifest')
  expect(manifest.ok()).toBeTruthy()
  expect((await manifest.json()).display).toBe('standalone')
  const serviceWorker = await page.request.get('/sw.js')
  expect(serviceWorker.ok()).toBeTruthy()
})

test('une donnée locale abîmée ne bloque pas le démarrage', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('podmix-catalog-v1', '{invalide')
    localStorage.setItem('podmix-history-v1', '{"ancien":true}')
    localStorage.setItem('podmix-offline-v1', '{"ancien":true}')
    localStorage.setItem('podmix-track-favorites-v1', '{invalide')
    localStorage.setItem('podmix-settings-v1', '{invalide')
  })

  await page.goto('/')
  await expect(page.locator('.topbar')).toBeVisible()
  await expect(page.getByText('Votre bibliothèque est vide')).toBeVisible()
  // Les données existantes ne sont pas effacées : l'échec de reprise est signalé.
  const warning = page.getByRole('alert')
  await expect(warning).toBeVisible()
  await expect(warning).toContainText('ne sont pas enregistrées')
  await expect(warning).toContainText('n’ont pas été effacées')
  // L'application reste utilisable.
  await page.getByRole('button', { name: 'Rechercher' }).click()
  await expect(page.getByPlaceholder('Podcast, épisode, radio ou DJ set…')).toBeVisible()
})

test('l’historique migre, recherche et supprime les anciennes écoutes', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('podmix-history-v1', JSON.stringify([{
      id: 'episode-hermes', title: 'Session Hermes', artist: 'Podcast mémoire', url: '/hermes.wav',
      position: 42, duration: 600, playedAt: '2026-08-12T10:00:00.000Z',
    }]))
  })

  await page.goto('/')
  await page.getByRole('button', { name: 'Voir l’historique' }).click()
  // Le titre h1 du héros est masqué en mobile (media query) : l'outil de
  // recherche de l'historique identifie la vue sur les deux formats.
  await expect(page.getByLabel('Rechercher dans l’historique')).toBeVisible()
  await expect(page.locator('.history-item')).toContainText('Session Hermes')
  await expect(page.locator('.history-item')).toContainText('repris à 0:42')

  await page.getByLabel('Rechercher dans l’historique').fill('introuvable')
  await expect(page.locator('.history-item')).toHaveCount(0)
  await page.getByLabel('Rechercher dans l’historique').fill('mémoire')
  await expect(page.locator('.history-item')).toHaveCount(1)
  await page.getByRole('button', { name: 'Supprimer Session Hermes de l’historique' }).click()
  await expect(page.locator('.history-item')).toHaveCount(0)
  await expect(page.getByText('Aucune écoute trouvée')).toBeVisible()
  // La suppression est bien persistée dans le stockage durable.
  await expect.poll(() => readDurableRecord(page, 'podmix-listening-sessions-v1')).toBe('[]')
})

test('un refus d’écriture du stockage ne perd pas le catalogue', async ({ page }) => {
  await page.addInitScript(() => {
    const catalog = [{
      id: 'catalogue-volumineux', kind: 'podcast', title: 'Catalogue conservé', description: 'S'.repeat(5_000), artworkUrl: '',
      episodes: [{
        id: 'episode-conserve', title: 'Épisode conservé', description: 'E'.repeat(12_000), publishedAt: '',
        duration: '10:00', audioUrl: '/episode.wav', artworkUrl: '',
        tracks: [{ id: 1, time: 0, artist: 'Artiste', title: 'Morceau conservé', confidence: 90, source: 'manual' }],
      }],
    }, {
      id: 'source-ephemere', kind: 'show', title: 'Source éphémère', description: '', artworkUrl: '',
      episodes: [{ id: 'episode-ephemere', title: 'Épisode éphémère', description: '', publishedAt: '', duration: '1:00', audioUrl: '/ephemere.wav', artworkUrl: '' }],
    }]
    localStorage.setItem('podmix-catalog-v1', JSON.stringify(catalog))
    // Simule un stockage plein : toute nouvelle écriture IDB du catalogue est
    // refusée une fois le sabotage armé (la migration de démarrage doit passer).
    const nativePut = IDBObjectStore.prototype.put
    IDBObjectStore.prototype.put = function (this: IDBObjectStore, value: unknown, key?: IDBValidKey) {
      const armed = (window as unknown as { __podmixStorageFull?: boolean }).__podmixStorageFull
      if (armed && key === 'podmix-catalog-v1' && typeof value === 'string' && value.length > 3_000) {
        throw new DOMException('Quota simulé', 'QuotaExceededError')
      }
      return nativePut.call(this, value, key as IDBValidKey)
    } as typeof IDBObjectStore.prototype.put
  })

  await page.goto('/')
  // Le catalogue migré reste consultable.
  await page.locator('.media-card').filter({ hasText: 'Catalogue conservé' }).click()
  await page.locator('.episode-info').filter({ hasText: 'Épisode conservé' }).click()
  await expect(page.locator('.episode-track')).toContainText('Morceau conservé')

  // Le stockage devient plein : la suppression de la petite source échoue à se
  // sauvegarder (le catalogue restant est toujours volumineux), elle est signalée,
  // et la copie déjà enregistrée n'est pas effacée.
  await page.evaluate(() => { (window as unknown as { __podmixStorageFull?: boolean }).__podmixStorageFull = true })
  await page.getByRole('button', { name: /Retour à/ }).click()
  await page.getByRole('button', { name: /Retour à/ }).click()
  await page.locator('.media-card').filter({ hasText: 'Source éphémère' }).click()
  await page.getByRole('button', { name: 'Supprimer', exact: true }).click()
  const warning = page.getByRole('alert')
  await expect(warning).toBeVisible()
  await expect(warning).toContainText('ne sont pas enregistrées')
  await expect.poll(() => readDurableRecord(page, 'podmix-catalog-v1')).toContain('Catalogue conservé')

  // Au rechargement, les deux sources reviennent : aucune donnée n'a été perdue.
  await page.reload()
  await expect(page.locator('.media-card').filter({ hasText: 'Catalogue conservé' })).toBeVisible()
  await expect(page.locator('.media-card').filter({ hasText: 'Source éphémère' })).toBeVisible()
})

test('les catégories héritées sont corrigées sans contaminer les prochains ajouts', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('podmix-catalog-v1', JSON.stringify([
      {
        id: 'https://example.com/legend.xml',
        kind: 'podcast',
        title: 'LEGEND',
        description: 'Émission parlée',
        artworkUrl: '',
        episodes: [],
      },
      {
        id: 'https://feed.podbean.com/richve/feed.xml',
        kind: 'show',
        title: 'Pure Trance Radio Podcast with Solarstone',
        description: 'Podcast musical',
        feedUrl: 'https://feed.podbean.com/richve/feed.xml',
        artworkUrl: '',
        episodes: [],
      },
    ]))
  })

  await page.goto('/')
  // Les rubriques d'accueil reflètent les catégories corrigées.
  const legend = page.locator('.home-shows .media-card').filter({ hasText: 'LEGEND' })
  const pureTrance = page.locator('.home-podcasts .media-card').filter({ hasText: 'Pure Trance Radio Podcast with Solarstone' })
  await expect(legend).toBeVisible()
  await expect(pureTrance).toBeVisible()

  // La correction ne contamine pas le prochain ajout : le dialogue s'ouvre
  // toujours en mode Podcast.
  await page.getByLabel('Ajouter une source').click()
  await expect(page.locator('.source-mode').getByRole('button', { name: 'Podcast' })).toHaveClass(/active/)
  await page.locator('.source-modal .modal-close').click()

  await legend.click()
  await expect(page.locator('.source-heading > div > span').first()).toHaveText('Émission')
  // La réparation est persistée dans le stockage durable.
  await expect.poll(async () => {
    const raw = await readDurableRecord(page, 'podmix-catalog-v1')
    const catalog = JSON.parse(raw ?? '[]') as Array<{ title: string; kind: string; musical?: boolean }>
    return catalog.map((source) => ({ title: source.title, kind: source.kind, musical: source.musical }))
  }).toEqual([
    { title: 'LEGEND', kind: 'show', musical: false },
    { title: 'Pure Trance Radio Podcast with Solarstone', kind: 'podcast', musical: undefined },
  ])
})

test('le raccourci d’accueil reprend à la dernière position', async ({ page }) => {
  await page.addInitScript(() => {
    const episode = (id: string, title: string, audioUrl: string) => ({
      id, title, description: '', publishedAt: '30 juillet 2026', duration: '0:10', audioUrl, artworkUrl: '',
    })
    localStorage.setItem('podmix-catalog-v1', JSON.stringify([{
      id: 'podcast-test', kind: 'podcast', title: 'Podcast test', description: '', artworkUrl: '',
      episodes: [
        episode('episode-reprise', 'Épisode à reprendre', '/resume.wav'),
        episode('episode-termine', 'Épisode terminé à 98 %', '/done.wav'),
      ],
    }, {
      id: 'emission-test', kind: 'show', title: 'Émission test', description: '', artworkUrl: '',
      episodes: [episode('emission-reprise', 'Émission à reprendre', '/show.wav')],
    }, {
      id: 'dj-test', kind: 'dj', title: 'DJ test', description: '', artworkUrl: '',
      episodes: [episode('dj-reprise', 'DJ set à masquer', '/dj.wav')],
    }]))
    localStorage.setItem('podmix-history-v1', JSON.stringify([
      {
        id: 'episode-reprise', title: 'Morceau individuel à masquer', artist: 'Artiste du morceau', url: '/resume.wav',
        position: 3, duration: 10, playedAt: '2026-07-30T10:00:00.000Z',
      },
      {
        id: 'emission-reprise', title: 'Titre analysé à masquer', artist: 'Artiste analysé', url: '/show.wav',
        position: 4, duration: 10, playedAt: '2026-07-30T09:30:00.000Z',
      },
      {
        id: 'dj-reprise', title: 'DJ set à masquer', artist: 'DJ test', url: '/dj.wav',
        position: 2, duration: 10, playedAt: '2026-07-30T09:00:00.000Z',
      },
      {
        id: 'episode-termine', title: 'Épisode terminé à 98 %', artist: 'Podcast test', url: '/done.wav',
        position: 9.8, duration: 10, playedAt: '2026-07-29T10:00:00.000Z',
      },
    ]))
  })
  await page.route('**/resume.wav', (route) => fulfillWav(route, 10))

  await page.goto('/')
  // La reprise vit dans la vue dédiée « Reprendre l'écoute » de la topbar
  // (la grille d'accueil n'a plus de section repliée dédiée).
  await page.getByRole('button', { name: 'Reprendre l’écoute' }).click()
  const resumeSection = page.locator('.resume-library')
  await expect(resumeSection).toBeVisible()
  // Seuls les épisodes entiers non terminés sont proposés, jamais les entrées
  // de morceaux ; les DJ sets sont désormais repris comme les autres sources.
  await expect(resumeSection.locator('.episode-item')).toHaveCount(3)
  await expect(resumeSection).toContainText('Épisode à reprendre')
  await expect(resumeSection).toContainText('Émission à reprendre')
  await expect(resumeSection).toContainText('DJ set à masquer')
  await expect(resumeSection).not.toContainText('Morceau individuel à masquer')
  await expect(resumeSection).not.toContainText('Titre analysé à masquer')
  await expect(resumeSection).not.toContainText('Épisode terminé à 98 %')

  const resumeEpisode = resumeSection.locator('.episode-item').first()
  await resumeEpisode.getByRole('button', { name: 'Reprendre Épisode à reprendre' }).click()

  await expect(page.locator('.mini-player-global')).toContainText('Épisode à reprendre')
  const positionSlider = page.getByRole('slider', { name: 'Position de lecture' })
  await expect(positionSlider).toBeVisible()
  await expect.poll(async () => Number(await positionSlider.getAttribute('max'))).toBeGreaterThanOrEqual(9)
  // La lecture reprend à la position enregistrée, persistée en IndexedDB.
  await expect.poll(async () => {
    const raw = await readDurableRecord(page, 'podmix-history-v1')
    return (JSON.parse(raw ?? '[]') as Array<{ position?: number }>)[0]?.position ?? 0
  }).toBeGreaterThanOrEqual(3)
  await expect(resumeEpisode).toHaveClass(/playing/)
  await resumeEpisode.getByRole('button', { name: 'Mettre en pause Épisode à reprendre' }).click()
  await expect(resumeEpisode).toHaveClass(/playing/)
  await resumeEpisode.getByRole('button', { name: 'Reprendre Épisode à reprendre' }).click()
  await expect(resumeEpisode.getByRole('button', { name: 'Mettre en pause Épisode à reprendre' })).toBeVisible()

  await resumeEpisode.getByRole('button', { name: 'Ouvrir Épisode à reprendre' }).click()

  await expect(page.locator('.episode-detail')).toContainText('Épisode à reprendre')
  await expect(page.locator('.episode-detail .source-actions').getByRole('button', { name: /Reprendre · 0:0/ })).toBeVisible()
  await expect(page.locator('.resume-library')).toHaveCount(0)
  // La position affichée se rafraîchit environ une fois par seconde : le seuil
  // 6,5 s est hors de portée d'une lecture naturelle depuis la reprise (~4 s).
  await positionSlider.fill('7')
  await expect.poll(async () => Number(await positionSlider.inputValue()), { timeout: 8_000 }).toBeGreaterThanOrEqual(6.5)
})

test('la liste des épisodes montre un état de lecture compact sans pourcentage', async ({ page }) => {
  await page.addInitScript(() => {
    const episode = (id: string, title: string) => ({
      id,
      title,
      description: '',
      publishedAt: '2026-08-01',
      duration: '10:00',
      audioUrl: `/${id}.wav`,
      artworkUrl: '',
    })
    localStorage.setItem('podmix-catalog-v1', JSON.stringify([{
      id: 'source-progress',
      kind: 'podcast',
      title: 'Podcast progression',
      description: '',
      artworkUrl: '',
      episodes: [
        episode('episode-new', 'Épisode neuf'),
        episode('episode-progress', 'Épisode commencé'),
        episode('episode-done', 'Épisode terminé'),
      ],
    }]))
    localStorage.setItem('podmix-history-v1', JSON.stringify([{
      id: 'episode-progress', title: 'Épisode commencé', artist: 'Podcast progression', url: '/episode-progress.wav', position: 300, playedAt: '2026-08-01T10:00:00.000Z',
    }, {
      // Une nouvelle écoute partielle ne doit pas effacer l'état « Lu » acquis.
      id: 'episode-done', title: 'Épisode terminé', artist: 'Podcast progression', url: '/episode-done.wav', position: 60, playedAt: '2026-08-01T09:00:00.000Z',
    }]))
    localStorage.setItem('podmix-completed-episodes-v1', JSON.stringify(['episode-done']))
  })

  await page.goto('/')
  await page.locator('.media-card').filter({ hasText: 'Podcast progression' }).click()

  await expect(page.locator('.episode-item').filter({ hasText: 'Épisode neuf' }).getByLabel('État de lecture : À lire')).toBeVisible()
  const startedEpisode = page.locator('.episode-item').filter({ hasText: 'Épisode commencé' })
  await expect(startedEpisode.getByLabel('État de lecture : En cours')).toBeVisible()
  await expect(startedEpisode).not.toContainText('50%')
  await expect(page.locator('.episode-item').filter({ hasText: 'Épisode terminé' }).getByLabel('État de lecture : Lu')).toBeVisible()
  await expect(startedEpisode.locator('h3')).toHaveCSS('white-space', 'nowrap')
})

test('un titre démarre au repère du morceau', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('podmix-catalog-v1', JSON.stringify([{
      id: 'source-test',
      kind: 'podcast',
      title: 'Podcast musical test',
      description: 'Source de non-régression',
      artworkUrl: '',
      episodes: [{
        id: 'episode-test',
        title: 'Épisode avec tracklist',
        description: 'Épisode de test',
        publishedAt: '2026-07-28',
        duration: '00:02',
        audioUrl: '/test-tone.wav',
        artworkUrl: '',
        tracks: [{
          id: 1,
          time: 0.25,
          artist: 'Artiste test',
          title: 'Morceau ciblé',
          confidence: 100,
          source: 'manual',
        }, {
          id: 2,
          time: 1.5,
          artist: 'Artiste suivant',
          title: 'Morceau suivant',
          confidence: 100,
          source: 'manual',
        }],
      }],
    }]))
  })
  await page.route('**/test-tone.wav', (route) => fulfillWav(route, 30))

  await page.goto('/')
  await page.locator('.media-card').filter({ hasText: 'Podcast musical test' }).click()
  await page.locator('.episode-info').filter({ hasText: 'Épisode avec tracklist' }).click()

  const firstTrackRow = page.locator('.episode-track-row').filter({ hasText: 'Morceau ciblé' })
  await expect(firstTrackRow).not.toContainText('100%')
  await expect(firstTrackRow.locator('.episode-track strong > span')).toHaveCSS('white-space', 'nowrap')
  await firstTrackRow.getByRole('button', { name: 'Ajouter Morceau ciblé aux favoris' }).click()
  await expect(firstTrackRow.getByRole('button', { name: 'Retirer Morceau ciblé des favoris' })).toBeVisible()
  await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem('podmix-track-favorites-v1') ?? '[]') as string[])).toContain('episode-test::track::1::0')

  await page.locator('.episode-detail .source-actions').getByRole('button', { name: 'Lire depuis le début' }).click()
  await expect(page.locator('.episode-track.playing')).toContainText('Morceau ciblé')

  await page.locator('.episode-track').filter({ hasText: 'Morceau ciblé' }).click()

  const mini = page.locator('.mini-player-global')
  await expect(mini).toContainText('Morceau ciblé')
  await expect(page.locator('.episode-track.playing')).toContainText('Morceau ciblé')
  // La lecture démarre au repère du morceau (0,25 s), pas au début de l'épisode.
  await expect.poll(async () => {
    const raw = await readDurableRecord(page, 'podmix-history-v1')
    return (JSON.parse(raw ?? '[]') as Array<{ position?: number }>)[0]?.position ?? 0
  }).toBeGreaterThanOrEqual(0.25)
  const position = (JSON.parse(await readDurableRecord(page, 'podmix-history-v1') ?? '[]') as Array<{ position?: number }>)[0]?.position ?? 0
  expect(position).toBeLessThan(2)

  await page.getByRole('button', { name: 'Morceau suivant', exact: true }).click()
  await expect(mini).toContainText('Morceau suivant')

  await page.locator('.episode-detail .source-actions').getByRole('button', { name: 'Lire depuis le début' }).click()
  await expect(mini).toContainText('Épisode avec tracklist')
})

test('les favoris gardent leur épisode et s’enchaînent', async ({ page }) => {
  await page.route('**/v1/catalog/links', async (route) => {
    const request = route.request().postDataJSON() as { tracks: Array<{ key: string }> }
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        items: request.tracks.map((track) => track.key.includes('episode-favori-a')
          ? { key: track.key, spotifyUrl: 'https://open.spotify.com/track/detected-a', youtubeUrl: 'https://www.youtube.com/watch?v=detected-a' }
          : { key: track.key, deezerUrl: 'https://www.deezer.com/track/detected-b' }),
      }),
    })
  })
  await page.route('**/v1/catalog/artwork', (route) => route.fulfill({
    contentType: 'application/json', body: '{"items":[]}',
  }))
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'share', {
      configurable: true,
      value: async () => undefined,
    })
    const tracks = [{
      id: 1,
      time: 0.25,
      artist: 'Artiste A',
      title: 'Favori du premier épisode',
      artworkUrl: '/track-cover.jpg',
      deezerUrl: 'https://www.deezer.com/track/123',
      confidence: 100,
      source: 'manual',
    }]
    localStorage.setItem('podmix-catalog-v1', JSON.stringify([{
      id: 'source-favoris',
      kind: 'podcast',
      title: 'Podcast des favoris',
      description: 'Source de non-régression',
      artworkUrl: '/podcast-logo.jpg',
      episodes: [{
        id: 'episode-favori-a',
        title: 'Premier épisode favori',
        description: '',
        publishedAt: '2026-07-28',
        duration: '00:02',
        audioUrl: 'https://publisher.example/favorite-a.wav',
        sourceUrl: 'https://publisher.example/episode-a',
        artworkUrl: '',
        tracks,
      }, {
        id: 'episode-favori-b',
        title: 'Second épisode favori',
        description: '',
        publishedAt: '2026-07-28',
        duration: '00:02',
        audioUrl: 'https://publisher.example/favorite-b.wav',
        sourceUrl: 'https://publisher.example/episode-b',
        artworkUrl: '',
        tracks: [
          { ...tracks[0], id: 2, title: 'Favori du second épisode', artworkUrl: '', deezerUrl: undefined, spotifyUrl: 'https://open.spotify.com/track/abc' },
          { ...tracks[0], id: 3, time: 4, title: 'Titre étranger à la file', artworkUrl: '', deezerUrl: undefined, spotifyUrl: undefined },
        ],
      }],
    }]))
    localStorage.setItem('podmix-track-favorites-v1', JSON.stringify([
      'episode-favori-a::track::1::0',
      'episode-favori-b::track::2::0',
    ]))
  })
  await page.route('**/favorite-*.wav', (route) => fulfillWav(route, 30))

  await page.goto('/')
  await page.getByRole('button', { name: 'Voir les favoris' }).click()
  const favorites = page.locator('.favorite-tracks')
  await expect(favorites.getByText('Favori du premier épisode', { exact: true })).toBeVisible()
  await expect(favorites.getByText('Podcast des favoris', { exact: true })).toHaveCount(0)
  await expect(favorites.getByText('Premier épisode favori', { exact: false })).toHaveCount(0)
  await expect(favorites.locator('.track-cover-button')).toHaveCount(2)
  await expect(favorites.getByLabel('Deezer disponible : ouvrir le morceau').nth(0)).toHaveAttribute('href', 'https://www.deezer.com/track/123')
  await expect(favorites.getByLabel('Spotify disponible : ouvrir le morceau').nth(0)).toHaveAttribute('href', 'https://open.spotify.com/track/detected-a')
  await expect(favorites.getByLabel('Deezer disponible : ouvrir le morceau').nth(1)).toHaveAttribute('href', 'https://www.deezer.com/track/detected-b')
  await expect(favorites.getByLabel('Spotify disponible : ouvrir le morceau').nth(1)).toHaveAttribute('href', 'https://open.spotify.com/track/abc')
  await expect(favorites.getByLabel('YouTube disponible : ouvrir le morceau')).toHaveAttribute('href', 'https://www.youtube.com/watch?v=detected-a')
  await expect(favorites.getByRole('button', { name: 'YouTube indisponible pour ce morceau' })).toHaveCount(1)
  await expect(favorites.getByRole('button', { name: 'Partager Favori du premier épisode' })).toBeVisible()
  await expect(favorites.locator('.favorite-track-actions')).toHaveCount(2)
  const copyBox = await favorites.locator('.favorite-track-copy').first().boundingBox()
  const actionBox = await favorites.locator('.favorite-track-actions').first().boundingBox()
  expect(copyBox).not.toBeNull()
  expect(actionBox).not.toBeNull()
  expect(actionBox?.x ?? 0).toBeGreaterThan(copyBox?.x ?? 0)
  await page.route('**/v1/shares', async (route) => {
    const payload = route.request().postDataJSON() as { startSeconds: number; endSeconds?: number; artist: string; trackTitle: string }
    expect(payload.startSeconds).toBe(0.25)
    expect(payload.endSeconds).toBe(2)
    expect(payload.artist).toBe('Artiste A')
    expect(payload.trackTitle).toBe('Favori du premier épisode')
    await route.fulfill({ contentType: 'application/json', status: 201, body: JSON.stringify({ id: 'share-token', shareUrl: 'https://podmix.mb4.fr/s/share-token', expiresAt: '2026-09-11T00:00:00Z' }) })
  })
  const shareRequest = page.waitForRequest((request) => request.url().includes('/v1/shares') && request.method() === 'POST')
  await favorites.getByRole('button', { name: 'Partager Favori du premier épisode' }).click()
  await shareRequest
  await page.getByRole('button', { name: 'Tout lire' }).click()

  await expect(page.locator('.mini-player-global')).toContainText('Favori du premier épisode')
  await expect(favorites.locator('.episode-item.playing')).toContainText('Favori du premier épisode')
  await page.getByRole('button', { name: 'Morceau suivant', exact: true }).click()
  await expect(page.locator('.mini-player-global')).toContainText('Favori du second épisode')
  await expect(favorites.locator('.episode-item.playing')).toContainText('Favori du second épisode')
  // Le poll natif/web s'exécute chaque seconde. La file de favoris est à
  // l'index 1, mais ce favori est à l'index 0 de son épisode : ces deux index
  // ne doivent jamais être confondus dans le titre du mini-lecteur.
  await page.waitForTimeout(1_200)
  await expect(page.locator('.mini-player-global')).toContainText('Favori du second épisode')
  await expect(page.locator('.mini-player-global')).not.toContainText('Titre étranger à la file')
})

// Régression applicative connue, assertions volontairement maintenues à pleine
// force : la normalisation des entités HTML du catalogue stocké
// (normalizeCatalogSourceText au chargement, puis ré-enregistrement avec ids et
// URLs inchangés) a été perdue quand src/App.tsx a été remplacé pendant la
// fusion 1.0.229 (« Delete src/App.tsx » 72b256a / « Rename 1-App.tsx » aa3aa9a).
// Depuis, les titres de sources et d'épisodes stockés avec des entités doubles
// s'affichent bruts (« Above &amp;amp; Beyond ») et le stockage n'est plus
// réparé. Ce test repasse vert dès que l'app répare de nouveau le catalogue au
// chargement : retirer alors le test.fail.
// RÉGRESSION APP CONNUE (fix f708d24 perdu à la fusion 1.0.229, commits
// 72b256a / aa3aa9a) : les titres source/épisode s'affichent bruts
// (« Above &amp;amp; Beyond ») et le stockage n'est jamais réparé.
// Ce test reste volontairement ROUGE jusqu'au correctif app : la suite ne
// doit pas masquer la régression. Retirer ce commentaire une fois corrigé.
test('les entités HTML sont corrigées dans les données existantes, la tracklist et les deux lecteurs', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('podmix-settings-v1', JSON.stringify({ automaticAnalysis: false }))
    localStorage.setItem('podmix-catalog-v1', JSON.stringify([{
      id: 'source-entities&amp;stable',
      kind: 'podcast',
      title: 'Above &amp;amp; Beyond',
      description: 'Source &amp; description',
      artworkUrl: '',
      feedUrl: 'https://example.test/feed?a=1&amp;b=2',
      episodes: [{
        id: 'episode-entities&amp;stable',
        title: 'Find Your Harmony &amp;amp; Friends',
        description: 'Une balise &lt;b&gt;reste du texte&lt;/b&gt;.',
        publishedAt: '2026-10-13',
        duration: '00:30',
        audioUrl: 'https://example.test/entities.wav?a=1&amp;b=2',
        artworkUrl: '',
        tracks: [{
          id: 1, time: 0, artist: 'Mark Sherry, BiXX &amp; Sue McLaren',
          title: 'Believe &#38; Receive', confidence: 100, source: 'manual',
        }, {
          id: 2, time: 20, artist: 'David Forbes &amp;amp; Lostly',
          title: 'Incomplete &amp stays', confidence: 100, source: 'manual',
        }],
      }],
    }]))
  })
  await page.route('**/entities.wav?*', (route) => fulfillWav(route, 30))

  await page.goto('/')
  await page.locator('.media-card').filter({ hasText: 'Above & Beyond' }).click()
  await expect(page.locator('.episode-info')).toContainText('Find Your Harmony & Friends')
  await page.locator('.episode-info').click()

  const track = page.locator('.episode-track').first()
  await expect(track).toContainText('Believe & Receive')
  await expect(track).toContainText('Mark Sherry, BiXX & Sue McLaren')
  await expect(page.locator('.episode-track').nth(1)).toContainText('David Forbes & Lostly')
  await expect(page.locator('.episode-track').nth(1)).toContainText('Incomplete &amp stays')

  await track.click()
  const mini = page.locator('.mini-player-global')
  await expect(mini).toContainText('Believe & Receive')
  await expect(mini).toContainText('Mark Sherry, BiXX & Sue McLaren')
  await mini.locator('.mini-meta').click()
  const full = page.getByRole('dialog', { name: 'Lecteur en cours' })
  await expect(full).toContainText('Believe & Receive')
  await expect(full).toContainText('Mark Sherry, BiXX & Sue McLaren')

  // La réparation est persistée : titres décodés, identifiants et URLs bruts.
  await expect.poll(async () => {
    const raw = await readDurableRecord(page, 'podmix-catalog-v1')
    const [source] = JSON.parse(raw ?? '[]') as Array<{
      id: string; feedUrl?: string; title: string
      episodes: Array<{ id: string; audioUrl: string; title: string; tracks: Array<{ artist: string }> }>
    }>
    return {
      sourceId: source?.id,
      feedUrl: source?.feedUrl,
      episodeId: source?.episodes?.[0]?.id,
      audioUrl: source?.episodes?.[0]?.audioUrl,
      sourceTitle: source?.title,
      episodeTitle: source?.episodes?.[0]?.title,
      artist: source?.episodes?.[0]?.tracks?.[0]?.artist,
    }
  }).toEqual({
    sourceId: 'source-entities&amp;stable',
    feedUrl: 'https://example.test/feed?a=1&amp;b=2',
    episodeId: 'episode-entities&amp;stable',
    audioUrl: 'https://example.test/entities.wav?a=1&amp;b=2',
    sourceTitle: 'Above & Beyond',
    episodeTitle: 'Find Your Harmony & Friends',
    artist: 'Mark Sherry, BiXX & Sue McLaren',
  })
})

test('le téléchargement confirme immédiatement la demande et suit son état natif', async ({ page }) => {
  await page.addInitScript(() => {
    const nativeState = {
      calls: 0,
      download: { id: 'episode-download', status: 'queued', bytesDownloaded: 0, totalBytes: 100 },
      pending: true,
      failNext: false,
      resolveDownload: undefined as undefined | ((value: unknown) => void),
      rejectDownload: undefined as undefined | ((error: Error) => void),
    }
    Object.assign(window, { __podmixDownloadTest: nativeState, androidBridge: {} })

    const promiseMethods = [
      'addListener', 'appendLibraryChunk', 'beginLibrarySync', 'boseDiscover', 'boseGetState',
      'boseKey', 'bosePlay', 'boseSetVolume', 'cacheArtwork', 'cast', 'commitLibrarySync',
      'connectCastDevice', 'disconnectCast', 'discoverCastDevices', 'download', 'downloadApk',
      'extractTracks', 'fetchPublishedTracklist', 'getApkStatus', 'getAppInfo', 'getCastState',
      'getCastVolume', 'getCompletedEpisodeIds', 'getDownload', 'getFavorites',
      'getPendingPlaybackTarget', 'getState', 'getStorage', 'getVolume', 'installApk',
      'listDownloads', 'load', 'next', 'openCastPicker', 'pause', 'play', 'previous',
      'recognizeMusic', 'removeDownload', 'seekTo', 'setCastVolume', 'setQueue', 'setRepeatMode',
      'setVolume', 'startWebTimestampWorker', 'syncFavorites', 'syncResume', 'syncSubscriptions',
    ].map((name) => ({ name, rtype: 'promise' }))

    Object.assign(window, {
      Capacitor: {
        PluginHeaders: [
          { name: 'PodmixPlayer', methods: promiseMethods },
          { name: 'App', methods: [{ name: 'addListener', rtype: 'callback' }, { name: 'removeListener', rtype: 'promise' }] },
        ],
        nativeCallback: () => 'listener-test',
        nativePromise: (plugin: string, method: string, options?: { id?: string }) => {
          if (plugin === 'App') return Promise.resolve({})
          if (method === 'download') {
            nativeState.calls += 1
            if (nativeState.failNext) {
              nativeState.failNext = false
              return Promise.reject(new Error('Échec natif simulé'))
            }
            if (nativeState.pending) {
              return new Promise((resolve, reject) => {
                nativeState.resolveDownload = resolve
                nativeState.rejectDownload = reject
              })
            }
            return Promise.resolve({ ...nativeState.download, id: options?.id ?? nativeState.download.id })
          }
          if (method === 'getDownload') return Promise.resolve({ ...nativeState.download, id: options?.id ?? nativeState.download.id })
          if (method === 'listDownloads') return Promise.resolve({ downloads: [] })
          if (method === 'getAppInfo') return Promise.resolve({ versionName: 'test', versionCode: 1, lastUpdateTime: 0 })
          if (method === 'getPendingPlaybackTarget') return Promise.resolve({ sourceId: '', episodeId: '' })
          if (method === 'getFavorites') return Promise.resolve({ ids: [], initialized: false })
          if (method === 'getCompletedEpisodeIds') return Promise.resolve({ ids: [] })
          if (method === 'getStorage') return Promise.resolve({ downloadedBytes: 0, availableBytes: 1_000, totalBytes: 1_000 })
          if (method === 'getVolume') return Promise.resolve({ volume: 1 })
          if (method === 'getState' || method === 'setRepeatMode') return Promise.resolve({
            playing: false, positionSeconds: 0, durationSeconds: 0, playbackState: 0,
            title: '', artist: '', queueIndex: 0, queueSize: 0, hasNext: false,
            hasPrevious: false, mediaId: '',
          })
          if (method === 'commitLibrarySync') return Promise.resolve({ count: 0 })
          if (method.startsWith('sync') || method.endsWith('LibrarySync')) return Promise.resolve({ count: 0, ok: true })
          return Promise.resolve({})
        },
      },
    })

    localStorage.setItem('podmix-catalog-v1', JSON.stringify([{
      id: 'source-download', kind: 'show', title: 'Émission factice', description: '', artworkUrl: '',
      episodes: [{
        id: 'episode-download', title: 'Épisode factice', description: '', publishedAt: '2026-10-13',
        duration: '10:00', audioUrl: 'https://example.test/audio.mp3', artworkUrl: '',
      }, {
        id: 'episode-retry', title: 'Épisode à réessayer', description: '', publishedAt: '2026-10-12',
        duration: '08:00', audioUrl: 'https://example.test/retry.mp3', artworkUrl: '',
      }],
    }]))
  })

  await page.goto('/')
  await page.locator('.media-card').filter({ hasText: 'Émission factice' }).click()
  await page.locator('.episode-info').filter({ hasText: 'Épisode factice' }).click()

  const detail = page.locator('.episode-detail')
  const download = detail.getByRole('button', { name: /Télécharger pour écouter hors connexion/ })
  await download.evaluate((button: HTMLButtonElement) => { button.click(); button.click() })
  await expect(detail.getByText('Préparation…', { exact: false })).toBeVisible()
  await expect.poll(() => page.evaluate(() => (window as typeof window & { __podmixDownloadTest: { calls: number } }).__podmixDownloadTest.calls)).toBe(1)

  await page.evaluate(() => {
    const state = (window as typeof window & { __podmixDownloadTest: {
      pending: boolean
      download: { id: string; status: string; bytesDownloaded: number; totalBytes: number; localUri?: string }
      resolveDownload?: (value: unknown) => void
    } }).__podmixDownloadTest
    state.pending = false
    state.download = { id: 'episode-download', status: 'queued', bytesDownloaded: 0, totalBytes: 100 }
    state.resolveDownload?.(state.download)
  })
  await expect(detail.getByRole('button', { name: /file d’attente/i })).toContainText('File d’attente')
  await expect(detail.getByText(/Demande reçue.*file d’attente/)).toBeVisible()

  await page.evaluate(() => {
    const state = (window as typeof window & { __podmixDownloadTest: { download: object } }).__podmixDownloadTest
    state.download = { id: 'episode-download', status: 'downloading', bytesDownloaded: 45, totalBytes: 100 }
  })
  await expect(detail.getByText('Téléchargement démarré · 45 %', { exact: true })).toBeVisible({ timeout: 5_000 })

  await page.evaluate(() => {
    const state = (window as typeof window & { __podmixDownloadTest: { download: object } }).__podmixDownloadTest
    state.download = { id: 'episode-download', status: 'completed', bytesDownloaded: 100, totalBytes: 100, localUri: 'file:///data/factice.mp3' }
  })
  const completedDetail = detail.getByRole('button', { name: 'Téléchargé · disponible hors connexion' })
  await expect(completedDetail).toContainText('Téléchargé', { timeout: 5_000 })
  await expect(completedDetail.locator('svg.lucide-check')).toBeVisible()
  await expect(completedDetail).toBeDisabled()
  await expect(detail.getByRole('button', { name: 'Supprimer le téléchargement' })).toBeVisible()
  await expect(detail.getByText(/Téléchargement terminé.*disponible hors connexion/)).toBeVisible()

  await page.getByRole('button', { name: /Retour à/ }).click()
  const completedRow = page.locator('.episode-item').filter({ hasText: 'Épisode factice' })
  const completedRowStatus = completedRow.getByRole('button', { name: 'Téléchargé · disponible hors connexion' })
  await expect(completedRowStatus.locator('svg.lucide-check')).toBeVisible()
  await expect(completedRowStatus).toBeDisabled()
  await expect(completedRow.getByRole('button', { name: 'Supprimer le téléchargement' })).toBeVisible()
  await page.locator('.episode-info').filter({ hasText: 'Épisode factice' }).click()
  await expect(page.locator('.episode-detail').getByRole('button', { name: 'Téléchargé · disponible hors connexion' })).toBeVisible()

  await page.getByRole('button', { name: /Retour à/ }).click()
  await page.locator('.episode-info').filter({ hasText: 'Épisode à réessayer' }).click()
  await page.evaluate(() => {
    const state = (window as typeof window & { __podmixDownloadTest: { pending: boolean; failNext: boolean } }).__podmixDownloadTest
    state.pending = false
    state.failNext = true
  })
  await page.locator('.episode-detail').getByRole('button', { name: /Télécharger pour écouter/ }).click()
  await expect(page.locator('.episode-detail').getByRole('button', { name: /Téléchargement échoué/ })).toContainText('Réessayer')
  await expect(page.locator('.episode-detail').getByText(/téléchargement a échoué/i)).toBeVisible()

  await page.evaluate(() => {
    const state = (window as typeof window & { __podmixDownloadTest: { download: object } }).__podmixDownloadTest
    state.download = { id: 'episode-retry', status: 'queued', bytesDownloaded: 0, totalBytes: 200 }
  })
  await page.locator('.episode-detail').getByRole('button', { name: /Téléchargement échoué/ }).click()
  await expect(page.locator('.episode-detail').getByRole('button', { name: /file d’attente/i })).toContainText('File d’attente')
  await expect.poll(() => page.evaluate(() => (window as typeof window & { __podmixDownloadTest: { calls: number } }).__podmixDownloadTest.calls)).toBe(3)
})

// RÉGRESSION APP CONNUE (responsive, qualifiée 13/11) : à 390 px et 360 px de
// large, le 6e bouton de la topbar (« Réglages ») s'étend jusqu'à 410 px — hors
// écran — et son centre ne reçoit pas le tap. Rouge jusqu'au fix app ; ne pas
// contourner en testant seulement à 412 px.
test('la barre supérieure reste accessible à 390 px de large', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  const buttons = page.locator('.topbar .top-actions').getByRole('button')
  await expect(buttons).toHaveCount(6)
  for (const button of await buttons.all()) {
    const label = await button.getAttribute('aria-label')
    const reach = await button.evaluate((el) => {
      const r = el.getBoundingClientRect()
      const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2)
      return { right: r.right, vw: window.innerWidth, tappable: hit === el || el.contains(hit as Node | null) }
    })
    expect(reach.right, `${label} déborde de l'écran à 390 px`).toBeLessThanOrEqual(reach.vw)
    expect(reach.tappable, `${label} ne reçoit pas le tap à 390 px`).toBe(true)
  }
  // Chaque bouton doit réellement ouvrir sa vue au tap.
  await page.locator('.topbar .top-actions').getByRole('button', { name: 'Réglages' }).click()
  await expect(page.getByRole('heading', { name: 'Mises à jour' })).toBeVisible()
})

// BUG APP CONNU (qualifié 13/11, repro synthétique) : la suppression d'une
// écoute est bien persistée (sessions IndexedDB = []), mais la copie legacy
// localStorage podmix-history-v1 n'est jamais vidée et loadListeningSessions
// retombe dessus quand les sessions sont vides → l'entrée supprimée réapparaît
// au rechargement. Rouge jusqu'au fix app.
test('la suppression d’une écoute survit au rechargement', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('podmix-history-v1', JSON.stringify([{
      id: 'ep-x', title: 'Session synthétique', artist: 'Podcast synthétique', url: '/x.wav',
      position: 42, duration: 600, playedAt: '2026-08-12T10:00:00.000Z',
    }]))
  })
  await page.goto('/')
  await page.getByRole('button', { name: 'Voir l’historique' }).click()
  await expect(page.locator('.history-item')).toHaveCount(1)
  await page.getByRole('button', { name: 'Supprimer Session synthétique de l’historique' }).click()
  await expect(page.locator('.history-item')).toHaveCount(0)
  await expect.poll(() => readDurableRecord(page, 'podmix-listening-sessions-v1')).toBe('[]')

  await page.reload()
  await page.getByRole('button', { name: 'Voir l’historique' }).click()
  await expect(page.locator('.history-item')).toHaveCount(0)
  await expect(page.getByText('Aucune écoute trouvée')).toBeVisible()
})
