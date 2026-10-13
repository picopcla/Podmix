import { execFileSync } from 'node:child_process'
import fs from 'node:fs'

const [command, wanted] = process.argv.slice(2)
if (!['tap', 'assert'].includes(command) || !wanted) throw new Error('Usage: android-ui-text.mjs <tap|assert> <text>')
const dir = process.env.PODMIX_EMULATOR_CAPTURE_DIR ?? 'artifacts/emulator-captures'
fs.mkdirSync(dir, { recursive: true })
const image = `${dir}/ui-latest.png`
const normalize = (s) => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim()
function readLines() {
  fs.writeFileSync(image, execFileSync('adb', ['exec-out', 'screencap', '-p']))
  const tsv = execFileSync('tesseract', [image, 'stdout', '--psm', '11', 'tsv'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
  const lines = new Map()
  for (const row of tsv.trim().split('\n').slice(1)) {
    const a = row.split('\t')
    if (a[0] !== '5' || !a[11]?.trim()) continue
    const key = a.slice(1, 5).join(':')
    const words = lines.get(key) ?? []
    words.push({ text: a.slice(11).join('\t'), x: Number(a[6]), y: Number(a[7]), w: Number(a[8]), h: Number(a[9]) })
    lines.set(key, words)
  }
  return [...lines.values()].map(words => ({ text: words.map(w => w.text).join(' '), words }))
}
let match
let seen = []
for (let attempt = 0; attempt < 15 && !match; attempt++) {
  seen = readLines()
  const dialog = seen.some(line => /system ui.*responding/i.test(line.text))
  if (dialog && wanted !== 'Wait') {
    const wait = seen.find(line => normalize(line.text) === 'wait')
    if (!wait) throw new Error('System UI dialog has no visible Wait button')
    const word = wait.words[0]
    execFileSync('adb', ['shell', 'input', 'tap', String(Math.round(word.x + word.w / 2)), String(Math.round(word.y + word.h / 2))])
  } else {
    // Match the actual rendered text. Never enable debugging on the release APK.
    match = seen.find(line => normalize(line.text).includes(normalize(wanted)))
    if (match && /\bamp\b|&#\d+;|&amp;/i.test(match.text)) throw new Error(`Encoded entity remains visible: ${match.text}`)
  }
  if (!match) execFileSync('sleep', ['2'])
}
if (!match) throw new Error(`Rendered text not found: ${wanted}; visible=${JSON.stringify(seen.map(l => l.text))}`)
const left = Math.min(...match.words.map(w => w.x))
const top = Math.min(...match.words.map(w => w.y))
const right = Math.max(...match.words.map(w => w.x + w.w))
const bottom = Math.max(...match.words.map(w => w.y + w.h))
if (command === 'tap') execFileSync('adb', ['shell', 'input', 'tap', String(Math.round((left + right) / 2)), String(Math.round((top + bottom) / 2))])
console.log(JSON.stringify({ command, wanted, rendered: match.text, bounds: [left, top, right, bottom], method: 'release-screenshot-ocr' }))
