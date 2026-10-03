import { expect, test } from '@playwright/test'

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

test('catalogue, podcast, lecture, persistance et réglages', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))

  await page.goto('/')
  await expect(page.locator('.breadcrumbs strong')).toHaveText('Accueil')
  await expect(page.getByText('Commencer à écouter')).toHaveCount(0)
  await expect(page.locator('.home-resume')).toHaveCount(0)
  await expect(page.locator('.home-catalog-heading')).toHaveCount(0)
  await expect(page.getByText('Bibliothèque', { exact: true })).toHaveCount(2)
  await expect(page.locator('.home-overview')).toHaveCount(0)
  const appInfo = page.getByRole('button', { name: /Informations sur l’application, version 1\.0\.39/ })
  await expect(appInfo).toBeVisible()
  await appInfo.click()
  await expect(page.getByRole('heading', { name: 'Podmix Studio' })).toBeVisible()
  await expect(page.getByText('Version 1.0.39', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Fermer' }).click()

  await page.getByRole('button', { name: 'Bibliothèque' }).last().click()
  await expect(page.locator('.catalog-grid')).toHaveClass(/home-catalog-grid/)
  await page.locator('.catalog-toolbar').getByRole('button', { name: 'Ajouter' }).click()
  await expect(page.getByRole('heading', { name: 'Ajouter une source' })).toBeVisible()
  const podcastSearch = page.getByLabel('Rechercher un podcast')
  await expect(podcastSearch).toHaveCSS('min-height', '56px')
  await expect(page.getByRole('button', { name: "Rechercher dans l’annuaire" })).toHaveCount(0)
  await podcastSearch.fill("Sur les routes de l'Asie")
  const result = page.locator('.podcast-results button').filter({ hasText: "Sur les routes de l'Asie" }).first()
  await expect(result).toBeVisible()
  await result.click()

  await expect(page.getByRole('heading', { name: "Sur les routes de l'Asie" })).toBeVisible()
  await expect(page.locator('.source-heading p')).toHaveCount(0)
  const firstEpisode = page.locator('.episode-item').first()
  await expect(firstEpisode).toBeVisible()
  await firstEpisode.locator('.episode-info').click()
  await expect(page.locator('.episode-detail .source-heading p')).toHaveCount(0)
  if ((page.viewportSize()?.width ?? 0) <= 700) {
    await expect(page.locator('.episode-detail .source-heading h2')).toHaveCSS('font-size', '18px')
    await expect(page.locator('.episode-detail .episode-analysis-card p')).toHaveCSS('display', 'none')
    await expect(page.locator('.episode-detail .episode-tracklist .section-heading > span')).toHaveCSS('font-size', '8px')
  }
  await page.getByRole('button', { name: /Retour à/ }).click()
  await firstEpisode.locator('.episode-play').click()
  await expect(page.locator('.mini-player-global')).toBeVisible()
  const outputButton = page.getByRole('button', { name: /Sortie audio :/ })
  await expect(outputButton).toBeVisible()
  await outputButton.click()
  await expect(page.getByRole('heading', { name: 'Écouter sur' })).toBeVisible()
  await expect(page.getByRole('button', { name: /Ce navigateur/ })).toBeVisible()
  await page.getByRole('button', { name: 'Fermer' }).click()
  if ((page.viewportSize()?.width ?? 0) <= 700) {
    const metaBox = await page.locator('.mini-meta').boundingBox()
    const controlsBox = await page.locator('.mini-player-global > button').first().boundingBox()
    expect(metaBox).not.toBeNull()
    expect(controlsBox).not.toBeNull()
    expect((metaBox?.y ?? 0) + (metaBox?.height ?? 0)).toBeLessThanOrEqual((controlsBox?.y ?? 0) + 1)
  }

  await page.reload()
  await page.getByRole('button', { name: 'Bibliothèque' }).last().click()
  await expect(page.getByText("Sur les routes de l'Asie", { exact: true })).toBeVisible()

  await page.getByRole('button', { name: 'Réglages' }).last().click()
  await expect(page.getByRole('heading', { name: 'Mises à jour' })).toBeVisible()
  await expect(page.getByText(/Version installée : 1\.0\.39/)).toBeVisible()
  await expect(page.getByText(/Application à jour/)).toBeVisible()

  expect(errors).toEqual([])
})

