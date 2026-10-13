import fs from 'node:fs/promises'

const endpoint = process.env.PODMIX_EMULATOR_CDP ?? 'http://127.0.0.1:9222'
const outputDir = process.env.PODMIX_EMULATOR_CAPTURE_DIR ?? 'artifacts/emulator-captures'
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
  const capture = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
  await fs.writeFile(`${outputDir}/${name}`, Buffer.from(capture.data, 'base64'))
}

async function installNativeFixtureBootstrap() {
  await send('Page.addScriptToEvaluateOnNewDocument', { source: `(() => {
    let capacitor
    Object.defineProperty(window, 'Capacitor', {
      configurable: true,
      get: () => capacitor,
      set: (value) => {
        capacitor = value
        const originalNativePromise = value.nativePromise.bind(value)
        value.nativePromise = (plugin, method, options = {}) => {
          if (plugin !== 'PodmixPlayer') return originalNativePromise(plugin, method, options)
          const fixture = JSON.parse(localStorage.getItem('podmix-emulator-download-fixture-v1') || 'null')
          if (!fixture) return originalNativePromise(plugin, method, options)
          if (method === 'download') {
            const calls = Number(localStorage.getItem('podmix-emulator-download-calls-v1') || '0') + 1
            localStorage.setItem('podmix-emulator-download-calls-v1', String(calls))
            return Promise.resolve({ ...fixture, id: options.id ?? fixture.id })
          }
          if (method === 'getDownload') return Promise.resolve({ ...fixture, id: options.id ?? fixture.id })
          if (method === 'listDownloads') return Promise.resolve({ downloads: [] })
          if (method === 'removeDownload') return Promise.resolve({ id: options.id, status: 'not_found', removed: true })
          return originalNativePromise(plugin, method, options)
        }
      },
    })
  })()` })
}

async function reloadWithOffline(episodes, fixture) {
  await evaluate(`localStorage.setItem('podmix-offline-v1', ${JSON.stringify(JSON.stringify(episodes))}); localStorage.setItem('podmix-emulator-download-fixture-v1', ${JSON.stringify(JSON.stringify(fixture))}); setTimeout(() => location.reload(), 1000); true`)
  await waitFor(`Boolean(document.querySelector('[aria-label="Émission factice"]'))`, 120_000)
}

