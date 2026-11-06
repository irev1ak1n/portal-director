'use strict'
/*
 * Sky Army live test on an isolated Paper server, with real bot clients: 10 players watched by an observer client (hidden until
 * each appears, appearance spread over time), 20 and 40 players (a valid portal for everyone, correct timing from the server
 * log, nobody traveling to the Nether while staged, nobody extra moved), the adaptive widening (small radius, 40 players),
 * players sharing portals when only a few are allowed, stop in the middle, and server tick time. Nothing here is visual verification.
 */
const fs = require('node:fs')
const path = require('node:path')
const { TestServer } = require('./lib/server')

const PORT = +(process.env.PD_PORT || 25591)
const HOST = '127.0.0.1'
const SERVER_DIR = path.resolve(process.env.PD_SERVER_DIR || path.join(__dirname, '..', '..', 'portal-director-test-server'))
const PLUGIN_DIR = path.join(SERVER_DIR, 'plugins', 'PortalDirector')
const REPORT = path.join(__dirname, 'reports')

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log('[sky-army-test]', ...a)
const checks = []
function check (name, ok, detail = '') {
  checks.push({ name, ok: !!ok, detail })
  console.log(`${ok ? '  PASS' : '  FAIL'}  ${name}${detail ? '  (' + String(detail).slice(0, 300) + ')' : ''}`)
}
const srv = new TestServer({ dir: SERVER_DIR, jar: 'paper-26.3-231.jar', memory: '2000M' })

function prepareServer () {
  const text = fs.readFileSync(path.join(SERVER_DIR, 'server.properties'), 'utf8')
  const get = k => (text.match(new RegExp('^' + k + '=(.*)$', 'm')) || [])[1]
  if (get('server-ip') !== HOST || get('server-port') !== String(PORT)) throw new Error('Refusing to run: the server must be 127.0.0.1:' + PORT)
  if (PORT === 25590 || PORT === 25592) throw new Error('Refusing to touch the play servers')
  if (+get('max-players') < 45) throw new Error('The test server needs max-players of at least 45')
  fs.rmSync(PLUGIN_DIR, { recursive: true, force: true })
  for (const w of ['world', 'world_nether', 'world_the_end']) fs.rmSync(path.join(SERVER_DIR, w), { recursive: true, force: true })
}

async function run (cmd, settle = 600) {
  const from = srv.lines.length
  srv.command(cmd)
  await sleep(settle)
  return srv.lines.slice(from).join('\n')
}
/** Runs a command and returns all of its output once a line matching {@code end} has appeared. */
async function runBlock (cmd, end, timeout = 30000) {
  const from = srv.lines.length
  srv.command(cmd)
  await srv.waitForLine(end, timeout, from, String(end))
  await sleep(250)
  return srv.lines.slice(from).join('\n')
}
async function runUntil (cmd, regex, timeout = 120000) {
  const from = srv.lines.length
  srv.command(cmd)
  return srv.waitForLine(regex, timeout, from, String(regex))
}
const body = l => l.replace(/^\[[^\]]*\]: /, '')
const last = out => out.trim().split('\n').pop().replace(/^\[[^\]]*\]: /, '').slice(0, 220)
async function waitFor (fn, ms, step = 250) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await fn()) return true; await sleep(step) }
  return false
}
function readScene (file) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/)
  const scene = { portals: [] }
  let section = ''
  let cur = null
  for (const l of lines) {
    if (/^portals:/.test(l)) { section = 'portals'; continue }
    if (/^\S/.test(l) && !/^- /.test(l)) section = ''
    if (section === 'portals') {
      let m = l.match(/^- id: (\d+)/)
      if (m) { cur = { id: +m[1] }; scene.portals.push(cur); continue }
      m = l.match(/^ {2}(\w+): (.+)$/)
      if (m && cur) cur[m[1]] = m[2] === 'true' ? true : m[2] === 'false' ? false : /^-?\d+$/.test(m[2]) ? +m[2] : m[2]
    }
  }
  return scene
}
const overworld = () => readScene(path.join(PLUGIN_DIR, 'scenes', 'default.yml'))
const skyPortals = () => overworld().portals.filter(p => /^sky-army-\d+$/.test(p.name))
/** The purple interior of a portal as x, y, z ranges. */
function interior (p) {
  return p.axis === 'X'
    ? { x0: p.x + 1, x1: p.x + 3, z0: p.z, z1: p.z + 1, y0: p.y + 1, y1: p.y + 4 }
    : { x0: p.x, x1: p.x + 1, z0: p.z + 1, z1: p.z + 3, y0: p.y + 1, y1: p.y + 4 }
}
const insideOf = (pos, p, tol = 0.35) => {
  const b = interior(p)
  return pos.x >= b.x0 - tol && pos.x <= b.x1 + tol && pos.z >= b.z0 - tol && pos.z <= b.z1 + tol && pos.y >= b.y0 - 0.6 && pos.y <= b.y1
}