test('navigation sans onglet analyses et PWA', async ({ page }) => {
  await page.goto('/')
  await expect(page.locator('.mobile-nav button').filter({ hasText: 'Favoris' })).toHaveCount(1)
  await expect(page.getByRole('button', { name: 'Analyses' })).toHaveCount(0)
  await expect(page.locator('.mobile-nav button')).toHaveCount(4)

  const manifest = await page.request.get('/manifest.webmanifest')
  expect(manifest.ok()).toBeTruthy()
  expect((await manifest.json()).display).toBe('standalone')
  const serviceWorker = await page.request.get('/sw.js')
  expect(serviceWorker.ok()).toBeTruthy()
})

test('une donnée locale abîmée ne bloque pas le démarrage', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('podmix-favorites-v1', '{invalide')
    localStorage.setItem('podmix-history-v1', '{"ancien":true}')
    localStorage.setItem('podmix-offline-v1', '{"ancien":true}')
  })

  await page.goto('/')
  await expect(page.locator('.breadcrumbs strong')).toHaveText('Accueil')
  await expect(page.locator('.mobile-nav')).toBeAttached()
})

test('l’historique Hermes migre, recherche et supprime les anciennes écoutes', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('podmix-history-v1', JSON.stringify([{
      id: 'episode-hermes', title: 'Session Hermes', artist: 'Podcast mémoire', url: '/hermes.wav',
      position: 42, duration: 600, playedAt: '2026-08-12T10:00:00.000Z',
    }]))
  })

  await page.goto('/')
  await page.getByRole('button', { name: 'Voir l’historique' }).click()
  await expect(page.getByRole('heading', { name: 'Historique' })).toBeVisible()
  await expect(page.locator('.history-item')).toContainText('Session Hermes')
  await expect(page.locator('.history-item')).toContainText('repris à 0:42')

  await page.getByLabel('Rechercher dans l’historique').fill('introuvable')
  await expect(page.locator('.history-item')).toHaveCount(0)
  await page.getByLabel('Rechercher dans l’historique').fill('mémoire')
  await expect(page.locator('.history-item')).toHaveCount(1)
  await page.getByRole('button', { name: 'Supprimer Session Hermes de l’historique' }).click()
  await expect(page.locator('.history-item')).toHaveCount(0)
})