async function openEpisode(title) {
  await evaluate(`document.querySelector('[aria-label="Émission factice"]').click()`)
  await waitFor(`Boolean([...document.querySelectorAll('.episode-info')].find((node) => node.textContent.includes(${JSON.stringify(title)})))`, 120_000)
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
    localStorage.setItem('podmix-catalog-v1', JSON.stringify([{
      id: 'source-emulator-test', kind: 'show', title: 'Émission factice', description: '', artworkUrl: '',
      episodes: [{
        id: 'episode-emulator-test', title: 'Épisode de test hors connexion',
        description: 'Donnée factice destinée au test émulateur.', publishedAt: '2026-10-13',
        duration: '10:00', audioUrl: 'https://example.test/slow.mp3', artworkUrl: ''
      }, {
        id: 'episode-emulator-retry', title: 'Épisode de test à réessayer',
        description: 'Donnée factice destinée au test émulateur.', publishedAt: '2026-10-12',
        duration: '08:00', audioUrl: 'https://example.test/retry.mp3', artworkUrl: ''
      }]
    }]))
    setTimeout(() => location.reload(), 1000)
    return true
  })()`)

  await waitFor(`Boolean(document.querySelector('[aria-label="Émission factice"]'))`)
  await openEpisode('Épisode de test hors connexion')

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

  const completedEpisode = {
    id: 'episode-emulator-test', title: 'Épisode de test hors connexion', artist: 'Émission factice',
    remoteUrl: 'https://example.test/slow.mp3', status: 'completed', bytesDownloaded: 100,
    totalBytes: 100, localUri: 'file:///data/factice.mp3', addedAt: '2026-10-13T00:00:00.000Z',
  }
  const progressEpisode = { ...completedEpisode, status: 'downloading', bytesDownloaded: 45, localUri: undefined }
  await reloadWithOffline([progressEpisode], progressEpisode)
  await openEpisode('Épisode de test hors connexion')
  await waitFor(`document.querySelector('.episode-download-action')?.textContent.includes('45%')`, 120_000)
  const progressCheck = await evaluate(`Boolean(document.querySelector('.episode-download-action svg.lucide-check'))`)
  if (progressCheck) throw new Error('Coche affichée pendant la progression')
  await screenshot('detail-download-progress.png')

  await reloadWithOffline([completedEpisode], completedEpisode)
  await openEpisode('Épisode de test hors connexion')
  await waitFor(`document.querySelector('.episode-download-action')?.getAttribute('aria-label') === 'Téléchargé · disponible hors connexion'`, 120_000)
  const completedDetailCheck = await evaluate(`Boolean(document.querySelector('.episode-download-action svg.lucide-check'))`)
  const completedDetailTrash = await evaluate(`Boolean(document.querySelector('.episode-download-remove[aria-label="Supprimer le téléchargement"]'))`)
  const completedDetailDisabled = await evaluate(`document.querySelector('.episode-download-action')?.disabled === true`)
  if (!completedDetailCheck || !completedDetailTrash || !completedDetailDisabled) {
    throw new Error('État terminé incomplet dans la fiche')
  }
  await screenshot('detail-download-completed.png')

  await evaluate(`document.querySelector('.back-button').click()`)
  await waitFor(`Boolean([...document.querySelectorAll('.episode-info')].find((node) => node.textContent.includes('Épisode de test hors connexion')))`)
  await waitFor(`Boolean([...document.querySelectorAll('.episode-item')].find((node) => node.textContent.includes('Épisode de test hors connexion'))?.querySelector('.episode-download.completed svg.lucide-check'))`)
  const completedRowActions = await evaluate(`(() => {
    const row = [...document.querySelectorAll('.episode-item')].find((node) => node.textContent.includes('Épisode de test hors connexion'))
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

  const failedEpisode = {
    id: 'episode-emulator-retry', title: 'Épisode de test à réessayer', artist: 'Émission factice',
    remoteUrl: 'https://example.test/retry.mp3', status: 'failed', bytesDownloaded: 0,
    totalBytes: 100, addedAt: '2026-10-13T00:00:00.000Z',
  }
  await reloadWithOffline([completedEpisode, failedEpisode], failedEpisode)
  await openEpisode('Épisode de test à réessayer')
  await waitFor(`document.querySelector('.episode-download-action')?.classList.contains('failed')`, 120_000)
  await screenshot('detail-download-failed.png')
  await evaluate(`localStorage.setItem('podmix-emulator-download-fixture-v1', JSON.stringify({ id: 'episode-emulator-retry', status: 'queued', bytesDownloaded: 0, totalBytes: 100 })); localStorage.setItem('podmix-emulator-download-calls-v1', '0')`)
  await evaluate(`document.querySelector('.episode-download-action').click(); document.querySelector('.episode-download-action').click()`)
  await waitFor(`document.querySelector('.episode-download-action')?.classList.contains('queued')`, 120_000)
  const retryCalls = await evaluate(`Number(localStorage.getItem('podmix-emulator-download-calls-v1'))`)
  if (retryCalls !== 1) throw new Error(`Double requête native au réessai : ${retryCalls}`)
  const retryCheck = await evaluate(`Boolean(document.querySelector('.episode-download-action svg.lucide-check'))`)
  if (retryCheck) throw new Error('Coche affichée pendant le réessai en file d’attente')

  console.log(JSON.stringify({ status, button: text, ariaLabel: label, navigationRestored: true, completedDetailCheck, completedDetailTrash, callsAfterDoubleClick, retryCalls }))
} finally {
  socket.close()
}
