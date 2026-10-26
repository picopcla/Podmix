import { chromium } from '@playwright/test'
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
const dist = process.argv[2]
const label = process.argv[3]
const types = { '.html':'text/html', '.js':'text/javascript', '.css':'text/css', '.svg':'image/svg+xml', '.png':'image/png', '.json':'application/json', '.webmanifest':'application/json' }
const server = http.createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0]); if (p === '/') p = '/index.html'
  const f = path.join(dist, p)
  if (!f.startsWith(dist) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); res.end(); return }
  res.writeHead(200, { 'content-type': types[path.extname(f)] ?? 'application/octet-stream' }); fs.createReadStream(f).pipe(res)
}).listen(4180)
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const ctx = await browser.newContext({ viewport: { width: 368, height: 700 } })
const page = await ctx.newPage()
const errors = []
page.on('pageerror', (e) => errors.push(String(e).slice(0, 200)))
await page.goto('http://127.0.0.1:4180/')
await page.waitForTimeout(2000)
await page.evaluate(() => {
  const ep = (i) => ({ id: 'ep' + i, title: 'Episode test ' + i, description: 'd', publishedAt: new Date(Date.now() - i * 86400000).toISOString(), duration: '1:00:00', audioUrl: 'https://example.invalid/a' + i + '.mp3', artworkUrl: '' })
  localStorage.setItem('podmix-catalog-v1', JSON.stringify([{ id: 'src1', kind: 'podcast', title: 'Podcast test', description: '', artworkUrl: '', feedUrl: 'https://example.invalid/feed.xml', episodes: [ep(1), ep(2), ep(3)] }]))
})
await page.reload()
await page.waitForTimeout(2500)
// Remplit le stockage local de test jusqu'a saturation (contexte jetable, aucune donnee utilisateur)
const info = await page.evaluate(() => {
  let n = 0
  for (const size of [10_000, 1_000, 100, 10, 1]) { try { for (;;) { localStorage.setItem('zz-filler-' + n, 'x'.repeat(size)); n++ } } catch {} }
  let used = 0; for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); used += k.length + (localStorage.getItem(k) ?? '').length }
  // libere un tout petit espace pour que le 1er setItem de l'appli passe puis sature ensuite
  return { fillerKeys: n, charsUsed: used }
})
const snap = () => page.evaluate(() => { const o = {}; for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); if (!k.startsWith('zz-filler-')) o[k] = localStorage.getItem(k) } return JSON.stringify(o) })
const before = await snap()
console.log(label, 'filler', JSON.stringify(info))
await page.reload()
await page.waitForTimeout(6000)
await page.screenshot({ path: `qt/${label}-accueil.png` })
const card = page.getByText('Podcast test').first()
if (await card.count()) { await card.click().catch(() => {}); await page.waitForTimeout(3000) }
const body = (await page.evaluate(() => document.body.innerText)).slice(0, 300).replace(/\s+/g, ' ')
console.log(label, 'body:', body)
await page.getByRole('button', { name: 'Réglages' }).first().click().catch(() => {})
await page.waitForTimeout(1500)
const nbCb = await page.locator('input[type=checkbox]').count()
console.log(label, 'cases a cocher:', nbCb, 'vue:', (await page.evaluate(() => document.body.innerText)).slice(0, 120).replace(/\s+/g, ' '))
for (let i = 0; i < Math.min(nbCb, 4); i++) {
  if (await page.locator('.storage-warning').count()) break
  await page.evaluate(() => { const ks = Object.keys(localStorage).filter((k) => k.startsWith('zz-filler-')); let grown = true; while (grown) { grown = false; for (const k of ks) { try { localStorage.setItem(k, localStorage.getItem(k) + 'x'); grown = true } catch {} } } })
  await page.locator('input[type=checkbox]').nth(i).click({ force: true }).catch((e) => console.log('clic', String(e).slice(0, 80)))
  await page.waitForTimeout(1000)
  console.log(label, 'reglages stockes apres clic', i, await page.evaluate(() => localStorage.getItem('podmix-settings-v1')))
}
await page.evaluate(() => window.scrollTo(0, 0)); await page.screenshot({ path: `qt/${label}-reglages.png` })
const after = await snap()
const bk = Object.keys(JSON.parse(before)); const ak = JSON.parse(after)
console.log(label, 'donnees utilisateur preservees:', bk.every((k) => ak[k] === JSON.parse(before)[k]), 'cles avant', bk.length, 'apres', Object.keys(ak).length)
const banner = await page.locator('.storage-warning').first().innerText().catch(() => 'AUCUN')
console.log(label, 'bandeau:', banner.replace(/\s+/g,' '))
console.log(label, 'pageerrors:', errors.length, errors.slice(0, 2).join(' | '))
await page.screenshot({ path: `qt/${label}.png` })
await browser.close(); server.close()