test('un catalogue proche de la limite de stockage reste utilisable', async ({ page }) => {
  await page.addInitScript(() => {
    const catalog = [{
      id: 'catalogue-volumineux', kind: 'podcast', title: 'Catalogue conservé', description: 'S'.repeat(5_000), artworkUrl: '',
      episodes: [{
        id: 'episode-conserve', title: 'Épisode conservé', description: 'E'.repeat(12_000), publishedAt: '',
        duration: '10:00', audioUrl: '/episode.wav', artworkUrl: '',
        tracks: [{ id: 1, time: 0, artist: 'Artiste', title: 'Morceau conservé', confidence: 90, source: 'manual' }],
      }],
    }]
    localStorage.setItem('podmix-catalog-v1', JSON.stringify(catalog))
    const nativeSetItem = Storage.prototype.setItem
    Storage.prototype.setItem = function (key, value) {
      if (key === 'podmix-catalog-v1' && value.length > 3_000) {
        throw new DOMException('Quota simulé', 'QuotaExceededError')
      }
      return nativeSetItem.call(this, key, value)
    }
  })

  await page.goto('/')
  await expect(page.locator('.mobile-nav')).toBeAttached()
  await page.getByRole('button', { name: 'Bibliothèque' }).last().click()
  await expect(page.getByText('Catalogue conservé', { exact: true })).toBeVisible()
  await page.getByText('Catalogue conservé', { exact: true }).click()
  await expect(page.getByText('Épisode conservé', { exact: true })).toBeVisible()
  await page.getByText('Épisode conservé', { exact: true }).click()
  await expect(page.locator('.episode-track')).toContainText('Morceau conservé')
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
  await page.getByRole('button', { name: 'Bibliothèque' }).last().click()
  const legend = page.locator('.media-card').filter({ hasText: 'LEGEND' })
  await expect(legend).toContainText('Émission')
  const pureTrance = page.locator('.media-card').filter({ hasText: 'Pure Trance Radio Podcast with Solarstone' })
  await expect(pureTrance).toContainText('Podcast')

  await page.getByRole('button', { name: 'Podcasts' }).click()
  await expect(legend).toHaveCount(0)
  await expect(pureTrance).toBeVisible()
  await page.getByRole('button', { name: 'Émissions' }).click()
  await expect(legend).toBeVisible()
  await expect(pureTrance).toHaveCount(0)

  await page.locator('.catalog-toolbar').getByRole('button', { name: 'Ajouter' }).click()
  await expect(page.locator('.source-mode').getByRole('button', { name: 'Émission' })).toHaveClass(/active/)
  await page.getByRole('button', { name: '×' }).click()

  await page.getByRole('button', { name: 'Tout' }).click()
  await page.locator('.catalog-toolbar').getByRole('button', { name: 'Ajouter' }).click()
  await expect(page.locator('.source-mode').getByRole('button', { name: 'Podcast' })).toHaveClass(/active/)
  await page.getByRole('button', { name: '×' }).click()

  await legend.click()
  const sourceType = page.locator('.source-heading > div > span').first()
  await expect(sourceType).toHaveText('Émission')
  await expect.poll(() => page.evaluate(() => {
    const catalog = JSON.parse(localStorage.getItem('podmix-catalog-v1') ?? '[]')
    return catalog.map((source: { title: string; kind: string; musical?: boolean }) => ({
      title: source.title,
      kind: source.kind,
      musical: source.musical,
    }))
  })).toEqual([
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
  await page.route('**/resume.wav', (route) => route.fulfill({
    body: silentWav(10),
    contentType: 'audio/wav',
  }))

  await page.goto('/')
  await expect(page.getByText(/Reprendre à 0:03/)).toBeVisible()
  await expect(page.getByText('Émission à reprendre', { exact: true })).toBeVisible()
  await expect(page.getByText('Morceau individuel à masquer')).toHaveCount(0)
  await expect(page.getByText('Titre analysé à masquer')).toHaveCount(0)
  await expect(page.getByText('DJ set à masquer')).toHaveCount(0)
  await expect(page.getByText('Épisode terminé à 98 %')).toHaveCount(0)
  const resumeEpisode = page.locator('.home-resume .episode-item').first()
  await expect(resumeEpisode.locator('.episode-play')).toBeVisible()
  await resumeEpisode.locator('.episode-play').click()

  await expect(page.locator('.mini-player-global')).toContainText('Épisode à reprendre')
  const positionSlider = page.getByRole('slider', { name: 'Position de lecture' })
  await expect(positionSlider).toBeVisible()
  await expect.poll(async () => Number(await positionSlider.getAttribute('max'))).toBeGreaterThanOrEqual(9)
  await expect.poll(() => page.evaluate(() => {
    const history = JSON.parse(localStorage.getItem('podmix-history-v1') ?? '[]')
    return history[0]?.position ?? 0
  })).toBeGreaterThanOrEqual(3)
  await expect(resumeEpisode).toHaveClass(/playing/)
  await resumeEpisode.locator('.episode-play').click()
  await expect(resumeEpisode).toHaveClass(/playing/)
  await expect(resumeEpisode.locator('.episode-play')).toHaveAttribute('aria-label', /Reprendre/)
  await resumeEpisode.locator('.episode-play').click()
  await expect(resumeEpisode.locator('.episode-play')).toHaveAttribute('aria-label', /Mettre en pause/)

  await resumeEpisode.locator('.episode-info').click()

  await expect(page.locator('.episode-detail')).toContainText('Épisode à reprendre')
  await expect(page.locator('.home-resume')).toHaveCount(0)
  await positionSlider.fill('5')
  await expect.poll(async () => Number(await positionSlider.inputValue())).toBeGreaterThanOrEqual(5)
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
      // A new partial replay must not erase the historical completed state.
      id: 'episode-done', title: 'Épisode terminé', artist: 'Podcast progression', url: '/episode-done.wav', position: 60, playedAt: '2026-08-01T09:00:00.000Z',
    }]))
    localStorage.setItem('podmix-completed-episodes-v1', JSON.stringify(['episode-done']))
  })

  await page.goto('/')
  await page.getByRole('img', { name: 'Logo Podcast progression' }).click()

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
  await page.route('**/test-tone.wav', (route) => route.fulfill({
    body: silentWav(2),
    contentType: 'audio/wav',
  }))

  await page.goto('/')
  await page.getByRole('button', { name: 'Bibliothèque' }).last().click()
  await page.locator('.media-card').filter({ hasText: 'Podcast musical test' }).click()
  await page.locator('.episode-info').filter({ hasText: 'Épisode avec tracklist' }).click()

  const firstTrack = page.locator('.episode-track').filter({ hasText: 'Morceau ciblé' })
  await expect(firstTrack).not.toContainText('100%')
  await expect(firstTrack.locator('strong > span')).toHaveCSS('white-space', 'nowrap')
  const firstTrackRow = page.locator('.episode-track-row').filter({ hasText: 'Morceau ciblé' })
  await firstTrackRow.getByRole('button', { name: 'Ajouter Morceau ciblé aux favoris' }).click()
  await expect(firstTrackRow.getByRole('button', { name: 'Retirer Morceau ciblé des favoris' })).toBeVisible()
  await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem('podmix-track-favorites-v1') ?? '[]'))).toContain('episode-test::track::1::0')

  await page.locator('.episode-detail .source-actions').getByRole('button', { name: 'Lire' }).click()
  await expect(page.locator('.episode-track.playing')).toContainText('Morceau ciblé')

  await page.locator('.episode-track').filter({ hasText: 'Morceau ciblé' }).click()

  await expect(page.locator('.mini-player-global')).toContainText('Morceau ciblé')
  await expect(page.locator('.episode-track.playing')).toContainText('Morceau ciblé')
  await expect.poll(() => page.evaluate(() => {
    const history = JSON.parse(localStorage.getItem('podmix-history-v1') ?? '[]')
    return history[0]?.position ?? 0
  })).toBe(0.25)
  await expect(page.locator('.mini-player-global')).toContainText('Morceau suivant')

  await page.locator('.episode-detail .source-actions').getByRole('button', { name: 'Lire' }).click()
  await expect(page.locator('.mini-player-global')).toContainText('Épisode avec tracklist')
})

