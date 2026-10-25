import { execSync, spawn } from 'node:child_process'
import fs from 'node:fs'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const PKG = 'com.podmix.next.ng'
const FEED = 'https://raw.githubusercontent.com/picopcla/Podmix/test/repro-assets/3-repro-feed.xml'
const SCENARIO = process.env.FLAP_SCENARIO ?? 'file'
if (!['file', 'http'].includes(SCENARIO)) throw new Error(`scenario inconnu: ${SCENARIO}`)
const out = { scenario: SCENARIO, phases: [], errors: [], diag: [], network: {}, flapdiag: { counts: {}, seedSeen: false, plays: [], scopeTransitions: [], uiTransitions: [] } }
const summaryPath = process.env.GITHUB_STEP_SUMMARY

function sh(cmd) { try { return execSync(cmd, { stdio: ['ignore','pipe','pipe'] }).toString() } catch { return '' } }
function raw(cmd) { try { execSync(cmd, { stdio: ['ignore','inherit','inherit'] }) } catch {} }
function logd(msg) { out.diag.push(msg); console.log('[diag] ' + msg) }

function uiDump() {
  for (let a = 0; a < 2; a++) {
    sh('adb shell uiautomator dump /sdcard/__ui.xml')
    const xml = sh('adb shell cat /sdcard/__ui.xml')
    if (xml.includes('<hierarchy')) return xml
  }
  return ''
}
function parseNodes(xml) {
  const list = []
  for (const m of xml.matchAll(/<node\b[^>]*>/g)) {
    const tag = m[0]
    const attr = (n) => { const mm = tag.match(new RegExp(n + '="([^"]*)"')); return mm ? mm[1].replace(/&quot;/g, '"').replace(/&amp;/g, '&') : '' }
    const b = attr('bounds').match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/)
    list.push({ text: attr('text'), desc: attr('content-desc'), cls: attr('class'), bounds: b ? { x1: +b[1], y1: +b[2], x2: +b[3], y2: +b[4] } : null })
  }
  return list
}
function tap(x, y) { sh(`adb shell input tap ${x} ${y}`) }
function tapNode(n) {
  if (!n || !n.bounds) return false
  tap(Math.round((n.bounds.x1 + n.bounds.x2) / 2), Math.round((n.bounds.y1 + n.bounds.y2) / 2))
  return true
}
function findByTextOrDesc(ns, value) {
  return ns.find((n) => n.desc === value) || ns.find((n) => n.text === value) || null
}
function findBestVisible(ns, value) {
  const cand = ns.filter((n) => (n.text === value || n.desc === value) && n.bounds && n.bounds.x2 > n.bounds.x1 && n.bounds.y2 > n.bounds.y1)
  cand.sort((a, b) => (b.bounds.x2 - b.bounds.x1) * (b.bounds.y2 - b.bounds.y1) - (a.bounds.x2 - a.bounds.x1) * (a.bounds.y2 - a.bounds.y1))
  return cand[0] || null
}
async function waitFor(label, pred, timeoutMs) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    const xml = uiDump()
    const ns = parseNodes(xml)
    const hit = pred(ns)
    if (hit) return { ns, hit }
    await sleep(1000)
  }
  throw new Error('timeout attente UI: ' + label)
}
function mediaSession() {
  const dump = sh('adb shell dumpsys media_session')
  const m = dump.match(/state=PlaybackState \{state=(\d+), position=(-?\d+)/)
  return m ? { state: +m[1], pos: +m[2] } : { state: null, pos: null }
}
function cap(path) { raw(`adb exec-out screencap -p > ${path}`) }

function screencapRaw() {
  try {
    const buf = execSync('adb exec-out screencap', { maxBuffer: 64 * 1024 * 1024 })
    if (buf.length < 16) return null
    const w = buf.readUInt32LE(0), h = buf.readUInt32LE(4), f = buf.readUInt32LE(8)
    if (w < 100 || w > 3000 || h < 100 || h > 4000) return null
    if (buf.length < 12 + w * h * 4) return null
    return { w, h, f, buf }
  } catch { return null }
}
function px(fr, x, y) {
  const o = 12 + (y * fr.w + x) * 4
  return [fr.buf[o], fr.buf[o + 1], fr.buf[o + 2]]
}
function isOrange([r, g, b]) { return r > 190 && g > 45 && g < 140 && b < 130 && r - g > 80 && r - b > 90 }
function isGreen([r, g, b]) { return g > 55 && g - r > 20 && g - b > 12 }
function detectButtons(fr) {
  const cell = 40, cols = Math.floor(fr.w / cell), rows = Math.floor(fr.h / cell)
  const hits = new Map()
  for (let cy = 0; cy < rows; cy++) for (let cx = 0; cx < cols; cx++) {
    let no = 0, ng = 0
    for (let y = cy * cell + 4; y < cy * cell + cell - 4; y += 4)
      for (let x = cx * cell + 4; x < cx * cell + cell - 4; x += 4) {
        const p = px(fr, x, y)
        if (isOrange(p)) no++; else if (isGreen(p)) ng++
      }
    if (no >= 20) hits.set(cx + ',' + cy, { cx, cy, n: no, kind: 'orange' })
    else if (ng >= 20) hits.set(cx + ',' + cy, { cx, cy, n: ng, kind: 'green' })
  }
  const cells = [...hits.values()]
  const used = new Set(), buttons = []
  for (const c of cells) {
    const k = c.cx + ',' + c.cy
    if (used.has(k)) continue
    let sx = 0, sy = 0, sn = 0, kind = c.kind
    for (const d of cells) {
      if (d.kind !== kind) continue
      if (Math.abs(d.cx - c.cx) <= 1 && Math.abs(d.cy - c.cy) <= 1) {
        used.add(d.cx + ',' + d.cy); sx += d.cx * d.n; sy += d.cy * d.n; sn += d.n
      }
    }
    if (sn > 0) buttons.push({ x: Math.round((sx / sn) * cell + cell / 2), y: Math.round((sy / sn) * cell + cell / 2), strength: sn, kind })
  }
  buttons.sort((a, b) => b.strength - a.strength)
  return buttons
}
function classifyBuf(buf, w, h, x, y) {
  let no = 0, ng = 0
  for (let dy = -6; dy <= 6; dy += 2) for (let dx = -6; dx <= 6; dx += 2) {
    const xx = Math.min(w - 1, Math.max(0, x + dx)), yy = Math.min(h - 1, Math.max(0, y + dy))
    const o = (yy * w + xx) * 4
    const p = [buf[o], buf[o + 1], buf[o + 2]]
    if (isOrange(p)) no++; else if (isGreen(p)) ng++
  }
  if (no > ng && no >= 6) return 'orange'
  if (ng > no && ng >= 6) return 'green'
  return 'autre'
}

// ---- logcat FLAPDIAG ----
let logproc = null
function startLogcat() {
  try {
    sh('adb logcat -c')
    const fd = fs.openSync('/tmp/logcat.txt', 'w')
    logproc = spawn('adb', ['logcat', '-v', 'threadtime'], { stdio: ['ignore', fd, fd] })
    logproc.on('error', (e) => logd('logcat spawn erreur: ' + e.message))
    logd('logcat lance (pid ' + logproc.pid + ')')
  } catch (e) { logd('startLogcat KO (mesure video+ms conservee): ' + (e && e.message || e)) }
}
function stopLogcat() {
  try { if (logproc) logproc.kill('SIGKILL') } catch {}
  logd('logcat stoppe')
}
function parseFlapDiag() {
  let lines = []
  try { lines = fs.readFileSync('/tmp/logcat.txt', 'utf8').split('\n') } catch { return [] }
  const events = []
  for (const ln of lines) {
    const i = ln.indexOf('FLAPDIAG {')
    if (i < 0) continue
    let body = ln.slice(i + 'FLAPDIAG '.length)
    const srcIdx = body.lastIndexOf('", source')
    if (srcIdx > 0) body = body.slice(0, srcIdx)
    const end = body.lastIndexOf('}')
    if (end < 0) continue
    try { events.push(JSON.parse(body.slice(0, end + 1))) } catch {}
  }
  return events
}
function summarizeFlapDiag(events) {
  const counts = {}
  for (const e of events) counts[e.event] = (counts[e.event] || 0) + 1
  out.flapdiag.counts = counts
  out.flapdiag.seedSeen = events.some((e) => e.event === 'seed' || e.event === 'seed-present')
  out.flapdiag.continuousConfirmed = events.some((e) => e.event === 'play' && e.fn === 'track' && e.episodeMode === true && (e.tracks ?? 0) > 1)
    || events.some((e) => (e.event === 'evt' || e.event === 'poll') && e.tqEp)
  out.flapdiag.sessions = [...new Set(events.map((e) => e.sid).filter(Boolean))].length
  out.flapdiag.plays = events.filter((e) => e.event === 'play')
  // transitions de scope sur les ecritures effectives (evt-write/poll-write)
  const writes = events.filter((e) => e.event === 'evt-write' || e.event === 'poll-write' || e.event === 'restoreFx')
  const transitions = []
  let prev = null
  for (const w of writes) {
    const key = w.id + '|' + w.scope
    if (prev && key !== prev.key) transitions.push({ t: w.t, event: w.event, from: prev.key, to: key, same: w.same })
    prev = { key }
  }
  out.flapdiag.scopeTransitions = transitions
  const uiWrites = events.filter((e) => e.event === 'ui-play-control')
  out.flapdiag.uiTransitions = uiWrites.filter((entry, index) => index === 0 || entry.component !== uiWrites[index - 1].component || entry.state !== uiWrites[index - 1].state || entry.scope !== uiWrites[index - 1].scope)
  out.flapdiag.catalogChanges = events.filter((e) => e.event === 'catalog-change')
  out.flapdiag.feedRefresh = events.filter((e) => e.event === 'feed-refresh')
  // echantillons representatifs
  out.flapdiag.sampleEvt = events.filter((e) => e.event === 'evt').slice(0, 6)
  out.flapdiag.samplePoll = events.filter((e) => e.event === 'poll').slice(0, 6)
  out.flapdiag.gpEvents = events.filter((e) => e.event === 'gp').length
  out.flapdiag.total = events.length
  fs.writeFileSync('artifacts/flapdiag.jsonl', events.map((e) => JSON.stringify(e)).join('\n') + '\n')
  logd(`FLAPDIAG: ${events.length} evenements, seed=${out.flapdiag.seedSeen}, transitions scope=${transitions.length}, compteurs=${JSON.stringify(counts)}`)
}

function startMsLog(tag) {
  fs.writeFileSync(`/tmp/mslog-${tag}.sh`, 'for i in $(seq 1 220); do printf "%s " "$(date +%s%3N)"; dumpsys media_session | grep -m1 "state=PlaybackState"; sleep 0.5; done > /sdcard/mslog-' + tag + '.txt 2>&1\n')
  sh(`adb push /tmp/mslog-${tag}.sh /sdcard/mslog-${tag}.sh`)
  sh(`adb shell "sh /sdcard/mslog-${tag}.sh &"`)
}
async function recordPhase(tag, seconds) {
  sh(`adb shell "rm -f /sdcard/flap-${tag}.mp4; screenrecord --time-limit ${seconds} /sdcard/flap-${tag}.mp4 > /dev/null 2>&1 &"`)
  const t0 = Date.now()
  let pid = ''
  while (Date.now() - t0 < 5000) {
    pid = sh('adb shell pidof screenrecord').trim()
    if (pid) break
    await sleep(250)
  }
  if (!pid) throw new Error(`screenrecord ${tag} non actif avant interaction`)
  logd(`screenrecord ${tag} confirme actif pid=${pid} (${seconds}s)`)
  // Pre-roll explicite : la capture doit etre active 1 a 2 s avant Lire/Reprendre.
  await sleep(1500)
}
async function pullPhase(tag) {
  for (let i = 0; i < 45; i++) {
    const pid = sh('adb shell pidof screenrecord').trim()
    if (!pid) break
    await sleep(2000)
  }
  sh(`adb pull /sdcard/flap-${tag}.mp4 /tmp/flap-${tag}.mp4`)
  sh(`adb pull /sdcard/mslog-${tag}.txt /tmp/mslog-${tag}.txt`)
  const videoPath = `/tmp/flap-${tag}.mp4`
  const okV = fs.existsSync(videoPath) && fs.statSync(videoPath).size > 10000
  if (!okV) throw new Error(`video ${tag} absente ou vide apres screenrecord`)
  const probeRaw = sh(`ffprobe -v error -count_frames -select_streams v:0 -show_entries stream=nb_read_frames,duration:format=duration -of json ${videoPath}`)
  let probe
  try { probe = JSON.parse(probeRaw) } catch { throw new Error(`ffprobe ${tag} illisible: ${probeRaw.slice(0, 300)}`) }
  const stream = probe.streams?.[0] ?? {}
  const duration = Number(probe.format?.duration ?? stream.duration)
  const frames = Number(stream.nb_read_frames)
  logd(`ffprobe ${tag}: duree=${duration}s, frames=${frames}, octets=${fs.statSync(videoPath).size}`)
  if (!Number.isFinite(duration) || duration < 70) throw new Error(`video ${tag} incomplete: duree=${duration}s (<70s)`)
  if (!Number.isInteger(frames) || frames < 100) throw new Error(`video ${tag} incomplete: frames=${frames} (<100)`)
  out.videoValidation ??= {}
  out.videoValidation[tag] = { duration, frames, bytes: fs.statSync(videoPath).size }
  return true
}
function parseMsLog(tag) {
  try {
    const lines = fs.readFileSync(`/tmp/mslog-${tag}.txt`, 'utf8').split('\n').filter(Boolean)
    const ev = []
    for (const ln of lines) {
      const m = ln.match(/^(\d+) .*state=PlaybackState \{state=(\d+), position=(-?\d+)/)
      if (m) ev.push({ t: +m[1], state: +m[2], pos: +m[3] })
    }
    return ev
  } catch { return [] }
}
function analyzeVideo(tag, anchors) {
  const fps = 10, W = 360, H = 640
  const r = sh(`ffmpeg -y -i /tmp/flap-${tag}.mp4 -vf fps=${fps},scale=${W}:${H} -f rawvideo -pix_fmt rgba /tmp/flap-${tag}.raw 2>&1`)
  if (!fs.existsSync(`/tmp/flap-${tag}.raw`)) { logd('ffmpeg raw KO: ' + r.slice(-200)); return null }
  const st = fs.statSync(`/tmp/flap-${tag}.raw`)
  const frameSize = W * 4 * H
  const nFrames = Math.floor(st.size / frameSize)
  logd(`video ${tag}: ${nFrames} frames a ${fps}fps`)
  const fd = fs.openSync(`/tmp/flap-${tag}.raw`, 'r')
  const frameBuf = Buffer.alloc(frameSize)
  const seqs = anchors.map(() => [])
  for (let i = 0; i < nFrames; i++) {
    fs.readSync(fd, frameBuf, 0, frameSize, i * frameSize)
    anchors.forEach((a, ai) => seqs[ai].push(classifyBuf(frameBuf, W, H, Math.round(a.x / 3), Math.round(a.y / 3))))
  }
  fs.closeSync(fd)
  const buttons = anchors.map((a, i) => {
    const seq = seqs[i].map((s) => s === 'orange' ? 'O' : s === 'green' ? 'V' : '.')
    const flipTimes = []
    for (let k = 1; k < seq.length; k++) if (seq[k] !== seq[k - 1] && seq[k] !== '.' && seq[k - 1] !== '.') flipTimes.push(+(k / fps).toFixed(1))
    return { x: a.x, y: a.y, flips: flipTimes.length, flipTimes: flipTimes.slice(0, 20), seq: seq.slice(0, 120).join('') }
  })
  const firstFlip = buttons.map((b) => b.flipTimes[0]).filter((t) => t != null).sort((a, b) => a - b)[0]
  if (firstFlip != null) {
    for (const dt of [-1.5, 0, 1.5]) {
      const tt = Math.max(0, firstFlip + dt)
      raw(`ffmpeg -y -ss ${tt} -i /tmp/flap-${tag}.mp4 -frames:v 1 artifacts/flip-${tag}-${dt < 0 ? 'avant' : dt === 0 ? 'pile' : 'apres'}.png`)
    }
  }
  return { nFrames, fps, buttons }
}
function msSummary(ev) {
  let trans = 0
  for (let k = 1; k < ev.length; k++) if (ev[k].state !== ev[k - 1].state) trans++
  const poss = ev.map((e) => e.pos).filter((p) => p != null && p >= 0)
  return { events: ev.length, transitions: trans, firstPos: poss[0] ?? null, lastPos: poss[poss.length - 1] ?? null, states: [...new Set(ev.map((e) => e.state))] }
}

function report() {
  const lines = ['# Diagnostic clignotement play/pause HORS LIGNE (build instrumente FLAPDIAG, observation pure)', '']
  lines.push(`- scenario source: ${out.scenario === 'file' ? 'file:// local telecharge' : 'HTTP distant demarre avant coupure'}`)
  lines.push(`## Reseau`)
  lines.push(`- mode avion demande: ${out.network.airplaneRequested}, settings airplane_mode_on=${out.network.airplaneSetting}, Wi-Fi=${JSON.stringify(out.network.wifi)}, donnees=${JSON.stringify(out.network.data)}, ping IPv4 rc=${out.network.pingRc}`)
  lines.push(`## FLAPDIAG (logcat)`)
  lines.push(`- evenements: ${out.flapdiag.total ?? 0} (${out.flapdiag.sessions ?? 0} sessions WebView), seed tracklist CONFIRME: ${out.flapdiag.seedSeen}, mode continu CONFIRME: ${out.flapdiag.continuousConfirmed}, compteurs: ${JSON.stringify(out.flapdiag.counts)}`)
  lines.push(`- entrees lecture: ${JSON.stringify(out.flapdiag.plays)}`)
  lines.push(`- transitions de scope entre ecritures nowPlaying: ${out.flapdiag.scopeTransitions?.length ?? 0}`)
  for (const tr of (out.flapdiag.scopeTransitions ?? []).slice(0, 40)) lines.push(`  - t=${tr.t} ${tr.event}: ${tr.from} -> ${tr.to} (same=${tr.same})`)
  lines.push(`- transitions UI: ${out.flapdiag.uiTransitions?.length ?? 0}`)
  for (const tr of (out.flapdiag.uiTransitions ?? []).slice(0, 40)) lines.push(`  - t=${tr.t} ${tr.component}: ${tr.state}, scope=${tr.scope}, globalPlaying=${tr.globalPlaying}`)
  lines.push(`- revisions catalogue: ${out.flapdiag.catalogChanges?.length ?? 0}; evenements refresh RSS: ${out.flapdiag.feedRefresh?.length ?? 0}`)
  for (const p of out.phases) {
    lines.push(`## Phase ${p.label}`)
    if (p.video) {
      lines.push(`- frames analysees: ${p.video.nFrames} a ${p.video.fps}fps`)
      for (const b of p.video.buttons) lines.push(`- bouton (${b.x},${b.y}) : ${b.flips} bascules, t=${JSON.stringify(b.flipTimes)}s, etats: ${b.seq} (O=orange V=vert .=autre)`)
    }
    if (p.ms) lines.push(`- media_session: ${p.ms.events} evt, ${p.ms.transitions} transitions, etats=${JSON.stringify(p.ms.states)}, pos ${p.ms.firstPos} -> ${p.ms.lastPos}`)
    if (p.note) lines.push(`- note: ${p.note}`)
    lines.push('')
  }
  if (out.errors.length) { lines.push('## Erreurs'); for (const e of out.errors) lines.push(`- ${e}`); lines.push('') }
  if (out.diag.length) { lines.push('## Diagnostic'); for (const d of out.diag) lines.push(`- ${d}`) }
  const text = lines.join('\n')
  console.log(text)
  if (summaryPath) fs.appendFileSync(summaryPath, text + '\n')
  fs.writeFileSync('artifacts/flap-report.json', JSON.stringify(out, null, 2))
}
function startApp() {
  const res = sh(`adb shell am start -n ${PKG}/com.podmix.next.MainActivity 2>&1`)
  logd('am start: ' + res.replace(/\n/g, ' ; ').slice(0, 200))
}

async function cutNetworkAfterImport() {
  const airplaneResult = sh('adb shell cmd connectivity airplane-mode enable').trim()
  sh('adb shell svc wifi disable')
  sh('adb shell svc data disable')
  await sleep(5000)
  out.network = {
    airplaneRequested: airplaneResult || 'commande envoyee',
    airplaneSetting: sh('adb shell settings get global airplane_mode_on').trim(),
    wifi: sh('adb shell cmd wifi status').trim().slice(0, 300),
    data: sh('adb shell dumpsys telephony.registry | grep -m1 -E "mDataConnectionState|mDataNetworkType"').trim().slice(0, 300),
    pingRc: sh("adb shell 'ping -c 1 -W 2 1.1.1 >/dev/null 2>&1; echo $?' ").trim(),
  }
  logd('reseau coupe apres import et telechargement: ' + JSON.stringify(out.network))
  if (out.network.airplaneSetting !== '1' || out.network.pingRc === '0') {
    throw new Error('preuve de mise hors ligne insuffisante: ' + JSON.stringify(out.network))
  }
}

async function waitForOfflineDownload(id, timeoutMs) {
  const prefsPath = `/data/user/0/${PKG}/shared_prefs/podmix_downloads.xml`
  const audioPath = `/sdcard/Android/data/${PKG}/files/Podcasts/podmix/${id}.offline`
  const t0 = Date.now()
  let lastPrefs = ''
  let lastBytes = 0
  while (Date.now() - t0 < timeoutMs) {
    lastPrefs = sh(`adb shell cat ${prefsPath}`)
    lastBytes = Number(sh(`adb shell stat -c %s ${audioPath}`).trim()) || 0
    const completed = lastPrefs.includes(`name="${id}.status">completed</string>`)
    if (completed && lastBytes > 1_000_000) {
      logd(`telechargement UI termine avant coupure reseau: id=${id}, octets=${lastBytes}, statut=completed`)
      return lastBytes
    }
    await sleep(2000)
  }
  throw new Error(`telechargement hors ligne non termine: octets=${lastBytes}, prefs=${lastPrefs.replace(/\s+/g, ' ').slice(0, 500)}`)
}

try {
  fs.mkdirSync('artifacts', { recursive: true })
  logd('ffmpeg: ' + sh('ffmpeg -version 2>/dev/null | head -1').trim())
  logd('wm size: ' + sh('adb shell wm size').trim())
  const probe = screencapRaw()
  logd('screencap raw: ' + (probe ? `${probe.w}x${probe.h} format=${probe.f}` : 'INDISPONIBLE'))
  if (!probe) throw new Error('screencap raw indisponible')

  startLogcat()
  startApp()
  await sleep(12000)

  // --- ajout de la source (methode v11-v13 validee) ---
  {
    const { hit } = await waitFor('bouton Ajouter une source', (ns) => findBestVisible(ns, 'Ajouter une source'), 30000)
    tapNode(hit)
  }
  await sleep(4000)
  sh('adb shell input keyevent KEYCODE_TAB')
  await sleep(1200)
  sh(`adb shell input text '${FEED}'`)
  await sleep(1000)
  sh('adb shell input keyevent KEYCODE_TAB')
  await sleep(1200)
  sh('adb shell input keyevent KEYCODE_ENTER')
  logd('import soumis via TAB+ENTER')
  await sleep(20000)
  sh(`adb shell am force-stop ${PKG}`)
  await sleep(2000)
  startApp()
  await sleep(12000)
  const { hit: carte } = await waitFor('carte source Repro Flap apres relance', (ns) => findByTextOrDesc(ns, 'Repro Flap'), 90000)
  logd('carte Repro Flap presente apres relance')
  cap('artifacts/accueil-peuple.png')

  // --- controle precoce : le tampon localStorage doit deja contenir seed/seed-present ---
  await sleep(4000)
  const early = extractLocalStore('preflight', false)
  const seedOk = early.found.some((e) => e.event === 'seed' || e.event === 'seed-present')
  logd(`preflight canal repli: seed=${seedOk} (${early.found.length} evenements)`)
  if (!seedOk) {
    // une chance de plus apres flush (force-stop + relance)
    sh(`adb shell am force-stop ${PKG}`)
    await sleep(2000)
    const early2 = extractLocalStore('preflight2', false)
    const seedOk2 = early2.found.some((e) => e.event === 'seed' || e.event === 'seed-present')
    logd(`preflight2 (apres flush): seed=${seedOk2} (${early2.found.length} evenements)`)
    if (!seedOk2) throw new Error('canal repli localStorage non fonctionnel : seed absent apres flush — arreter les relances')
    startApp()
    await sleep(12000)
    await waitFor('carte Repro Flap apres preflight2', (ns) => findByTextOrDesc(ns, 'Repro Flap'), 90000)
  }

  // --- vue podcast : deux chemins distincts vers la meme lecture continue ---
  // file: telechargement local puis lancement en mode avion.
  // http: lancement distant en ligne puis coupure reseau une fois MediaSession PLAYING.
  tapNode(carte)
  await sleep(5000)
  let fr = screencapRaw()
  if (!fr) throw new Error('capture indisponible avant scenario hors ligne')
  if (SCENARIO === 'file') {
    const downloadX = Math.round(fr.w * 0.81)
    const downloadY = Math.round(fr.h * 0.52)
    cap('artifacts/vue-podcast-avant-telechargement.png')
    tap(downloadX, downloadY)
    logd(`tap telechargement a (${downloadX},${downloadY})`)
    await waitForOfflineDownload('repro-ep-1', 180000)
    await sleep(5000)
    logd('scenario file://: episode telecharge avant coupure reseau')
    cap('artifacts/episode-disponible-hors-ligne.png')
    await cutNetworkAfterImport()
    cap('artifacts/vue-podcast-mode-avion-avant-lire.png')
  } else {
    logd('scenario HTTP: demarrage en ligne, coupure apres confirmation MediaSession PLAYING')
  }

  fr = screencapRaw()
  let btns = detectButtons(fr)
  logd('boutons detectes vue podcast: ' + JSON.stringify(btns.slice(0, 5)))
  cap('artifacts/vue-podcast-avant-lire.png')
  let lire = btns.find((b) => b.kind === 'orange')
  if (!lire) throw new Error('bouton Lire (orange) non detecte')
  const anchorsA = btns.filter((b) => b.kind === 'orange').slice(0, 4)
  startMsLog('A')
  await recordPhase('A', 75)
  tap(lire.x, lire.y)
  logd(`tap Lire a (${lire.x},${lire.y}), ancres video: ` + JSON.stringify(anchorsA))
  const t0 = Date.now()
  let playing = false
  while (Date.now() - t0 < 30000) {
    if (mediaSession().state === 3) { playing = true; break }
    await sleep(1000)
  }
  if (!playing) throw new Error('lecture jamais demarree')
  logd('media_session=playing confirme apres Lire (mode continu attendu via tracklist seedee)')
  if (SCENARIO === 'http') {
    await sleep(3000)
    await cutNetworkAfterImport()
    cap('artifacts/vue-podcast-mode-avion-http-en-lecture.png')
    logd('scenario HTTP: reseau coupe pendant la lecture')
  }
  await sleep(62000)
  const videoA = await pullPhase('A')
  const msA = msSummary(parseMsLog('A'))
  logd('media_session apres phase A: ' + JSON.stringify(mediaSession()))
  if (videoA) {
    const va = analyzeVideo('A', anchorsA)
    out.phases.push({ label: `${SCENARIO}-mode-avion-lecture-continue`, video: va, probe: out.videoValidation?.A, ms: msA })
  } else {
    out.phases.push({ label: `${SCENARIO}-mode-avion-lecture-continue`, ms: msA, note: 'screenrecord indisponible' })
  }
  cap('artifacts/phaseA-fin.png')

  // --- pause en place puis Reprendre (chemin exact de la video utilisateur) ---
  sh('adb shell input keyevent KEYCODE_MEDIA_PAUSE')
  await sleep(3000)
  logd('etat apres pause: ' + JSON.stringify(mediaSession()))
  cap('artifacts/apres-pause.png')
  fr = screencapRaw()
  btns = detectButtons(fr)
  const orangesB = btns.filter((b) => b.kind === 'orange')
  if (!orangesB.length) throw new Error('aucun bouton orange (Reprendre) apres pause')
  const reprendre = orangesB.reduce((a, b) => (b.y > a.y ? b : a)) // ligne episode
  const anchorsB = orangesB.slice(0, 4)
  logd('tap Reprendre a ' + JSON.stringify(reprendre))
  startMsLog('B')
  await recordPhase('B', 75)
  tap(reprendre.x, reprendre.y)
  const t1 = Date.now()
  let resumed = false
  while (Date.now() - t1 < 20000) {
    if (mediaSession().state === 3) { resumed = true; break }
    await sleep(1000)
  }
  if (!resumed) throw new Error('reprise jamais repartie (media_session)')
  logd('media_session=playing confirme apres Reprendre')
  await sleep(72000)
  const videoB = await pullPhase('B')
  const msB = msSummary(parseMsLog('B'))
  if (videoB) {
    const vb = analyzeVideo('B', anchorsB)
    out.phases.push({ label: `${SCENARIO}-mode-avion-apres-pause-reprendre`, video: vb, probe: out.videoValidation?.B, ms: msB })
  } else {
    out.phases.push({ label: `${SCENARIO}-mode-avion-apres-pause-reprendre`, ms: msB, note: 'screenrecord indisponible' })
  }
  cap('artifacts/phaseB-fin.png')
  logd('media_session final: ' + JSON.stringify(mediaSession()))
} catch (error) {
  out.errors.push(String(error && error.message || error))
  cap('artifacts/echec.png')
  try { fs.writeFileSync('artifacts/echec-ui.xml', uiDump() || 'dump vide') } catch {}
}
stopLogcat()
// --- extraction du tampon localStorage FLAPDIAG via adb root (canal de repli) ---
function extractLocalStore(tag, forceStop) {
  try {
    if (forceStop) { sh(`adb shell am force-stop ${PKG}`); sh('sleep 2') }
    sh('adb root')
    sh('sleep 2')
    sh('rm -rf /tmp/flapstore')
    sh("adb pull '/data/user/0/com.podmix.next.ng/app_webview/Default/Local Storage/leveldb' /tmp/flapstore")
    const files = fs.existsSync('/tmp/flapstore') ? fs.readdirSync('/tmp/flapstore').filter((f) => f.endsWith('.log') || f.endsWith('.ldb')) : []
    let blob = ''
    for (const f of files) blob += sh(`strings /tmp/flapstore/${f}`) + '\n'
    fs.writeFileSync(`artifacts/flapstore-raw-${tag}.txt`, blob)
    const found = []
    const seen = new Set()
    for (const ln of blob.split('\n')) {
      const i = ln.indexOf('FLAPDIAG {')
      if (i < 0) continue
      const rest = ln.slice(i)
      for (const part of rest.split('FLAPDIAG ')) {
        if (!part.startsWith('{')) continue
        const end = part.lastIndexOf('}')
        if (end < 0) continue
        const raw = part.slice(0, end + 1)
        if (seen.has(raw)) continue // doublons leveldb (versions successives du tampon)
        seen.add(raw)
        try { found.push(JSON.parse(raw)) } catch {}
      }
    }
    // controle chronologie : tri par horloge murale puis horloge session ; regroupe par sid
    found.sort((a, b) => (a.ts ?? 0) - (b.ts ?? 0) || (a.t ?? 0) - (b.t ?? 0))
    logd(`leveldb[${tag}]: ${files.length} fichiers, ${found.length} evenements uniques extraits`)
    return { found, files: files.length }
  } catch (e) {
    logd(`extraction localStorage[${tag}] KO: ` + (e && e.message || e))
    return { found: [], files: 0 }
  }
}
let events = parseFlapDiag()
const ls = extractLocalStore('fin', true)
if (ls.found.length) {
  logd(`canal localStorage: ${ls.found.length} evenements uniques (logcat en avait ${events.length})`)
  events = ls.found
}
try { fs.copyFileSync('/tmp/logcat.txt', 'artifacts/logcat.txt') } catch {}
summarizeFlapDiag(events)
report()
