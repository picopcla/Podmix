import fs from 'node:fs/promises'
import { execFileSync } from 'node:child_process'

const endpoint = process.env.PODMIX_EMULATOR_CDP ?? 'http://127.0.0.1:9222'
const outputDir = process.env.PODMIX_EMULATOR_CAPTURE_DIR ?? 'artifacts/emulator-captures'
const sampleRate = 8_000
const audioSeconds = 60
const audioDataSize = sampleRate * audioSeconds * 2
const audioFixture = Buffer.alloc(44 + audioDataSize)
audioFixture.write('RIFF', 0)
audioFixture.writeUInt32LE(36 + audioDataSize, 4)
audioFixture.write('WAVEfmt ', 8)
audioFixture.writeUInt32LE(16, 16)
audioFixture.writeUInt16LE(1, 20)
audioFixture.writeUInt16LE(1, 22)
audioFixture.writeUInt32LE(sampleRate, 24)
audioFixture.writeUInt32LE(sampleRate * 2, 28)
audioFixture.writeUInt16LE(2, 32)
audioFixture.writeUInt16LE(16, 34)
audioFixture.write('data', 36)
audioFixture.writeUInt32LE(audioDataSize, 40)
await fs.writeFile(`${outputDir}/entities.wav`, audioFixture)
const [target] = await fetch(`${endpoint}/json/list`).then((response) => response.json())
if (!target?.webSocketDebuggerUrl) throw new Error('WebView Podmix introuvable')

const socket = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((resolve, reject) => {
  socket.addEventListener('open', resolve, { once: true })
  socket.addEventListener('error', reject, { once: true })
})

let requestId = 0
const pending = new Map()
socket.addEventListener('message', (event) => {
  const message = JSON.parse(event.data)
  if (!message.id || !pending.has(message.id)) return
  const { resolve, reject, timer } = pending.get(message.id)
  pending.delete(message.id)
  clearTimeout(timer)
  if (message.error) reject(new Error(message.error.message))
  else resolve(message.result)
})

function send(method, params = {}) {
  const id = ++requestId
  socket.send(JSON.stringify({ id, method, params }))
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id)
      reject(new Error(`Commande CDP sans réponse : ${method}`))
    }, 60_000)
    pending.set(id, { resolve, reject, timer })
  })
}

async function evaluate(expression) {
  const response = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (response.exceptionDetails) throw new Error(response.exceptionDetails.text)
  return response.result?.value
}

async function waitFor(expression, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      if (await evaluate(expression)) return
    } catch {
      // A reload briefly destroys the JavaScript execution context.
    }
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  throw new Error(`Délai dépassé : ${expression}`)
}

async function screenshot(name) {
  const capture = execFileSync('adb', ['exec-out', 'screencap', '-p'], { maxBuffer: 20 * 1024 * 1024 })
  await fs.writeFile(`${outputDir}/${name}`, capture)
}

async function installNativeFixtureBootstrap() {
  await send('Page.addScriptToEvaluateOnNewDocument', { source: `(() => {
    const install = () => {
      const capacitor = window.Capacitor
      if (!capacitor?.nativePromise) {
        setTimeout(install, 0)
        return
      }
      if (capacitor.__podmixFixtureInstalled) return
      capacitor.__podmixFixtureInstalled = true
      const originalNativePromise = capacitor.nativePromise.bind(capacitor)
      capacitor.nativePromise = (plugin, method, options = {}) => {
          if (plugin !== 'PodmixPlayer') return originalNativePromise(plugin, method, options)
          const fixture = JSON.parse(localStorage.getItem('podmix-emulator-download-fixture-v1') || 'null')
          if (!fixture) return originalNativePromise(plugin, method, options)
          if (method === 'download') {
            const calls = Number(localStorage.getItem('podmix-emulator-download-calls-v1') || '0') + 1
            localStorage.setItem('podmix-emulator-download-calls-v1', String(calls))
            if (fixture.reject) return Promise.reject(new Error('Échec natif factice'))
            return Promise.resolve({ ...fixture, id: options.id ?? fixture.id })
          }
          if (method === 'getDownload') return Promise.resolve({ id: options.id, status: 'unknown' })
          if (method === 'listDownloads') return Promise.resolve({ downloads: [] })
          if (method === 'removeDownload') return Promise.resolve({ id: options.id, status: 'not_found', removed: true })
          return originalNativePromise(plugin, method, options)
      }
    }
    install()
  })()` })
}

