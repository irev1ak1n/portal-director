'use strict'
/*
 * Sequences live test on an isolated Paper server: the /pd sequence commands, settings that stay separate from /pd set, tab
 * completion and help through a real client, nether-arrival (portal, partner, safe waiting places, nobody walked in, manual
 * entry, travel allowance that does not change the group setting), tnt-strike (random counts, staggered times, directions, safe
 * and real modes, the cumulative limit, stop) and settings surviving a restart. Nothing here is visual verification.
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
const log = (...a) => console.log('[sequence-test]', ...a)
const checks = []
function check (name, ok, detail = '') {
  checks.push({ name, ok: !!ok, detail })
  console.log(`${ok ? '  PASS' : '  FAIL'}  ${name}${detail ? '  (' + String(detail).slice(0, 300) + ')' : ''}`)
}
const srv = new TestServer({ dir: SERVER_DIR, jar: 'paper-26.3-231.jar' })

function prepareServer () {
  const text = fs.readFileSync(path.join(SERVER_DIR, 'server.properties'), 'utf8')
  const get = k => (text.match(new RegExp('^' + k + '=(.*)$', 'm')) || [])[1]
  if (get('server-ip') !== HOST || get('server-port') !== String(PORT)) throw new Error('Refusing to run: the server must be 127.0.0.1:' + PORT)
  if (PORT === 25590 || PORT === 25592) throw new Error('Refusing to touch the play servers')
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
function clearMarks () { srv.lines = srv.lines.filter(l => !/\[Server\] BAD/.test(l)) }
async function flush (tag) {
  const from = srv.lines.length
  srv.command('say ' + tag)
  await srv.waitForLine(new RegExp(tag), 180000, from)
  await sleep(250)
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
const netherScene = () => {
  const f = path.join(PLUGIN_DIR, 'nether', 'links.yml')
  return fs.existsSync(f) ? readScene(f) : { portals: [] }
}
function pairs () {
  const f = path.join(PLUGIN_DIR, 'nether', 'pairs.yml')
  if (!fs.existsSync(f)) return {}
  const out = {}
  for (const l of fs.readFileSync(f, 'utf8').split(/\r?\n/)) {
    const m = l.match(/^ {2}(.+)\|(\d+): (\d+)/)
    if (m) out[`${m[1]}/${m[2]}`] = +m[3]
  }
  return out
}
const cxOf = p => (p.axis === 'X' ? p.x + 2 : p.x + 0.5)
const czOf = p => (p.axis === 'Z' ? p.z + 2 : p.z + 0.5)
const byName = n => overworld().portals.find(p => p.name === n)
const partnerOf = n => {
  const o = byName(n)
  if (!o) return null
  return netherScene().portals.find(p => p.id === pairs()[`default/${o.id}`]) || null
}
async function portalComplete (p, dim) {
  clearMarks()
  for (let i = 0; i < 4; i++) {
    for (let j = 0; j < 5; j++) {
      const x = p.axis === 'X' ? p.x + i : p.x
      const z = p.axis === 'Z' ? p.z + i : p.z
      const frame = i === 0 || i === 3 || j === 0 || j === 4
      const want = frame ? 'minecraft:obsidian' : `minecraft:nether_portal[axis=${p.axis.toLowerCase()}]`
      srv.command(`execute in ${dim} unless block ${x} ${p.y + j} ${z} ${want} run say BAD`)
    }
  }
  await flush('PCHECK' + Date.now())
  return srv.lines.filter(l => /\[Server\] BAD/.test(l)).length === 0
}

const mineflayer = require('@wp2508/mineflayer')
const ALPHA = (x, y) => (x.toLowerCase() < y.toLowerCase() ? -1 : x.toLowerCase() > y.toLowerCase() ? 1 : 0)
const isSorted = l => l.every((v, i) => i === 0 || ALPHA(l[i - 1], v) <= 0)
const names = o => o.map(x => (x.match || x).toString())
const tab = async (bot, text) => names(await bot.tabComplete(text, true))
const bots = new Map()
async function connect (name) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: name, auth: 'offline', version: '26.3', viewDistance: 'tiny', checkTimeoutInterval: 60000 })
  bot.on('error', e => console.error(`[${name}] error:`, e.message))
  bots.set(name, bot)
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(name + ' did not spawn')), 60000)
    bot.once('spawn', () => { clearTimeout(t); resolve() })
  })
  return bot
}
const dimension = b => String(b.game && b.game.dimension)
const pos = b => ({ x: b.entity.position.x, y: b.entity.position.y, z: b.entity.position.z })

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
  const Y = 130
  await runUntil(`forceload add ${CX - 80} ${CZ - 80} ${CX + 100} ${CZ + 80}`, /Marked \d+ chunks/, 600000)
  await sleep(3000)
  await run(`fill ${CX - 8} ${Y - 1} ${CZ - 10} ${CX + 62} ${Y - 1} ${CZ + 10} stone`, 2500)

  const NAMES = ['PdA', 'PdB', 'PdC', 'PdD', 'PdE', 'PdF', 'Obs']
  for (const n of NAMES) await connect(n)
  for (const n of NAMES) await run('op ' + n, 150)
  const obs = bots.get('Obs')

  // ---- commands, settings and help
  log('commands and settings')
  let out = await run('pd sequence list')
  check('/pd sequence list names all three with a description and an example', ['nether-arrival', 'sky-army', 'tnt-strike'].every(n => out.includes(n)) && /Try: \/pd sequence sky-army run here/.test(out), last(out))
  out = await run('pd sequence sky-army info')
  check('info says what it does, how to run it, players, TNT and the settings', /Run it: \/pd sequence sky-army run <x y z \| here \| player>/.test(out) && /teleported automatically/.test(out) && /No TNT/.test(out) && /radius +100/.test(out) && /portal-delay-min +0\.15/.test(out) && /player-delay-max +0\.5/.test(out), last(out))
  const settingRows = out.split('\n').map(body).filter(l => /^ {4}[a-z-]+ /.test(l)).map(l => l.trim().split(/\s+/)[0])
  check('the settings are listed A to Z', settingRows.length >= 6 && isSorted(settingRows), settingRows.join(' '))
  const globalBefore = await run('pd settings all', 800)
  out = await run('pd sequence sky-army set radius 120')
  check('set changes that sequence only', /radius is now 120/.test(out) && /Only this sequence changed/.test(out), last(out))
  const globalAfter = await run('pd settings all', 800)
  check('the global /pd set settings are untouched', globalBefore.split('\n').map(body).join('\n') === globalAfter.split('\n').map(body).join('\n').replace(/^$/m, ''), 'compared /pd settings all')
  out = await run('pd sequence tnt-strike info')
  check('another sequence keeps its own radius', /radius +100/.test(out), last(out))
  out = await run('pd sequence sky-army info')
  check('and this one shows the new value', /radius +120/.test(out), last(out))
  const file = path.join(PLUGIN_DIR, 'sequences.yml')
  check('it is saved in sequences.yml, not in config.yml', fs.existsSync(file) && /sky-army/.test(fs.readFileSync(file, 'utf8')) && !/sky-army/.test(fs.readFileSync(path.join(PLUGIN_DIR, 'config.yml'), 'utf8')))
  out = await run('pd sequence sky-army set portal-delay-min 5')
  check('a minimum above the maximum is refused', /cannot be above portal-delay-max/.test(out), last(out))
  out = await run('pd sequence sky-army set bogus 1')
  check('an unknown setting is refused and the real ones are listed', /no setting called 'bogus'/.test(out) && /portal-delay-min/.test(out), last(out))
  out = await run('pd sequence sky-army set radius 5')
  check('an out-of-range value is refused with the range', /between 10 and 400/.test(out), last(out))
  out = await run('pd sequence sky-army set group Nogroup')
  check('an unknown group is refused', /no group called Nogroup/.test(out), last(out))
  out = await run('pd sequence nonsense info')
  check('an unknown sequence is refused and the three are named', /no sequence called 'nonsense'/.test(out) && /nether-arrival, sky-army, tnt-strike/.test(out), last(out))
  out = await run('pd sequence sky-army reset')
  check('reset restores the defaults', /back to its default settings/.test(out), last(out))
  out = await run('pd sequence sky-army info')
  check('radius is 100 again', /radius +100/.test(out), last(out))
  check('and the saved file no longer holds it', !/sky-army/.test(fs.readFileSync(file, 'utf8')))
  out = await run('pd sequence status')
  check('status says nothing is running', /Nothing is running/.test(out), last(out))
  out = await run('pd sequence stop')
  check('stop with nothing running is harmless', /No sequence is running/.test(out), last(out))
  out = await run('pd help sequence', 800)
  check('help has a sequence topic with the three sequences', /Nether Arrival/.test(out) && /Sky Army/.test(out) && /TNT Strike/.test(out) && /\/pd sequence <name> set <option> <value>/.test(out), last(out))

  // ---- tab completion through a real client
  log('tab completion')
  try {
    await run('pd group create NULL')
    for (const n of ['PdA', 'PdB', 'PdC', 'PdD', 'PdE']) await run(`pd group assign-to-group ${n} NULL`, 200)
    let t = await tab(obs, '/pd sequence ')
    check('/pd sequence suggests list, status, stop and the three names A to Z', JSON.stringify(t) === JSON.stringify(['list', 'nether-arrival', 'sky-army', 'status', 'stop', 'tnt-strike']), t.join(' '))
    t = await tab(obs, '/pd sequence sky-army ')
    check('a sequence suggests info, reset, run, set', JSON.stringify(t) === JSON.stringify(['info', 'reset', 'run', 'set']), t.join(' '))
    t = await tab(obs, '/pd sequence tnt-strike set ')
    check('set suggests the setting names A to Z', t.length >= 9 && isSorted(t) && t.includes('tnt-mode') && t.includes('launch-delay-max'), t.join(' '))
    t = await tab(obs, '/pd sequence nether-arrival set group ')
    check('set group suggests the existing groups', t.includes('NULL'), t.join(' '))
    t = await tab(obs, '/pd sequence tnt-strike set tnt-mode ')
    check('a choice setting suggests its values', JSON.stringify(t) === JSON.stringify(['real', 'safe']), t.join(' '))
    t = await tab(obs, '/pd sequence sky-army run ')
    check('run suggests here and coordinates', t.includes('here'), t.join(' '))
    t = await tab(obs, '/pd sequence nether-arrival run here ')
    check('nether-arrival then suggests the four directions', JSON.stringify(t) === JSON.stringify(['east', 'north', 'south', 'west']), t.join(' '))
    t = await tab(obs, '/pd sequence nether-arrival run 1 2 3 ')
    check('also after explicit coordinates', JSON.stringify(t) === JSON.stringify(['east', 'north', 'south', 'west']), t.join(' '))
  } catch (e) { check('tab completion', false, e.message) }

  // ---- nether-arrival
  log('nether-arrival')
  await run('pd group nether-travel false')
  await run('pd sequence nether-arrival set group NULL')
  out = await run('pd sequence nether-arrival set spawn-distance 5')
  check('nether-arrival settings are its own', /spawn-distance is now 5/.test(out), last(out))
  out = await run('pd sequence nether-arrival run 1 2', 400)
  check('run without enough arguments explains what is missing', /Where, and which way/.test(out), last(out))
  out = await run(`pd sequence nether-arrival run ${CX} ${Y} ${CZ} sideways world`, 400)
  check('a bad direction is refused', /not a direction/.test(out), last(out))
  const startedAt = Date.now()
  out = await runUntil(`pd sequence nether-arrival run ${CX} ${Y} ${CZ} north world`, /Nether Arrival (ready|is only partly|could not|failed)/, 120000)
  check('nether-arrival reports ready for all five', /ready: all 5 players/.test(out), last(out))
  const arrivalPortal = byName('nether-arrival')
  check('the portal exists at the chosen anchor, facing north', arrivalPortal && arrivalPortal.x === CX - 1 && arrivalPortal.y === Y - 1 && arrivalPortal.z === CZ && arrivalPortal.axis === 'X' && /NORTH/i.test(arrivalPortal.facing), JSON.stringify(arrivalPortal))
  check('it is a complete Overworld portal', arrivalPortal && await portalComplete(arrivalPortal, 'minecraft:overworld'))
  const partner = partnerOf('nether-arrival')
  check('it has a Nether partner with the same axis and facing', partner && partner.axis === 'X' && /NORTH/i.test(partner.facing), JSON.stringify(partner))
  check('and the partner is a complete Nether portal', partner && await portalComplete(partner, 'minecraft:the_nether'))
  const members = ['PdA', 'PdB', 'PdC', 'PdD', 'PdE']
  const inNether = await waitFor(() => members.every(n => dimension(bots.get(n)).includes('nether')), 20000)
  check('all five group members are in the Nether', inNether, members.map(n => dimension(bots.get(n))).join(' '))
  check('a non-member and the observer were not moved', dimension(bots.get('PdF')).includes('overworld') && dimension(obs).includes('overworld'))
  await sleep(1500)
  const spots = members.map(n => pos(bots.get(n)))
  const pcx = cxOf(partner)
  const pcz = czOf(partner)
  const dists = spots.map(p => Math.hypot(p.x - pcx, p.z - pcz))
  check('they wait near the Nether portal, not in it', dists.every(d => d >= 2 && d <= 5 + 12 + 2), dists.map(d => d.toFixed(1)).join(' '))
  const cells = new Set(spots.map(p => `${Math.floor(p.x)},${Math.floor(p.y)},${Math.floor(p.z)}`))
  check('every player has a block of their own', cells.size === 5, [...cells].join(' | '))
  clearMarks()
  for (const n of members) {
    srv.command(`execute in minecraft:the_nether as ${n} at @s unless block ~ ~ ~ minecraft:air run say BADBODY ${n}`)
    srv.command(`execute in minecraft:the_nether as ${n} at @s unless block ~ ~1 ~ minecraft:air run say BADHEAD ${n}`)
    srv.command(`execute in minecraft:the_nether as ${n} at @s if block ~ ~-1 ~ minecraft:air run say BADFLOOR ${n}`)
    for (const dx of [-1, 0, 1]) for (const dz of [-1, 0, 1]) srv.command(`execute in minecraft:the_nether as ${n} at @s if block ~${dx} ~-1 ~${dz} minecraft:lava run say BADLAVA ${n}`)
  }
  await flush('SAFE' + Date.now())
  const unsafe = srv.lines.filter(l => /\[Server\] BAD(BODY|HEAD|FLOOR|LAVA)/.test(l))
  check('nobody stands in a wall, over air or next to lava', unsafe.length === 0, unsafe.slice(0, 2).join(' | '))
  await sleep(6000)
  const later = members.map(n => pos(bots.get(n)))
  const moved = later.map((p, i) => Math.hypot(p.x - spots[i].x, p.z - spots[i].z))
  check('nobody is walked into the portal: after 8 s they have not moved', moved.every(d => d < 0.6) && members.every(n => dimension(bots.get(n)).includes('nether')), moved.map(d => d.toFixed(2)).join(' '))
  out = await run('pd group info')
  check('the group still has Nether travel off', /Nether travel off/.test(out), last(out))
  out = await run('pd sequence status')
  check('status describes the arrival', /nether-arrival is done: 5 players wait beside the Nether partner of nether-arrival/.test(out), last(out))

  // manual entry
  const A = bots.get('PdA')
  await run(`execute in minecraft:the_nether run tp PdA ${pcx} ${partner.y + 1} ${pcz}`, 300)
  const back = await waitFor(() => dimension(A).includes('overworld'), 15000)
  check('a player who walks into the Nether portal arrives at the Overworld portal', back && Math.hypot(pos(A).x - (CX + 1), pos(A).z - (CZ + 0.5)) < 6, JSON.stringify(pos(A)))

  // the travel allowance, and the group setting that is not changed
  await run(`pd group link-to-portal PdB nether-arrival`)
  await run(`execute in minecraft:overworld run tp PdB ${CX + 1} ${Y} ${CZ + 0.5}`, 300)
  const B = bots.get('PdB')
  const wentThrough = await waitFor(() => dimension(B).includes('nether'), 15000)
  await sleep(500)
  check('the group can use this portal during the sequence even though nether-travel is false', wentThrough, dimension(B))
  await run('pd create portal EntranceZ ' + (CX + 30) + ' ' + Y + ' ' + CZ + ' east world', 4000)
  await run('pd group link-to-portal PdC EntranceZ')
  const C = bots.get('PdC')
  await run(`execute in minecraft:overworld run tp PdC ${CX + 30.5} ${Y} ${CZ + 2}`, 300)
  await sleep(9000)
  check('another linked portal still keeps its player inside (the group setting is not overridden)', dimension(C).includes('overworld'), dimension(C))
  await run('pd group unlink-from-portal PdB nether-arrival')
  out = await run('pd sequence stop')
  check('stop ends the arrival and says the group setting applies again', /Nether Arrival ended/.test(out), last(out))
  await run('pd group link-to-portal PdD nether-arrival')
  const D = bots.get('PdD')
  await run(`execute in minecraft:overworld run tp PdD ${CX + 1} ${Y} ${CZ + 0.5}`, 300)
  await sleep(9000)
  check('after stop the portal obeys the group setting again', dimension(D).includes('overworld'), dimension(D))
  out = await run('pd group info')
  check('Nether travel was never switched on', /Nether travel off/.test(out), last(out))
  out = await run('pd sequence status')
  check('status is idle again', /Nothing is running/.test(out), last(out))
  // running it again at the same spot reuses the portal
  out = await runUntil(`pd sequence nether-arrival run ${CX} ${Y} ${CZ} north world`, /Nether Arrival (ready|is only partly|could not|failed)/, 120000)
  check('running it again at the same place reuses the portal and does not delete anything', /using the portal nether-arrival|ready/.test(out) && overworld().portals.filter(p => /^nether-arrival/.test(p.name)).length === 1, last(out))
  out = await run(`pd sequence nether-arrival run ${CX} ${Y} ${CZ} east world`, 800)
  check('the same spot with another direction is refused', /already stands at/.test(out), last(out))
  await run('pd sequence stop')
  for (const n of members) await run(`execute in minecraft:overworld run tp ${n} ${CX + 20} ${Y + 2} ${CZ + 5}`, 100)

  // ---- tnt-strike
  log('tnt-strike')
  await run('pd clear', 3000)
  await waitFor(() => netherScene().portals.length === 0, 60000)
  await run(`forceload add ${CX - 80} ${CZ - 80} ${CX + 80} ${CZ + 80}`, 1500)
  await run('pd tnt clear')
  await run('pd tnt limit 20')
  await run('pd sequence tnt-strike set portals 12')
  await run('pd sequence tnt-strike set radius 50')
  const portalsBefore = overworld().portals.length
  out = await run(`pd sequence tnt-strike run ${CX} ${Y + 20} ${CZ} world`, 1200)
  check('a limit that is too low is reported before anything starts', /launch limit of 20 has only 20 left/.test(out) && /Nothing was built/.test(out), last(out))
  check('and nothing was built', overworld().portals.length === portalsBefore)
  await run('pd tnt limit 300')
  const tntBefore = (await run('pd settings tnt', 800)).split('\n').map(body).filter(l => /tnt\./.test(l)).join('\n')
  let from = srv.lines.length
  out = await runUntil(`pd sequence tnt-strike run ${CX} ${Y + 20} ${CZ} world`, /TNT Strike (done|could not|failed)/, 180000)
  check('tnt-strike finishes and reports safe mode', /portals fired \d+ TNT minecarts \(safe mode\)/.test(out), last(out))
  const logLines = srv.lines.slice(from).map(body)
  const builds = logLines.map(l => l.match(/tnt-strike tick (\d+): portal (tnt-strike-\d+) built facing (\w+)/)).filter(Boolean).map(m => ({ tick: +m[1], name: m[2], facing: m[3] }))
  const shots = logLines.map(l => l.match(/tnt-strike tick (\d+): minecart from (tnt-strike-\d+) facing (\w+)/)).filter(Boolean).map(m => ({ tick: +m[1], name: m[2], facing: m[3] }))
  check('twelve portals were built', builds.length === 12, String(builds.length))
  const gaps = builds.slice(1).map((b, i) => b.tick - builds[i].tick)
  check('the first portal appears at once and the others at short varied gaps (0.15 to 0.55 s)', builds[0].tick === 0 && gaps.every(g => g >= 3 && g <= 11) && new Set(gaps).size >= 3, gaps.join(' '))
  const perPortal = {}
  for (const s of shots) perPortal[s.name] = (perPortal[s.name] || 0) + 1
  const counts = builds.map(b => perPortal[b.name] || 0)
  check('every portal launches 5 to 10 minecarts', counts.every(c => c >= 5 && c <= 10), counts.join(' '))
  check('the numbers differ from portal to portal', new Set(counts).size >= 3, counts.join(' '))
  let launchGapsOk = true
  const firstShotTicks = []
  for (const b of builds) {
    const mine = shots.filter(s => s.name === b.name).map(s => s.tick)
    firstShotTicks.push(mine[0])
    let prev = b.tick
    for (const t of mine) {
      if (t - prev < 4 || t - prev > 14) launchGapsOk = false
      prev = t
    }
  }
  check('a portal waits 0.2 to 0.7 s before each of its minecarts and never fires before it exists', launchGapsOk && shots.every(s => s.tick > builds.find(b => b.name === s.name).tick))
  check('launches are staggered, not all at the same moment', new Set(firstShotTicks).size >= 3, firstShotTicks.join(' '))
  const sc = overworld()
  const mine = sc.portals.filter(p => /^tnt-strike/.test(p.name))
  check('the portals are floating and face several directions', mine.length === 12 && mine.every(p => p.floating) && new Set(mine.map(p => p.facing)).size >= 3, [...new Set(mine.map(p => p.facing))].join(' '))
  const total = shots.length
  out = await run('pd tnt status')
  check('the cumulative count matches what was launched', new RegExp(`Launched: ${total} of at most 300`).test(out), `${total}: ${last(out)}`)
  await sleep(2500)
  out = await run('execute if entity @e[type=minecraft:tnt_minecart]', 500)
  check('safe mode: minecarts fly past their fuse without exploding (global mode is real)', /Test passed/.test(out), last(out))
  const tntAfter = (await run('pd settings tnt', 800)).split('\n').map(body).filter(l => /tnt\./.test(l)).join('\n')
  check('the global TNT settings are unchanged by the sequence', tntBefore === tntAfter)
  check('the portals are intact', (await Promise.all(mine.slice(0, 4).map(p => portalComplete(p, 'minecraft:overworld')))).every(Boolean))

  // real mode, chosen for the sequence only
  await run('pd tnt clear')
  await run('pd tnt mode safe')
  await run('pd sequence tnt-strike set tnt-mode real')
  await run('pd sequence tnt-strike set portals 4')
  await run('pd sequence tnt-strike set tnt-min 5')
  await run('pd sequence tnt-strike set tnt-max 5')
  out = await runUntil(`pd sequence tnt-strike run ${CX} ${Y + 20} ${CZ} world`, /TNT Strike (done|could not|failed)/, 180000)
  check('real mode: reports 20 minecarts in real mode', /fired 20 TNT minecarts \(real mode\)/.test(out), last(out))
  await sleep(7000)
  out = await run('execute if entity @e[type=minecraft:tnt_minecart]', 500)
  check('real mode: every minecart exploded', /Test failed/.test(out), last(out))
  out = await run('pd tnt status')
  check('the global mode is still safe', /SAFE MODE is on/.test(out), last(out))
  const newest = overworld().portals.filter(p => /^tnt-strike/.test(p.name)).slice(-4)
  check('the explosions left the portals standing', (await Promise.all(newest.map(p => portalComplete(p, 'minecraft:overworld')))).every(Boolean))
  out = await run('pd tnt status')
  check('the count is cumulative and was never reset by a sequence', /Launched: 3\d\d? of at most 300|Launched: \d+ of at most 300/.test(out) && !/Launched: 0 /.test(out), last(out))

  // directions
  log('directions')
  await run('pd sequence tnt-strike set tnt-mode safe')
  await run('pd sequence tnt-strike set portals 1')
  await run('pd sequence tnt-strike set tnt-min 1')
  await run('pd sequence tnt-strike set tnt-max 1')
  const wrong = []
  const seen = new Set()
  for (let i = 0; i < 6; i++) {
    await run('pd tnt clear', 300)
    from = srv.lines.length
    srv.command(`pd sequence tnt-strike run ${CX} ${Y + 20} ${CZ} world`)
    const line = await srv.waitForLine(/tnt-strike tick \d+: minecart from tnt-strike-\d+ facing \w+/, 60000, from, 'minecart')
    const facing = line.match(/facing (\w+)/)[1]
    const o = await run('data get entity @e[type=minecraft:tnt_minecart,limit=1] Motion', 250)
    const v = (o.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/) || []).slice(1, 4).map(Number)
    const ok = v.length === 3 && ({ north: v[2] < -0.3 && Math.abs(v[0]) < 0.05, south: v[2] > 0.3 && Math.abs(v[0]) < 0.05, east: v[0] > 0.3 && Math.abs(v[2]) < 0.05, west: v[0] < -0.3 && Math.abs(v[2]) < 0.05 })[facing]
    seen.add(facing)
    if (!ok) wrong.push(`${facing}: ${v.join(',')}`)
    await waitFor(() => !srv.lines.slice(from).some(l => /TNT Strike (done|could not|failed)/.test(l)) ? false : true, 20000)
  }
  check('minecarts leave outward, the way their portal faces (6 runs: ' + [...seen].join(' ') + ')', wrong.length === 0, wrong.join(' | '))

  // stop in the middle
  log('stop')
  await run('pd tnt clear')
  await run('pd sequence tnt-strike reset')
  await run('pd sequence tnt-strike set portals 20')
  await run('pd sequence tnt-strike set radius 50')
  from = srv.lines.length
  srv.command(`pd sequence tnt-strike run ${CX} ${Y + 40} ${CZ} world`)
  await srv.waitForLine(/tnt-strike tick \d+: minecart from/, 60000, from, 'first minecart')
  out = await run('pd sequence status', 400)
  check('status shows progress while it runs', /tnt-strike is running: tnt-strike: \d+\/\d+ portals, \d+\/\d+ minecarts launched/.test(out), last(out))
  out = await run('pd sequence stop', 600)
  check('stop cancels it and says what stays', /Stopped tnt-strike/.test(out), last(out))
  const stoppedAt = srv.lines.filter(l => /tnt-strike tick/.test(l)).length
  await sleep(4000)
  check('nothing more is launched or built after stop', srv.lines.filter(l => /tnt-strike tick/.test(l)).length === stoppedAt)
  out = await run('pd sequence status')
  check('status is idle after stop', /Nothing is running/.test(out), last(out))
  out = await run('pd tnt launch all 1', 800)
  check('manual commands still work afterwards', /Launching \d+ TNT minecarts/.test(out), last(out))
  await run('pd tnt clear')

  // ---- settings survive a restart
  log('restart')
  await run('pd sequence sky-army set radius 140')
  await run('pd sequence nether-arrival set spawn-distance 7')
  await run('pd sequence tnt-strike set tnt-mode real')
  for (const b of bots.values()) { try { b.end() } catch (e) { /* ignore */ } }
  await sleep(1500)
  await srv.stop().catch(() => {})
  const restartFrom = srv.lines.length
  srv.start()
  await srv.waitForLine(/Done \(/, 300000, restartFrom, 'server restart')
  await sleep(2000)
  out = await runBlock('pd sequence sky-army info', /reset restores the defaults/)
  check('after a restart sky-army keeps its radius', /radius +140/.test(out), last(out))
  out = await runBlock('pd sequence nether-arrival info', /reset restores the defaults/)
  check('nether-arrival keeps its spawn-distance and group', /spawn-distance +7/.test(out) && /group +NULL/.test(out), last(out))
  out = await runBlock('pd sequence tnt-strike info', /reset restores the defaults/)
  check('tnt-strike keeps its mode', /tnt-mode +real/.test(out), last(out))
  out = await runBlock('pd settings all', /(SAFE MODE is on|Real explosions are on)/)
  check('and the global radius is still its own', /Radius: 100 blocks/.test(out), last(out))
  await run('pd clear', 4000)

  const errors = srv.lines.filter(l => /(ERROR|Exception)/.test(l) && /PortalDirector|portaldirector/i.test(l))
  check('no plugin errors in the console', errors.length === 0, errors.slice(0, 2).join(' | '))
  fs.writeFileSync(path.join(REPORT, 'sequence-latest.json'), JSON.stringify({ checks }, null, 2))
  fs.writeFileSync(path.join(REPORT, 'sequence-console.log'), srv.lines.join('\n'))
  await srv.stop().catch(() => {})
  const failed = checks.filter(c => !c.ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed (started ${Math.round((Date.now() - startedAt) / 1000)} s after the arrival test began)`)
  process.exit(failed.length ? 1 : 0)
}

main().catch(async e => {
  console.error(e)
  try { fs.writeFileSync(path.join(REPORT, 'sequence-console.log'), srv.lines.join('\n')) } catch (x) { /* ignore */ }
  for (const b of bots.values()) { try { b.end() } catch (x) { /* ignore */ } }
  await srv.stop().catch(() => {})
  process.exit(2)
})
