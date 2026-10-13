import { execFileSync } from 'node:child_process'

const [command, wanted] = process.argv.slice(2)
if (!['tap', 'assert'].includes(command) || !wanted) {
  throw new Error('Usage: node scripts/android-ui-text.mjs <tap|assert> <texte>')
}

function dumpNodes() {
  execFileSync('adb', ['shell', 'uiautomator', 'dump', '/sdcard/podmix-window.xml'], { stdio: 'ignore' })
  const xml = execFileSync('adb', ['exec-out', 'cat', '/sdcard/podmix-window.xml'], { encoding: 'utf8' })
  return [...xml.matchAll(/<node\s+([^>]+)>?/g)].map((match) => {
    const attributes = Object.fromEntries([...match[1].matchAll(/([\w-]+)="([^"]*)"/g)].map((attribute) => [attribute[1], decodeXmlAttribute(attribute[2])]))
    return attributes
  })
}

function decodeXmlAttribute(value) {
  return value.replace(/&(?:#(\d+)|#x([\da-f]+)|(amp|apos|gt|lt|quot));/gi, (entity, decimal, hexadecimal, named) => {
    if (decimal || hexadecimal) {
      const codePoint = Number.parseInt(decimal ?? hexadecimal, decimal ? 10 : 16)
      return Number.isSafeInteger(codePoint) && codePoint <= 0x10ffff ? String.fromCodePoint(codePoint) : entity
    }
    return { amp: '&', apos: "'", gt: '>', lt: '<', quot: '"' }[named.toLowerCase()]
  })
}

let match
for (let attempt = 0; attempt < 30 && !match; attempt += 1) {
  match = dumpNodes().find((node) => `${node.text ?? ''} ${node['content-desc'] ?? ''}`.includes(wanted))
  if (!match) execFileSync('sleep', ['1'])
}
if (!match) throw new Error(`Texte Android introuvable : ${wanted}`)

if (command === 'tap') {
  const bounds = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(match.bounds ?? '')
  if (!bounds) throw new Error(`Bornes Android invalides pour : ${wanted}`)
  const x = Math.round((Number(bounds[1]) + Number(bounds[3])) / 2)
  const y = Math.round((Number(bounds[2]) + Number(bounds[4])) / 2)
  execFileSync('adb', ['shell', 'input', 'tap', String(x), String(y)])
}

console.log(JSON.stringify({ command, wanted, bounds: match.bounds }))