async function openEpisode(title) {
  const episodeVisible = await evaluate(`Boolean([...document.querySelectorAll('.episode-info')].find((node) => node.textContent.includes(${JSON.stringify(title)})))`)
  if (!episodeVisible) {
    await evaluate(`document.querySelector('[aria-label="Émission factice"]').click()`)
    await waitFor(`Boolean([...document.querySelectorAll('.episode-info')].find((node) => node.textContent.includes(${JSON.stringify(title)})))`, 120_000)
  }
  await evaluate(`[...document.querySelectorAll('.episode-info')].find((node) => node.textContent.includes(${JSON.stringify(title)})).click()`)
  await waitFor(`Boolean(document.querySelector('.episode-download-action'))`, 120_000)
}

try {
  await send('Page.enable')
  await send('Runtime.enable')
  await installNativeFixtureBootstrap()
  await evaluate(`(() => {
    localStorage.clear()
    localStorage.setItem('podmix-emulator-download-fixture-v1', JSON.stringify({ id: 'episode-emulator-test', status: 'queued', bytesDownloaded: 0, totalBytes: 100 }))
    localStorage.setItem('podmix-emulator-download-calls-v1', '0')
    localStorage.setItem('podmix-settings-v1', JSON.stringify({ automaticAnalysis: false }))
    localStorage.setItem('podmix-catalog-v1', JSON.stringify([{
      id: 'source-entities-release', kind: 'podcast', title: 'Above &amp;amp; Beyond', description: '', artworkUrl: '',
      feedUrl: 'https://example.test/feed?a=1&amp;b=2',
      episodes: [{
        id: 'episode-entities-release', title: 'Find Your Harmony &amp;amp; Friends',
        description: 'Donnée factice &lt;b&gt;non interprétée&lt;/b&gt;.', publishedAt: '2026-10-13',
        duration: '01:00', audioUrl: 'http://10.0.2.2:8765/entities.wav?a=1&amp;b=2', artworkUrl: '',
        tracks: [{
          id: 1, time: 0, artist: 'Mark Sherry, BiXX &amp; Sue McLaren', title: 'Believe &#38; Receive', confidence: 100, source: 'manual'
        }, {
          id: 2, time: 30, artist: 'David Forbes &amp;amp; Lostly', title: 'Second &amp; Final', confidence: 100, source: 'manual'
        }]
      }]
    }, {
      id: 'source-emulator-test', kind: 'show', title: 'Émission factice', description: '', artworkUrl: '',
      episodes: [{
        id: 'episode-emulator-test', title: 'Épisode de test en attente',
        description: 'Donnée factice destinée au test émulateur.', publishedAt: '2026-10-13',
        duration: '10:00', audioUrl: 'https://example.test/slow.mp3', artworkUrl: ''
      }, {
        id: 'episode-emulator-progress', title: 'Épisode de test à 45 pour cent',
        description: 'Donnée factice destinée au test émulateur.', publishedAt: '2026-10-12',
        duration: '09:00', audioUrl: 'https://example.test/progress.mp3', artworkUrl: ''
      }, {
        id: 'episode-emulator-completed', title: 'Épisode de test téléchargé',
        description: 'Donnée factice destinée au test émulateur.', publishedAt: '2026-10-11',
        duration: '08:30', audioUrl: 'https://example.test/completed.mp3', artworkUrl: ''
      }, {
        id: 'episode-emulator-retry', title: 'Épisode de test à réessayer',
        description: 'Donnée factice destinée au test émulateur.', publishedAt: '2026-10-10',
        duration: '08:00', audioUrl: 'https://example.test/retry.mp3', artworkUrl: ''
      }]
    }]))
    return true
  })()`)
  await send('Page.reload')

  await waitFor(`Boolean(document.querySelector('[aria-label="Émission factice"]'))`)
  await openEpisode('Épisode de test en attente')

  const initialLabel = await evaluate(`document.querySelector('.episode-download-action').getAttribute('aria-label')`)
  if (!/Télécharger pour écouter hors connexion/.test(initialLabel)) throw new Error(`État initial inattendu : ${initialLabel}`)
  await screenshot('download-before.png')

  await evaluate(`document.querySelector('.episode-download-action').click(); document.querySelector('.episode-download-action').click()`)
  await waitFor(`Boolean(document.querySelector('.episode-download-feedback')?.textContent.trim())`)
  const status = await evaluate(`document.querySelector('.episode-download-feedback').textContent.trim()`)
  if (!/Demande envoyée|Demande reçue|Téléchargement démarré/.test(status)) throw new Error(`Statut inattendu après le clic : ${status}`)
  await screenshot('detail-download-pending.png')

  const label = await evaluate(`document.querySelector('.episode-download-action').getAttribute('aria-label')`)
  const text = await evaluate(`document.querySelector('.episode-download-action').textContent.trim()`)
  if (!label || !text || text === 'Hors connexion') throw new Error(`Bouton non actualisé : label=${label} texte=${text}`)

  const prematureCheck = await evaluate(`Boolean(document.querySelector('.episode-download-action svg.lucide-check'))`)
  if (prematureCheck) throw new Error('Coche affichée avant la fin du téléchargement')
  const callsAfterDoubleClick = await evaluate(`Number(localStorage.getItem('podmix-emulator-download-calls-v1'))`)
  if (callsAfterDoubleClick !== 1) throw new Error(`Double requête native : ${callsAfterDoubleClick}`)

  await evaluate(`document.querySelector('.back-button').click()`)
  await waitFor(`Boolean([...document.querySelectorAll('.episode-info')].find((node) => node.textContent.includes('Épisode de test à 45 pour cent')))`)
  await evaluate(`localStorage.setItem('podmix-emulator-download-fixture-v1', JSON.stringify({ id: 'episode-emulator-progress', status: 'downloading', bytesDownloaded: 45, totalBytes: 100 })); localStorage.setItem('podmix-emulator-download-calls-v1', '0')`)
  await openEpisode('Épisode de test à 45 pour cent')
  await evaluate(`document.querySelector('.episode-download-action').click()`)
  await waitFor(`Number(localStorage.getItem('podmix-emulator-download-calls-v1')) === 1`, 120_000)
  const progressState = await evaluate(`(() => ({
    fixture: JSON.parse(localStorage.getItem('podmix-emulator-download-fixture-v1') || 'null'),
    text: document.querySelector('.episode-download-action')?.textContent.trim(),
    label: document.querySelector('.episode-download-action')?.getAttribute('aria-label'),
    className: document.querySelector('.episode-download-action')?.className,
    feedback: document.querySelector('.episode-download-feedback')?.textContent.trim(),
    offline: JSON.parse(localStorage.getItem('podmix-offline-v1') || '[]'),
  }))()`)
  console.log(JSON.stringify({ progressState }))
  if (!/45\s*%/.test(progressState.text ?? '')) throw new Error(`Progression Android inattendue : ${JSON.stringify(progressState)}`)
  const progressCheck = await evaluate(`Boolean(document.querySelector('.episode-download-action svg.lucide-check'))`)
  if (progressCheck) throw new Error('Coche affichée pendant la progression')
  await screenshot('detail-download-progress.png')

  await evaluate(`document.querySelector('.back-button').click()`)
  await waitFor(`Boolean([...document.querySelectorAll('.episode-info')].find((node) => node.textContent.includes('Épisode de test téléchargé')))`)
  await evaluate(`localStorage.setItem('podmix-emulator-download-fixture-v1', JSON.stringify({ id: 'episode-emulator-completed', status: 'completed', bytesDownloaded: 100, totalBytes: 100, localUri: 'file:///data/factice.mp3' })); localStorage.setItem('podmix-emulator-download-calls-v1', '0')`)
  await openEpisode('Épisode de test téléchargé')
  await evaluate(`document.querySelector('.episode-download-action').click()`)
  await waitFor(`document.querySelector('.episode-download-action')?.getAttribute('aria-label') === 'Téléchargé · disponible hors connexion'`, 120_000)
  const completedDetailCheck = await evaluate(`Boolean(document.querySelector('.episode-download-action svg.lucide-check'))`)
  const completedDetailTrash = await evaluate(`Boolean(document.querySelector('.episode-download-remove[aria-label="Supprimer le téléchargement"]'))`)
  const completedDetailDisabled = await evaluate(`document.querySelector('.episode-download-action')?.disabled === true`)
  if (!completedDetailCheck || !completedDetailTrash || !completedDetailDisabled) {
    throw new Error('État terminé incomplet dans la fiche')
  }
  await screenshot('detail-download-completed.png')

  await evaluate(`document.querySelector('.back-button').click()`)
  await waitFor(`Boolean([...document.querySelectorAll('.episode-info')].find((node) => node.textContent.includes('Épisode de test téléchargé')))`)
  await waitFor(`Boolean([...document.querySelectorAll('.episode-item')].find((node) => node.textContent.includes('Épisode de test téléchargé'))?.querySelector('.episode-download.completed svg.lucide-check'))`)
  const completedRowActions = await evaluate(`(() => {
    const row = [...document.querySelectorAll('.episode-item')].find((node) => node.textContent.includes('Épisode de test téléchargé'))
    return {
      label: row?.querySelector('.episode-download.completed')?.getAttribute('aria-label'),
      disabled: row?.querySelector('.episode-download.completed')?.disabled,
      trash: Boolean(row?.querySelector('.remove-download[aria-label="Supprimer le téléchargement"]')),
    }
  })()`)
  if (completedRowActions.label !== 'Téléchargé · disponible hors connexion' || !completedRowActions.disabled || !completedRowActions.trash) {
    throw new Error(`État terminé incomplet dans la liste : ${JSON.stringify(completedRowActions)}`)
  }
  await screenshot('list-download-completed.png')

  await evaluate(`localStorage.setItem('podmix-emulator-download-fixture-v1', JSON.stringify({ id: 'episode-emulator-retry', reject: true })); localStorage.setItem('podmix-emulator-download-calls-v1', '0')`)
  await openEpisode('Épisode de test à réessayer')
  await evaluate(`document.querySelector('.episode-download-action').click()`)
  await waitFor(`document.querySelector('.episode-download-action')?.classList.contains('failed')`, 120_000)
  await screenshot('detail-download-failed.png')
  await evaluate(`localStorage.setItem('podmix-emulator-download-fixture-v1', JSON.stringify({ id: 'episode-emulator-retry', status: 'queued', bytesDownloaded: 0, totalBytes: 100 })); localStorage.setItem('podmix-emulator-download-calls-v1', '0')`)
  await evaluate(`document.querySelector('.episode-download-action').click(); document.querySelector('.episode-download-action').click()`)
  await waitFor(`document.querySelector('.episode-download-action')?.classList.contains('queued')`, 120_000)
  const retryCalls = await evaluate(`Number(localStorage.getItem('podmix-emulator-download-calls-v1'))`)
  if (retryCalls !== 1) throw new Error(`Double requête native au réessai : ${retryCalls}`)
  const retryCheck = await evaluate(`Boolean(document.querySelector('.episode-download-action svg.lucide-check'))`)
  if (retryCheck) throw new Error('Coche affichée pendant le réessai en file d’attente')

  await evaluate(`document.querySelector('.back-button')?.click()`)
  await waitFor(`Boolean([...document.querySelectorAll('.episode-info')].find((node) => node.textContent.includes('Épisode de test à réessayer')))`)
  await evaluate(`document.querySelector('.back-button')?.click()`)
  await waitFor(`Boolean(document.querySelector('[aria-label="Above & Beyond"]'))`)
  await evaluate(`document.querySelector('[aria-label="Above & Beyond"]').click()`)
  await waitFor(`Boolean([...document.querySelectorAll('.episode-info')].find((node) => node.textContent.includes('Find Your Harmony & Friends')))`)
  await evaluate(`[...document.querySelectorAll('.episode-info')].find((node) => node.textContent.includes('Find Your Harmony & Friends')).click()`)
  await waitFor(`Boolean([...document.querySelectorAll('.episode-track')].find((node) => node.textContent.includes('Mark Sherry, BiXX & Sue McLaren')))`)
  const entityTexts = await evaluate(`(() => ({
    detail: document.querySelector('.episode-detail')?.textContent,
    stored: JSON.parse(localStorage.getItem('podmix-catalog-v1') || '[]').find((source) => source.id === 'source-entities-release')
  }))()`)
  if (!entityTexts.detail?.includes('Believe & Receive') || !entityTexts.detail?.includes('David Forbes & Lostly') || entityTexts.detail.includes('&amp;')) {
    throw new Error(`Entités encore visibles dans la tracklist : ${JSON.stringify(entityTexts.detail)}`)
  }
  if (entityTexts.stored.id !== 'source-entities-release' || entityTexts.stored.feedUrl !== 'https://example.test/feed?a=1&amp;b=2' || entityTexts.stored.episodes[0].audioUrl !== 'http://10.0.2.2:8765/entities.wav?a=1&amp;b=2') {
    throw new Error('Une URL ou un identifiant a été modifié pendant la normalisation')
  }
  await screenshot('entities-tracklist.png')
  await evaluate(`[...document.querySelectorAll('.episode-track')].find((node) => node.textContent.includes('Believe & Receive')).click()`)
  await waitFor(`document.querySelector('.mini-player-global')?.textContent.includes('Mark Sherry, BiXX & Sue McLaren')`)
  await screenshot('entities-mini-player.png')
  await evaluate(`document.querySelector('.mini-meta').click()`)
  await waitFor(`document.querySelector('.full-player')?.textContent.includes('Believe & Receive')`)
  await screenshot('entities-full-player.png')

  console.log(JSON.stringify({ status, button: text, ariaLabel: label, navigationRestored: true, completedDetailCheck, completedDetailTrash, callsAfterDoubleClick, retryCalls, entityTextsVerified: true }))
} finally {
  socket.close()
}
