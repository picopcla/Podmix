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
  const { resolve, reject } = pending.get(message.id)
  pending.delete(message.id)
  if (message.error) reject(new Error(message.error.message))
  else resolve(message.result)
})

function send(method, params = {}) {
  const id = ++requestId
  socket.send(JSON.stringify({ id, method, params }))
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject }))
}

async function evaluate(expression) {
  const response = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (response.exceptionDetails) throw new Error(response.exceptionDetails.text)
  return response.result?.value
}

async function waitFor(expression, timeoutMs = 15_000) {
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
  const result = await send('Page.captureScreenshot', { format: 'png', fromSurface: true })
  await fs.writeFile(`${outputDir}/${name}`, Buffer.from(result.data, 'base64'))
}

try {
  await send('Page.enable')
  await send('Runtime.enable')
  await evaluate(`(() => {
    localStorage.clear()
    localStorage.setItem('podmix-catalog-v1', JSON.stringify([{
      id: 'source-emulator-test', kind: 'show', title: 'Émission factice', description: '', artworkUrl: '',
      episodes: [{
        id: 'episode-emulator-test', title: 'Épisode de test hors connexion',
        description: 'Donnée factice destinée au test émulateur.', publishedAt: '2026-10-13',
        duration: '10:00', audioUrl: 'https://example.test/podmix-factice.mp3', artworkUrl: ''
      }]
    }]))
    location.reload()
  })()`)

  await waitFor(`Boolean(document.querySelector('[aria-label="Émission factice"]'))`)
  await evaluate(`document.querySelector('[aria-label="Émission factice"]').click()`)
  await waitFor(`Boolean([...document.querySelectorAll('.episode-info')].find((node) => node.textContent.includes('Épisode de test hors connexion')))`)
  await evaluate(`[...document.querySelectorAll('.episode-info')].find((node) => node.textContent.includes('Épisode de test hors connexion')).click()`)
  await waitFor(`Boolean(document.querySelector('.episode-download-action'))`)

  const initialLabel = await evaluate(`document.querySelector('.episode-download-action').getAttribute('aria-label')`)
  if (!/Télécharger pour écouter hors connexion/.test(initialLabel)) throw new Error(`État initial inattendu : ${initialLabel}`)
  await screenshot('download-before.png')

  await evaluate(`document.querySelector('.episode-download-action').click()`)
  await waitFor(`Boolean(document.querySelector('.episode-download-feedback')?.textContent.trim())`)
  const status = await evaluate(`document.querySelector('.episode-download-feedback').textContent.trim()`)
  if (!/Demande envoyée|Demande reçue|Téléchargement démarré/.test(status)) throw new Error(`Statut inattendu après le clic : ${status}`)
  await screenshot('download-after.png')

  const label = await evaluate(`document.querySelector('.episode-download-action').getAttribute('aria-label')`)
  const text = await evaluate(`document.querySelector('.episode-download-action').textContent.trim()`)
  if (!label || !text || text === 'Hors connexion') throw new Error(`Bouton non actualisé : label=${label} texte=${text}`)

  await evaluate(`document.querySelector('.back-button').click()`)
  await waitFor(`Boolean([...document.querySelectorAll('.episode-info')].find((node) => node.textContent.includes('Épisode de test hors connexion')))`)
  await evaluate(`[...document.querySelectorAll('.episode-info')].find((node) => node.textContent.includes('Épisode de test hors connexion')).click()`)
  await waitFor(`Boolean(document.querySelector('.episode-download-feedback')?.textContent.trim())`)

  console.log(JSON.stringify({ status, button: text, ariaLabel: label, navigationRestored: true }))
} finally {
  socket.close()
}
