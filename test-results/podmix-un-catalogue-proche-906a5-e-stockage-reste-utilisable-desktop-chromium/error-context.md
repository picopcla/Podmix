# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: podmix.spec.ts >> un catalogue proche de la limite de stockage reste utilisable
- Location: tests/e2e/podmix.spec.ts:117:1

# Error details

```
Error: expect(locator).toBeAttached() failed

Locator: locator('.mobile-nav')
Expected: attached
Timeout: 15000ms
Error: element(s) not found

Call log:
  - Expect "toBeAttached" with timeout 15000ms
  - waiting for locator('.mobile-nav')

```

```yaml
- main:
  - text: podmix
  - button "Rechercher"
  - button "Ajouter une source"
  - button "Voir les favoris"
  - button "Réglages"
  - heading "Podcasts" [level=2]
  - text: "1"
  - article:
    - button "Lire Catalogue conservé"
    - text: Podcast
    - heading "Catalogue conservé" [level=2]
    - paragraph: 1 épisodes
    - button "Favori"
    - button "Télécharger le dernier épisode"
    - button "Ouvrir la source"
```

# Test source

```ts
  38  |   await page.getByRole('button', { name: 'Fermer' }).click()
  39  | 
  40  |   await page.getByRole('button', { name: 'Bibliothèque' }).last().click()
  41  |   await expect(page.locator('.catalog-grid')).toHaveClass(/home-catalog-grid/)
  42  |   await page.locator('.catalog-toolbar').getByRole('button', { name: 'Ajouter' }).click()
  43  |   await expect(page.getByRole('heading', { name: 'Ajouter une source' })).toBeVisible()
  44  |   const podcastSearch = page.getByLabel('Rechercher un podcast')
  45  |   await expect(podcastSearch).toHaveCSS('min-height', '56px')
  46  |   await expect(page.getByRole('button', { name: "Rechercher dans l’annuaire" })).toHaveCount(0)
  47  |   await podcastSearch.fill("Sur les routes de l'Asie")
  48  |   const result = page.locator('.podcast-results button').filter({ hasText: "Sur les routes de l'Asie" }).first()
  49  |   await expect(result).toBeVisible()
  50  |   await result.click()
  51  | 
  52  |   await expect(page.getByRole('heading', { name: "Sur les routes de l'Asie" })).toBeVisible()
  53  |   await expect(page.locator('.source-heading p')).toHaveCount(0)
  54  |   const firstEpisode = page.locator('.episode-item').first()
  55  |   await expect(firstEpisode).toBeVisible()
  56  |   await firstEpisode.locator('.episode-info').click()
  57  |   await expect(page.locator('.episode-detail .source-heading p')).toHaveCount(0)
  58  |   if ((page.viewportSize()?.width ?? 0) <= 700) {
  59  |     await expect(page.locator('.episode-detail .source-heading h2')).toHaveCSS('font-size', '18px')
  60  |     await expect(page.locator('.episode-detail .episode-analysis-card p')).toHaveCSS('display', 'none')
  61  |     await expect(page.locator('.episode-detail .episode-tracklist .section-heading > span')).toHaveCSS('font-size', '8px')
  62  |   }
  63  |   await page.getByRole('button', { name: /Retour à/ }).click()
  64  |   await firstEpisode.locator('.episode-play').click()
  65  |   await expect(page.locator('.mini-player-global')).toBeVisible()
  66  |   const outputButton = page.getByRole('button', { name: /Sortie audio :/ })
  67  |   await expect(outputButton).toBeVisible()
  68  |   await outputButton.click()
  69  |   await expect(page.getByRole('heading', { name: 'Écouter sur' })).toBeVisible()
  70  |   await expect(page.getByRole('button', { name: /Ce navigateur/ })).toBeVisible()
  71  |   await page.getByRole('button', { name: 'Fermer' }).click()
  72  |   if ((page.viewportSize()?.width ?? 0) <= 700) {
  73  |     const metaBox = await page.locator('.mini-meta').boundingBox()
  74  |     const controlsBox = await page.locator('.mini-player-global > button').first().boundingBox()
  75  |     expect(metaBox).not.toBeNull()
  76  |     expect(controlsBox).not.toBeNull()
  77  |     expect((metaBox?.y ?? 0) + (metaBox?.height ?? 0)).toBeLessThanOrEqual((controlsBox?.y ?? 0) + 1)
  78  |   }
  79  | 
  80  |   await page.reload()
  81  |   await page.getByRole('button', { name: 'Bibliothèque' }).last().click()
  82  |   await expect(page.getByText("Sur les routes de l'Asie", { exact: true })).toBeVisible()
  83  | 
  84  |   await page.getByRole('button', { name: 'Réglages' }).last().click()
  85  |   await expect(page.getByRole('heading', { name: 'Mises à jour' })).toBeVisible()
  86  |   await expect(page.getByText(/Version installée : 1\.0\.39/)).toBeVisible()
  87  |   await expect(page.getByText(/Application à jour/)).toBeVisible()
  88  | 
  89  |   expect(errors).toEqual([])
  90  | })
  91  | 
  92  | test('navigation sans onglet analyses et PWA', async ({ page }) => {
  93  |   await page.goto('/')
  94  |   await expect(page.locator('.mobile-nav button').filter({ hasText: 'Favoris' })).toHaveCount(1)
  95  |   await expect(page.getByRole('button', { name: 'Analyses' })).toHaveCount(0)
  96  |   await expect(page.locator('.mobile-nav button')).toHaveCount(4)
  97  | 
  98  |   const manifest = await page.request.get('/manifest.webmanifest')
  99  |   expect(manifest.ok()).toBeTruthy()
  100 |   expect((await manifest.json()).display).toBe('standalone')
  101 |   const serviceWorker = await page.request.get('/sw.js')
  102 |   expect(serviceWorker.ok()).toBeTruthy()
  103 | })
  104 | 
  105 | test('une donnée locale abîmée ne bloque pas le démarrage', async ({ page }) => {
  106 |   await page.addInitScript(() => {
  107 |     localStorage.setItem('podmix-favorites-v1', '{invalide')
  108 |     localStorage.setItem('podmix-history-v1', '{"ancien":true}')
  109 |     localStorage.setItem('podmix-offline-v1', '{"ancien":true}')
  110 |   })
  111 | 
  112 |   await page.goto('/')
  113 |   await expect(page.locator('.breadcrumbs strong')).toHaveText('Accueil')
  114 |   await expect(page.locator('.mobile-nav')).toBeAttached()
  115 | })
  116 | 
  117 | test('un catalogue proche de la limite de stockage reste utilisable', async ({ page }) => {
  118 |   await page.addInitScript(() => {
  119 |     const catalog = [{
  120 |       id: 'catalogue-volumineux', kind: 'podcast', title: 'Catalogue conservé', description: 'S'.repeat(5_000), artworkUrl: '',
  121 |       episodes: [{
  122 |         id: 'episode-conserve', title: 'Épisode conservé', description: 'E'.repeat(12_000), publishedAt: '',
  123 |         duration: '10:00', audioUrl: '/episode.wav', artworkUrl: '',
  124 |         tracks: [{ id: 1, time: 0, artist: 'Artiste', title: 'Morceau conservé', confidence: 90, source: 'manual' }],
  125 |       }],
  126 |     }]
  127 |     localStorage.setItem('podmix-catalog-v1', JSON.stringify(catalog))
  128 |     const nativeSetItem = Storage.prototype.setItem
  129 |     Storage.prototype.setItem = function (key, value) {
  130 |       if (key === 'podmix-catalog-v1' && value.length > 3_000) {
  131 |         throw new DOMException('Quota simulé', 'QuotaExceededError')
  132 |       }
  133 |       return nativeSetItem.call(this, key, value)
  134 |     }
  135 |   })
  136 | 
  137 |   await page.goto('/')
> 138 |   await expect(page.locator('.mobile-nav')).toBeAttached()
      |                                             ^ Error: expect(locator).toBeAttached() failed
  139 |   await page.getByRole('button', { name: 'Bibliothèque' }).last().click()
  140 |   await expect(page.getByText('Catalogue conservé', { exact: true })).toBeVisible()
  141 |   await page.getByText('Catalogue conservé', { exact: true }).click()
  142 |   await expect(page.getByText('Épisode conservé', { exact: true })).toBeVisible()
  143 |   await page.getByText('Épisode conservé', { exact: true }).click()
  144 |   await expect(page.locator('.episode-track')).toContainText('Morceau conservé')
  145 | })
  146 | 
  147 | test('les catégories héritées sont corrigées sans contaminer les prochains ajouts', async ({ page }) => {
  148 |   await page.addInitScript(() => {
  149 |     localStorage.setItem('podmix-catalog-v1', JSON.stringify([
  150 |       {
  151 |         id: 'https://example.com/legend.xml',
  152 |         kind: 'podcast',
  153 |         title: 'LEGEND',
  154 |         description: 'Émission parlée',
  155 |         artworkUrl: '',
  156 |         episodes: [],
  157 |       },
  158 |       {
  159 |         id: 'https://feed.podbean.com/richve/feed.xml',
  160 |         kind: 'show',
  161 |         title: 'Pure Trance Radio Podcast with Solarstone',
  162 |         description: 'Podcast musical',
  163 |         feedUrl: 'https://feed.podbean.com/richve/feed.xml',
  164 |         artworkUrl: '',
  165 |         episodes: [],
  166 |       },
  167 |     ]))
  168 |   })
  169 | 
  170 |   await page.goto('/')
  171 |   await page.getByRole('button', { name: 'Bibliothèque' }).last().click()
  172 |   const legend = page.locator('.media-card').filter({ hasText: 'LEGEND' })
  173 |   await expect(legend).toContainText('Émission')
  174 |   const pureTrance = page.locator('.media-card').filter({ hasText: 'Pure Trance Radio Podcast with Solarstone' })
  175 |   await expect(pureTrance).toContainText('Podcast')
  176 | 
  177 |   await page.getByRole('button', { name: 'Podcasts' }).click()
  178 |   await expect(legend).toHaveCount(0)
  179 |   await expect(pureTrance).toBeVisible()
  180 |   await page.getByRole('button', { name: 'Émissions' }).click()
  181 |   await expect(legend).toBeVisible()
  182 |   await expect(pureTrance).toHaveCount(0)
  183 | 
  184 |   await page.locator('.catalog-toolbar').getByRole('button', { name: 'Ajouter' }).click()
  185 |   await expect(page.locator('.source-mode').getByRole('button', { name: 'Émission' })).toHaveClass(/active/)
  186 |   await page.getByRole('button', { name: '×' }).click()
  187 | 
  188 |   await page.getByRole('button', { name: 'Tout' }).click()
  189 |   await page.locator('.catalog-toolbar').getByRole('button', { name: 'Ajouter' }).click()
  190 |   await expect(page.locator('.source-mode').getByRole('button', { name: 'Podcast' })).toHaveClass(/active/)
  191 |   await page.getByRole('button', { name: '×' }).click()
  192 | 
  193 |   await legend.click()
  194 |   const sourceType = page.locator('.source-heading > div > span').first()
  195 |   await expect(sourceType).toHaveText('Émission')
  196 |   await expect.poll(() => page.evaluate(() => {
  197 |     const catalog = JSON.parse(localStorage.getItem('podmix-catalog-v1') ?? '[]')
  198 |     return catalog.map((source: { title: string; kind: string; musical?: boolean }) => ({
  199 |       title: source.title,
  200 |       kind: source.kind,
  201 |       musical: source.musical,
  202 |     }))
  203 |   })).toEqual([
  204 |     { title: 'LEGEND', kind: 'show', musical: false },
  205 |     { title: 'Pure Trance Radio Podcast with Solarstone', kind: 'podcast', musical: undefined },
  206 |   ])
  207 | })
  208 | 
  209 | test('le raccourci d’accueil reprend à la dernière position', async ({ page }) => {
  210 |   await page.addInitScript(() => {
  211 |     const episode = (id: string, title: string, audioUrl: string) => ({
  212 |       id, title, description: '', publishedAt: '30 juillet 2026', duration: '0:10', audioUrl, artworkUrl: '',
  213 |     })
  214 |     localStorage.setItem('podmix-catalog-v1', JSON.stringify([{
  215 |       id: 'podcast-test', kind: 'podcast', title: 'Podcast test', description: '', artworkUrl: '',
  216 |       episodes: [
  217 |         episode('episode-reprise', 'Épisode à reprendre', '/resume.wav'),
  218 |         episode('episode-termine', 'Épisode terminé à 98 %', '/done.wav'),
  219 |       ],
  220 |     }, {
  221 |       id: 'emission-test', kind: 'show', title: 'Émission test', description: '', artworkUrl: '',
  222 |       episodes: [episode('emission-reprise', 'Émission à reprendre', '/show.wav')],
  223 |     }, {
  224 |       id: 'dj-test', kind: 'dj', title: 'DJ test', description: '', artworkUrl: '',
  225 |       episodes: [episode('dj-reprise', 'DJ set à masquer', '/dj.wav')],
  226 |     }]))
  227 |     localStorage.setItem('podmix-history-v1', JSON.stringify([
  228 |       {
  229 |         id: 'episode-reprise', title: 'Morceau individuel à masquer', artist: 'Artiste du morceau', url: '/resume.wav',
  230 |         position: 3, duration: 10, playedAt: '2026-07-30T10:00:00.000Z',
  231 |       },
  232 |       {
  233 |         id: 'emission-reprise', title: 'Titre analysé à masquer', artist: 'Artiste analysé', url: '/show.wav',
  234 |         position: 4, duration: 10, playedAt: '2026-07-30T09:30:00.000Z',
  235 |       },
  236 |       {
  237 |         id: 'dj-reprise', title: 'DJ set à masquer', artist: 'DJ test', url: '/dj.wav',
  238 |         position: 2, duration: 10, playedAt: '2026-07-30T09:00:00.000Z',
```