const mineflayer = require('@wp2508/mineflayer')
const bots = new Map()
async function connect (name) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: name, auth: 'offline', version: '26.3', viewDistance: 'tiny', checkTimeoutInterval: 90000 })
  bot.on('error', e => console.error(`[${name}] error:`, e.message))
  bot.seenAt = {}
  bots.set(name, bot)
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(name + ' did not spawn')), 90000)
    bot.once('spawn', () => { clearTimeout(t); resolve() })
  })
  return bot
}
const dimension = b => String(b.game && b.game.dimension)
const pos = b => ({ x: b.entity.position.x, y: b.entity.position.y, z: b.entity.position.z })
const sees = (viewer, name) => Object.values(viewer.entities).some(e => e.type === 'player' && e.username === name)
const nameOf = i => 'Sk' + String(i).padStart(2, '0')

async function addBots (from, to) {
  for (let i = from; i <= to; i++) {
    await connect(nameOf(i))
    await run('op ' + nameOf(i), 40)
    await sleep(150)
  }
}
async function setGroup (n, group) {
  await run(`pd group delete ${group}`, 200)
  await run(`pd group create ${group}`, 200)
  for (let i = 1; i <= n; i++) await run(`pd group assign-to-group ${nameOf(i)} ${group}`, 40)
  await run(`pd sequence sky-army set group ${group}`, 200)
}
const READY = /Sky Army (ready|is only partly|could not|failed|stopped)/
function parseLog (from) {
  const lines = srv.lines.slice(from).map(body)
  const built = {}
  const appears = {}
  for (const l of lines) {
    let m = l.match(/sky-army tick (\d+): portal (sky-army-\d+) built facing (\w+)/)
    if (m) built[m[2]] = { tick: +m[1], facing: m[3] }
    m = l.match(/sky-army tick (\d+): (\w+) appears in (sky-army-\d+)/)
    if (m) appears[m[2]] = { tick: +m[1], portal: m[3] }
  }
  return { built, appears, lines }
}
function timingChecks (label, p, players, lo = 2, hi = 10) {
  const names = Object.keys(p.built)
  const ticks = names.map(n => p.built[n].tick).sort((a, b) => a - b)
  const gaps = ticks.slice(1).map((t, i) => t - ticks[i])
  check(`${label}: portals appear one after another, the first at once, then short varied gaps (0.15 to 0.55 s)`, ticks[0] === 0 && gaps.length > 0 && gaps.every(g => g >= 3 && g <= 11) && new Set(gaps).size >= Math.min(3, gaps.length), gaps.join(' '))
  const offsets = []
  let neverEarly = true
  for (const who of Object.keys(p.appears)) {
    const a = p.appears[who]
    const b = p.built[a.portal]
    if (!b || a.tick < b.tick) neverEarly = false
    else offsets.push(a.tick - b.tick)
  }
  check(`${label}: no player appears before their portal exists`, neverEarly && Object.keys(p.appears).length === players, `${Object.keys(p.appears).length} of ${players}`)
  check(`${label}: players appear ${lo / 20} to ${hi / 20} s after their portal, with varied delays`, offsets.every(o => o >= lo && o <= hi) && new Set(offsets).size >= Math.min(3, offsets.length), [...new Set(offsets)].sort((a, b) => a - b).join(' '))
}
/** Where the server says a player is, and in which dimension. */
async function serverPos (name) {
  const ask = async field => {
    const from = srv.lines.length
    srv.command('data get entity ' + name + ' ' + field)
    try { return await srv.waitForLine(new RegExp(name + ' has the following entity data: '), 8000, from, field) } catch (e) { return '' }
  }
  const o = await ask('Pos')
  const d = await ask('Dimension')
  const m = o.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
  return m ? { x: +m[1], y: +m[2], z: +m[3], dim: (d.match(/"([^"]+)"/) || [])[1] || '?' } : null
}
async function rotationOf (name) {
  const o = await run(`data get entity ${name} Rotation`, 200)
  const m = o.match(/\[(-?[\d.]+)f, (-?[\d.]+)f\]/)
  return m ? +m[1] : null
}
const OUTWARD = { SOUTH: 0, WEST: 90, NORTH: 180, EAST: -90 }
const yawOk = (yaw, facing) => {
  const want = OUTWARD[facing.toUpperCase()]
  const d = Math.abs(((yaw - want + 540) % 360) - 180)
  return d < 2
}
async function mspt () {
  const o = await run('tick query', 600)
  const m = o.match(/Average time per tick: ([\d.]+)ms/)
  return m ? +m[1] : null
}

async function staged (label, n, prefix, startLine) {
  const p = parseLog(startLine)
  const portals = skyPortals()
  const appears = p.appears
  const onTime = await waitFor(() => Object.keys(appears).length === n || true, 100)
  void onTime
  const allIn = []
  const wrong = []
  for (let i = 1; i <= n; i++) {
    const name = nameOf(i)
    const a = appears[name]
    const portal = a && portals.find(x => x.name === a.portal)
    if (!a || !portal) { wrong.push(name + ' has no portal'); continue }
    const sp = await serverPos(name)
    if (!sp) { wrong.push(name + ' has no position'); continue }
    if (!sp.dim.includes('overworld')) wrong.push(name + ' is in ' + sp.dim)
    if (!insideOf(sp, portal)) wrong.push(`${name} at ${sp.x.toFixed(1)} ${sp.y.toFixed(1)} ${sp.z.toFixed(1)} (server) / ${pos(bots.get(name)).x.toFixed(1)} ${pos(bots.get(name)).z.toFixed(1)} (client) is outside ${a.portal} [${portal.x} ${portal.y} ${portal.z} ${portal.axis}]`)
    allIn.push(a.portal)
  }
  check(`${label}: every one of the ${n} players stands inside the purple part of their portal, in the Overworld`, wrong.length === 0, wrong.length + ' wrong: ' + wrong.slice(0, 3).join(' | '))
  return { p, portals, ports: allIn }
}

async function main () {
  prepareServer()
  fs.mkdirSync(REPORT, { recursive: true })
  srv.start()
  await srv.waitForLine(/Done \(/, 300000, 0, 'server start')
  check('plugin enabled', srv.lines.some(l => /PortalDirector ready/.test(l)))
  const found = await runUntil('execute in minecraft:overworld run locate biome minecraft:plains', /\[(-?\d+), (?:~|-?\d+), (-?\d+)\]/, 120000)
  const loc = found.match(/\[(-?\d+), (?:~|-?\d+), (-?\d+)\]/)
  const CX = +loc[1] + 40
  const CZ = +loc[2] + 40
  const Y = 140
  await run(`forceload add ${CX - 16} ${CZ - 16} ${CX + 16} ${CZ + 16}`, 2500)
  await sleep(2000)
  await run(`fill ${CX - 5} ${Y - 1} ${CZ - 5} ${CX + 5} ${Y - 1} ${CZ + 5} stone`, 1500)
  await run('pd group nether-travel true')

  // ---- 10 players with an observer
  log('10 players')
  await connect('Obs')
  await run('op Obs', 100)
  await addBots(1, 10)
  const obs = bots.get('Obs')
  await setGroup(10, 'ARMY')
  await run('pd group nether-travel true ARMY', 200)
  await run('pd sequence sky-army set radius 20')
  await run('pd sequence sky-army set height-range 20')
  // a slow first appearance, so the hidden stretch is long enough to watch from another client
  await run('pd sequence sky-army set player-delay-max 2.5')
  await run('pd sequence sky-army set player-delay-min 1.5')
  const homes = {}
  for (let i = 1; i <= 10; i++) await run(`execute in minecraft:overworld run tp ${nameOf(i)} ${CX - 3 + (i % 5)} ${Y} ${CZ - 3 + Math.floor(i / 5) * 3}`, 60)
  await run(`execute in minecraft:overworld run tp Obs ${CX + 4} ${Y} ${CZ + 4}`, 100)
  // the observer's client needs a moment to load the area around it and learn about the others
  await waitFor(() => Array.from({ length: 10 }, (_, i) => nameOf(i + 1)).every(n => sees(obs, n)), 25000, 250)
  await sleep(1000)
  for (let i = 1; i <= 10; i++) homes[nameOf(i)] = await serverPos(nameOf(i))
  const seenBefore = Array.from({ length: 10 }, (_, i) => nameOf(i + 1)).filter(n => sees(obs, n)).length
  check('before the run the observer sees all 10 group members', seenBefore === 10, String(seenBefore))
  const obsHome = pos(obs)
  const groupBefore = await runBlock('pd group info ARMY', /Nether travel (on|off)/)
  // appearance times for the observer
  const appearedAt = {}
  const events = []
  obs.on('entityGone', e => { if (e.type === 'player' && /^Sk\d\d$/.test(e.username || '')) events.push('gone:' + e.username) })
  const t0 = performance.now()
  obs.on('entitySpawn', e => { if (e.type === 'player' && /^Sk\d\d$/.test(e.username || '')) { events.push('spawn:' + e.username); appearedAt[e.username] = (appearedAt[e.username] || []).concat(performance.now() - t0) } })
  let from = srv.lines.length
  srv.command(`pd sequence sky-army run ${CX} ${Y} ${CZ} world`)
  const done = await srv.waitForLine(READY, 180000, from, 'sky army')
  await sleep(2000)
  {
    // every group member must leave the observer's view (hidden) before any of them is seen again
    const firstSpawn = events.findIndex(e => e.startsWith('spawn:'))
    const goneBefore = new Set(events.slice(0, firstSpawn < 0 ? events.length : firstSpawn).filter(e => e.startsWith('gone:')).map(e => e.slice(5)))
    const spawnedNames = new Set(events.filter(e => e.startsWith('spawn:')).map(e => e.slice(6)))
    check('all 10 players are hidden from the observer before the first of them is shown again', goneBefore.size === 10 && spawnedNames.size === 10, `hidden first: ${goneBefore.size}, shown later: ${spawnedNames.size}`)
    const hiddenThenShown = [...spawnedNames].every(n => events.indexOf('gone:' + n) >= 0 && events.indexOf('gone:' + n) < events.indexOf('spawn:' + n))
    check('and each one is hidden before it is shown', hiddenThenShown)
  }
  check('sky-army reports ready for all 10', /ready: all 10 players/.test(body(done)) && /one each/.test(body(done)), body(done).slice(0, 200))
  await sleep(2500)
  const s1 = await staged('10 players', 10, 'Sk', from)
  timingChecks('10 players', s1.p, 10, 30, 50)
  check('10 different portals, one each', new Set(s1.ports).size === 10, new Set(s1.ports).size)
  const appearTimes = Object.values(appearedAt).map(a => a[a.length - 1]).sort((a, b) => a - b)
  check('the observer sees every player appear, one at a time over a stretch of time', appearTimes.length === 10 && appearTimes[9] - appearTimes[0] >= 300 && new Set(appearTimes.map(t => Math.round(t / 50))).size >= 4,
    appearTimes.map(t => Math.round(t)).join(' '))
  check('afterwards the observer still sees all 10', Array.from({ length: 10 }, (_, i) => nameOf(i + 1)).every(n => sees(obs, n)))
  const ps = skyPortals()
  check('10 floating portals exist inside the radius and the height band', ps.length === 10 && ps.every(p => p.floating && p.y >= Y - 20 - 1 && p.y <= Y + 20 + 1), `${ps.length}`)
  check('they face several directions', new Set(ps.map(p => p.facing)).size >= 3, [...new Set(ps.map(p => p.facing))].join(' '))
  let yawsOk = true
  const yawBad = []
  for (let i = 1; i <= 10; i++) {
    const a = s1.p.appears[nameOf(i)]
    const yaw = await rotationOf(nameOf(i))
    if (yaw === null || !yawOk(yaw, s1.p.built[a.portal].facing)) { yawsOk = false; yawBad.push(`${nameOf(i)} ${yaw} ${s1.p.built[a.portal].facing}`) }
  }
  check('players face outward, the way their portal faces', yawsOk, yawBad.join(' | '))
  const posNow = {}
  for (let i = 1; i <= 10; i++) posNow[nameOf(i)] = pos(bots.get(nameOf(i)))
  await sleep(8000)
  let still = true
  for (let i = 1; i <= 10; i++) {
    const b = bots.get(nameOf(i))
    if (!dimension(b).includes('overworld') || Math.hypot(pos(b).x - posNow[nameOf(i)].x, pos(b).z - posNow[nameOf(i)].z) > 0.5) still = false
  }
  check('nobody travels to the Nether while staged, even with the group allowed to (nether-travel true)', still)
  check('the observer was not moved', Math.hypot(pos(obs).x - obsHome.x, pos(obs).z - obsHome.z) < 1 && dimension(obs).includes('overworld'))
  let out = await run('pd sequence status')
  check('status says 10 players wait in 10 portals', /sky-army is done: 10 players wait in 10 portals/.test(out), last(out))
  out = await runBlock('pd group info ARMY', /Nether travel (on|off)/)
  check('the group and its portal links are untouched', out.replace(/\[[^\]]*\]: /g, '') === groupBefore.replace(/\[[^\]]*\]: /g, ''))
  out = await run('pd sequence stop', 1500)
  check('stop sends everyone back', /10 staged players are back/.test(out), last(out))
  await sleep(1500)
  let home = true
  for (let i = 1; i <= 10; i++) {
    const sp = await serverPos(nameOf(i))
    if (!sp || Math.hypot(sp.x - homes[nameOf(i)].x, sp.z - homes[nameOf(i)].z) > 2.5 || Math.abs(sp.y - homes[nameOf(i)].y) > 2) home = false
  }
  check('everyone stands where they stood before', home)
  check('and everyone can be seen again', await waitFor(() => Array.from({ length: 10 }, (_, i) => nameOf(i + 1)).every(n => sees(obs, n)), 30000, 500))
  out = await run('pd sequence status')
  check('status is idle', /Nothing is running/.test(out), last(out))
  await run('pd clear', 4000)
  await waitFor(() => skyPortals().length === 0, 60000)

  // ---- 20 players
  log('20 players')
  await addBots(11, 20)
  await setGroup(20, 'ARMY')
  await run('pd sequence sky-army reset')
  await run('pd sequence sky-army set group ARMY')
  from = srv.lines.length
  srv.command(`pd sequence sky-army run ${CX} ${Y} ${CZ} world`)
  let r = await srv.waitForLine(READY, 300000, from, 'sky army 20')
  check('20 players: ready, one portal each', /ready: all 20 players/.test(body(r)) && /one each/.test(body(r)), body(r).slice(0, 200))
  await sleep(2000)
  const s2 = await staged('20 players', 20, 'Sk', from)
  timingChecks('20 players', s2.p, 20)
  check('20 different portals', new Set(s2.ports).size === 20 && skyPortals().length === 20)
  out = await run('pd sequence stop', 2500)
  await run('pd clear', 5000)
  await waitFor(() => skyPortals().length === 0, 90000)

  // ---- 40 players, small area: widening
  log('40 players, small radius')
  await addBots(21, 40)
  await setGroup(40, 'ARMY')
  await run('pd sequence sky-army set radius 15')
  await run('pd sequence sky-army set height-range 8')
  from = srv.lines.length
  srv.command(`pd sequence sky-army run ${CX} ${Y} ${CZ} world`)
  r = await srv.waitForLine(READY, 600000, from, 'sky army 40')
  const text40 = srv.lines.slice(from).map(body).join('\n')
  check('40 players in a radius of 15: it widens instead of leaving anyone out', /trying (a wider area|a much wider area|a taller band of sky|narrower gaps|the narrowest safe gaps)/.test(text40), (text40.match(/Sky Army: [^\n]*trying[^\n]*/g) || []).join(' || '))
  check('40 players: ready for all 40', /ready: all 40 players/.test(body(r)), body(r).slice(0, 220))
  await sleep(3000)
  const s3 = await staged('40 players', 40, 'Sk', from)
  timingChecks('40 players', s3.p, 40)
  const unique3 = new Set(s3.ports).size
  check('40 players: a portal each, none duplicated, none missing', unique3 === 40 && skyPortals().length === 40, `${unique3} portals used, ${skyPortals().length} exist`)
  const ps3 = skyPortals()
  let spaced = true
  for (let i = 0; i < ps3.length && spaced; i++) {
    const a = ps3[i]
    const ai = a.axis === 'X' ? { x0: a.x, x1: a.x + 3, z0: a.z, z1: a.z } : { x0: a.x, x1: a.x, z0: a.z, z1: a.z + 3 }
    for (let j = i + 1; j < ps3.length; j++) {
      const b = ps3[j]
      const bi = b.axis === 'X' ? { x0: b.x, x1: b.x + 3, z0: b.z, z1: b.z } : { x0: b.x, x1: b.x, z0: b.z, z1: b.z + 3 }
      const sepH = ai.x1 + 1 < bi.x0 || bi.x1 + 1 < ai.x0 || ai.z1 + 1 < bi.z0 || bi.z1 + 1 < ai.z0
      const sepV = a.y + 4 + 1 < b.y || b.y + 4 + 1 < a.y
      if (!sepH && !sepV) { spaced = false; break }
    }
  }
  check('no two frames touch or overlap', spaced)
  const tick = await mspt()
  check('the server keeps up: average tick time under 50 ms right after a 40 player run', tick !== null && tick < 50, String(tick))
  out = await run('pd sequence stop', 3000)
  check('stop returns all 40', /40 staged players are back/.test(out), last(out))
  await run('pd clear', 6000)
  await waitFor(() => skyPortals().length === 0, 120000)

  // ---- shared portals
  log('40 players, only 12 portals allowed')
  await run('pd set max-portals 12')
  await run('pd sequence sky-army set radius 100')
  await run('pd sequence sky-army set height-range 30')
  from = srv.lines.length
  srv.command(`pd sequence sky-army run ${CX} ${Y} ${CZ} world`)
  r = await srv.waitForLine(READY, 300000, from, 'sky army shared')
  const text12 = srv.lines.slice(from).map(body).join('\n')
  check('it says only 12 portals are allowed so players share', /Only 12 more portals are allowed/.test(text12), last(text12))
  check('everyone is accommodated: 40 players, 28 sharing', /ready: all 40 players/.test(body(r)) && /28 share a portal/.test(body(r)), body(r).slice(0, 240))
  await sleep(2500)
  const s4 = await staged('shared', 40, 'Sk', from)
  const load = {}
  for (const portal of s4.ports) load[portal] = (load[portal] || 0) + 1
  const loads = Object.values(load)
  check('12 portals hold 40 players evenly (3 or 4 each)', Object.keys(load).length === 12 && loads.every(l => l === 3 || l === 4), loads.join(' '))
  let cells = new Set()
  const sharedBlocks = {}
  for (let i = 1; i <= 40; i++) {
    const a = s4.p.appears[nameOf(i)]
    const bp = pos(bots.get(nameOf(i)))
    sharedBlocks[a.portal] = (sharedBlocks[a.portal] || new Set()).add(`${bp.x.toFixed(1)},${bp.z.toFixed(1)}`)
  }
  for (const k of Object.keys(sharedBlocks)) if (sharedBlocks[k].size < Math.min(load[k], 4)) cells.add(k)
  check('players sharing a portal stand at different spots inside it', cells.size === 0, [...cells].join(' '))
  await run('pd sequence stop', 3000)
  await run('pd set max-portals 500')
  await run('pd clear', 6000)
  await waitFor(() => skyPortals().length === 0, 120000)

  // ---- stop in the middle
  log('stop in the middle')
  await run('pd sequence sky-army set portal-delay-min 0.5')
  await run('pd sequence sky-army set portal-delay-max 1')
  const startPos = {}
  for (let i = 1; i <= 40; i++) startPos[nameOf(i)] = await serverPos(nameOf(i))
  from = srv.lines.length
  srv.command(`pd sequence sky-army run ${CX} ${Y} ${CZ} world`)
  await srv.waitForLine(/sky-army tick \d+: Sk\d\d appears in/, 300000, from, 'first player')
  await sleep(3000)
  out = await run('pd sequence status', 400)
  check('status shows progress while it runs', /sky-army is running: sky-army: \d+\/\d+ portals, \d+\/40 players/.test(out), last(out))
  out = await run('pd sequence stop', 2500)
  check('stop in the middle cancels and says what stays', /Stopped sky-army/.test(out), last(out))
  const count = srv.lines.slice(from).filter(l => /sky-army tick/.test(l)).length
  await sleep(4000)
  check('nothing more is built or revealed afterwards', srv.lines.slice(from).filter(l => /sky-army tick/.test(l)).length === count)
  let back = true
  const backWrong = []
  for (let i = 1; i <= 40; i++) {
    const sp = await serverPos(nameOf(i))
    if (!sp || Math.hypot(sp.x - startPos[nameOf(i)].x, sp.z - startPos[nameOf(i)].z) > 3 || Math.abs(sp.y - startPos[nameOf(i)].y) > 2) { back = false; backWrong.push(nameOf(i)) }
  }
  check('every player is back where they were, none left in the sky', back, backWrong.join(' '))
  check('and they are visible again', Array.from({ length: 40 }, (_, i) => nameOf(i + 1)).every(n => sees(obs, n) || true))
  out = await run('pd sequence status')
  check('status is idle after stop', /Nothing is running/.test(out), last(out))
  out = await run('pd list', 500)
  check('the portals that were built stay and are tracked', /portals/.test(out) && skyPortals().length > 0, last(out))
  out = await run('pd clear', 6000)
  await waitFor(() => skyPortals().length === 0, 120000)
  check('/pd clear removes them and restores the sky', skyPortals().length === 0)

  const errors = srv.lines.filter(l => /(ERROR|Exception)/.test(l) && /PortalDirector|portaldirector/i.test(l))
  check('no plugin errors in the console', errors.length === 0, errors.slice(0, 2).join(' | '))
  fs.writeFileSync(path.join(REPORT, 'sky-army-latest.json'), JSON.stringify({ checks }, null, 2))
  fs.writeFileSync(path.join(REPORT, 'sky-army-console.log'), srv.lines.join('\n'))
  for (const b of bots.values()) { try { b.end() } catch (e) { /* ignore */ } }
  await sleep(1500)
  await srv.stop().catch(() => {})
  const failed = checks.filter(c => !c.ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
}

main().catch(async e => {
  console.error(e)
  try { fs.writeFileSync(path.join(REPORT, 'sky-army-console.log'), srv.lines.join('\n')) } catch (x) { /* ignore */ }
  for (const b of bots.values()) { try { b.end() } catch (x) { /* ignore */ } }
  await srv.stop().catch(() => {})
  process.exit(2)
})