test('les favoris gardent leur épisode et s’enchaînent', async ({ page }) => {
  await page.route('**/v1/catalog/links', async (route) => {
    const request = route.request().postDataJSON() as { tracks: Array<{ key: string }> }
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        items: request.tracks.map((track) => track.key.includes('episode-favori-a')
          ? { key: track.key, spotifyUrl: 'https://open.spotify.com/track/detected-a' }
          : { key: track.key, deezerUrl: 'https://www.deezer.com/track/detected-b' }),
      }),
    })
  })
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
  await page.route('**/favorite-*.wav', (route) => route.fulfill({
    body: silentWav(1),
    contentType: 'audio/wav',
  }))

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
  await page.getByRole('button', { name: 'Morceau suivant' }).click()
  await expect(page.locator('.mini-player-global')).toContainText('Favori du second épisode')
  await expect(favorites.locator('.episode-item.playing')).toContainText('Favori du second épisode')
  // Le poll natif/web s'exécute chaque seconde. La file de favoris est à
  // l'index 1, mais ce favori est à l'index 0 de son épisode : ces deux index
  // ne doivent jamais être confondus dans le titre du mini-lecteur.
  await page.waitForTimeout(1_200)
  await expect(page.locator('.mini-player-global')).toContainText('Favori du second épisode')
  await expect(page.locator('.mini-player-global')).not.toContainText('Titre étranger à la file')
})
