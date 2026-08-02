import { chromium } from 'playwright'

const endpoint = process.env.PODMIX_CDP ?? 'http://127.0.0.1:9222'
const results = []
const record = (name, ok, detail = '') => results.push({ name, ok, detail })
const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))
const playerState = (page) => page.evaluate(() => window.Capacitor.Plugins.PodmixPlayer.getState())
async function waitForReady(page, timeout = 25_000) {
  const deadline = Date.now() + timeout
  let state = await playerState(page)
  while (state.playbackState !== 3 && !state.error && Date.now() < deadline) {
    await wait(500)
    state = await playerState(page)
  }
  return state
}

const browser = await chromium.connectOverCDP(endpoint)
const page = browser.contexts()[0].pages()[0]
const backup = await page.evaluate(async () => {
  const local = { ...localStorage }
  const db = await new Promise((resolve, reject) => {
    const request = indexedDB.open('podmix-studio', 1)
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
  const sessions = await new Promise((resolve, reject) => {
    const request = db.transaction('sessions', 'readonly').objectStore('sessions').getAll()
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
  db.close()
  return { local, sessions }
})

try {
  const platform = await page.evaluate(() => ({
    platform: window.Capacitor?.getPlatform?.(),
    native: window.Capacitor?.isNativePlatform?.(),
    plugins: Object.keys(window.Capacitor?.Plugins ?? {}),
  }))
  record('Pont Capacitor natif', platform.platform === 'android' && platform.native === true, JSON.stringify(platform))
  record('Plugin PodmixPlayer', platform.plugins.includes('PodmixPlayer'), platform.plugins.join(', '))

  const playButton = page.getByRole('button', { name: 'Lire Captive Soul by KOROLOVA' })
  record('Catalogue restauré', await playButton.count() === 1)
  await playButton.evaluate((element) => element.click())
  let state = await waitForReady(page)
  record('Lecture podcast', state.playing && state.playbackState === 3, JSON.stringify(state))
  record('Métadonnées et durée', state.title === 'Captive Soul 097' && state.durationSeconds > 3500, `${state.title}, ${state.durationSeconds}`)
  record('Mini-lecteur', await page.locator('.mini-player-global').count() === 1)

  state = await page.evaluate(() => window.Capacitor.Plugins.PodmixPlayer.pause())
  record('Pause', state.playing === false, JSON.stringify(state))
  state = await page.evaluate(() => window.Capacitor.Plugins.PodmixPlayer.seekTo({ positionSeconds: 42 }))
  record('Seek', Math.abs(state.positionSeconds - 42) < 2, `${state.positionSeconds}`)
  state = await page.evaluate(() => window.Capacitor.Plugins.PodmixPlayer.play())
  await wait(1200)
  state = await page.evaluate(() => window.Capacitor.Plugins.PodmixPlayer.getState())
  record('Reprise', state.playing && state.positionSeconds >= 42, `${state.positionSeconds}`)

  const secondUrl = 'https://audio.thisisdistorted.com/repository/audio/episodes/Captive_Soul_096_192k-1783683094288544398-NDI4NDQtODY1MjI2MzY=.mp3'
  state = await page.evaluate((secondUrl) => window.Capacitor.Plugins.PodmixPlayer.setQueue({
    items: [
      { id: 'korolova-097', url: 'https://audio.thisisdistorted.com/repository/audio/episodes/Captive_Soul_097_192k-1784898982063251543-NDI4OTUtODY0MTkxOTE=.mp3', title: 'Captive Soul 097', artist: 'KOROLOVA' },
      { id: 'korolova-096', url: secondUrl, title: 'Captive Soul 096', artist: 'KOROLOVA' },
    ],
    startIndex: 0,
    autoplay: false,
  }), secondUrl)
  record('Création de file', state.queueSize === 2 && state.hasNext, JSON.stringify(state))
  state = await page.evaluate(() => window.Capacitor.Plugins.PodmixPlayer.next())
  record('Suivant', state.queueIndex === 1 && state.mediaId === 'korolova-096', JSON.stringify(state))
  state = await page.evaluate(() => window.Capacitor.Plugins.PodmixPlayer.previous())
  record('Précédent', state.queueIndex === 0 && state.mediaId === 'korolova-097', JSON.stringify(state))

  const storage = await page.evaluate(() => window.Capacitor.Plugins.PodmixPlayer.getStorage())
  record('Stockage', storage.totalBytes > 0 && storage.availableBytes > 0, JSON.stringify(storage))
  try {
    const cast = await page.evaluate(() => window.Capacitor.Plugins.PodmixPlayer.getCastState())
    record('État Google Cast', typeof cast.connected === 'boolean', JSON.stringify(cast))
  } catch (error) {
    record('État Google Cast', false, String(error))
  }
  let boseRejected = false
  try {
    await page.evaluate(() => window.Capacitor.Plugins.PodmixPlayer.boseGetState({ ip: '8.8.8.8' }))
  } catch {
    boseRejected = true
  }
  record('Protection Bose réseau privé', boseRejected)

  await page.reload()
  await wait(1200)
  record('Persistance catalogue', await page.getByText('Captive Soul by KOROLOVA', { exact: true }).count() > 0)
  record('Persistance historique', await page.getByText(/reprise à/).count() > 0)
} catch (error) {
  record('Exécution de la campagne', false, error?.stack ?? String(error))
} finally {
  await page.evaluate(async (backup) => {
    localStorage.clear()
    for (const [key, value] of Object.entries(backup.local)) localStorage.setItem(key, value)
    const db = await new Promise((resolve, reject) => {
      const request = indexedDB.open('podmix-studio', 1)
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
    })
    await new Promise((resolve, reject) => {
      const transaction = db.transaction('sessions', 'readwrite')
      const store = transaction.objectStore('sessions')
      store.clear()
      for (const session of backup.sessions) store.put(session)
      transaction.oncomplete = resolve
      transaction.onerror = () => reject(transaction.error)
    })
    db.close()
  }, backup)
  await page.evaluate(() => window.Capacitor.Plugins.PodmixPlayer.pause()).catch(() => undefined)
}

for (const result of results) {
  console.log(`${result.ok ? 'PASS' : 'FAIL'}\t${result.name}\t${result.detail}`)
}
process.exit(results.some((result) => !result.ok) ? 1 : 0)
