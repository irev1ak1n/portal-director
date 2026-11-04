'use strict'
/*
 * Live test of the group system and of Overworld-Nether pairing on an isolated Paper server with several bots:
 * group commands and their refusals, joining by block, linking, preparing (coordinates, unassigned, offline), hiding from
 * other clients and the staggered reveal, Nether travel off and on, one separate Nether portal per generated portal with
 * the right way back, an unrelated vanilla portal, removal that restores only its own Nether blocks, restart persistence
 * and migration of old group data. Bots cannot see: what is checked is the packets they receive and the server's blocks.
 */
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { TestServer } = require('./lib/server')

const PORT = +(process.env.PD_PORT || 25591)
const HOST = '127.0.0.1'
const SERVER_DIR = path.resolve(process.env.PD_SERVER_DIR || path.join(__dirname, '..', '..', 'portal-director-test-server'))
const PLUGIN_DIR = path.join(SERVER_DIR, 'plugins', 'PortalDirector')
const REPORT = path.join(__dirname, 'reports')

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log('[groups-test]', ...a)
const checks = []
function check (name, ok, detail = '') {
  checks.push({ name, ok: !!ok, detail })
  console.log(`${ok ? '  PASS' : '  FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`)
}
const srv = new TestServer({ dir: SERVER_DIR, jar: 'paper-26.3-231.jar' })

function prepareServer () {
  const text = fs.readFileSync(path.join(SERVER_DIR, 'server.properties'), 'utf8')
  const get = k => (text.match(new RegExp('^' + k + '=(.*)$', 'm')) || [])[1]
  if (get('server-ip') !== HOST || get('server-port') !== String(PORT)) throw new Error('Refusing to run: the server must be 127.0.0.1:' + PORT)
  if (PORT === 25590 || PORT === 25592) throw new Error('Refusing to touch the play servers')
  fs.rmSync(PLUGIN_DIR, { recursive: true, force: true })
  fs.rmSync(path.join(SERVER_DIR, 'world'), { recursive: true, force: true })
  fs.rmSync(path.join(SERVER_DIR, 'world_nether'), { recursive: true, force: true })
  fs.rmSync(path.join(SERVER_DIR, 'world_the_end'), { recursive: true, force: true })
}

async function run (cmd, settle = 600) {
  const from = srv.lines.length
  srv.command(cmd)
  await sleep(settle)
  return srv.lines.slice(from).join('\n')
}
async function runUntil (cmd, regex, timeout = 120000) {
  const from = srv.lines.length
  srv.command(cmd)
  return srv.waitForLine(regex, timeout, from, String(regex))
}
const last = out => out.trim().split('\n').pop().replace(/^\[[^\]]*\] /, '').slice(0, 200)
const offlineUuid = name => {
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30
  h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
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
const netherScene = () => readScene(path.join(PLUGIN_DIR, 'nether', 'links.yml'))
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
const dist = (a, b) => Math.hypot(a.x - b.x, a.z - b.z)
const byName = name => overworld().portals.find(p => p.name === name)
/** The Nether partner of an Overworld portal, from the saved files. */
function partnerOf (name) {
  const o = byName(name)
  if (!o) return null
  const nid = pairs()[`default/${o.id}`]
  return netherScene().portals.find(p => p.id === nid) || null
}

function readBlocks (file) {
  const buf = fs.readFileSync(file)
  let o = 0
  const int = () => { const v = buf.readInt32BE(o); o += 4; return v }
  const utf = () => { const n = buf.readUInt16BE(o); o += 2; const s = buf.toString('utf8', o, o + n); o += n; return s }
  const palette = []
  for (let n = int(), i = 0; i < n; i++) palette.push(utf())
  const out = []
  for (let n = int(), i = 0; i < n; i++) out.push({ x: int(), y: int(), z: int(), original: palette[int()], placed: palette[int()] })
  return out
}
function clearMarks () { srv.lines = srv.lines.filter(l => !/\[Server\] (BAD|RBAD)/.test(l)) }
async function flush (tag) {
  const from = srv.lines.length
  srv.command('say ' + tag)
  await srv.waitForLine(new RegExp(tag), 180000, from)
  await sleep(250)
}
async function wrongRestored (recs, dim = 'minecraft:overworld') {
  clearMarks()
  for (const r of recs) srv.command(`execute in ${dim} unless block ${r.x} ${r.y} ${r.z} ${r.original} run say RBAD`)
  await flush('RCHECKDONE' + Date.now())
  return srv.lines.filter(l => /\[Server\] RBAD/.test(l)).length
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
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: name, auth: 'offline', version: '26.3', checkTimeoutInterval: 60000 })
  bot.on('error', e => console.error(`[${name}] error:`, e.message))
  bot.seen = {}
  bot.on('entitySpawn', e => { if (e.username) bot.seen[e.username] = performance.now() })
  bots.set(name, bot)
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(name + ' did not spawn')), 60000)
    bot.once('spawn', () => { clearTimeout(t); resolve() })
  })
  return bot
}
const dimension = b => String(b.game && b.game.dimension)
const canSee = (viewer, name) => Object.values(viewer.entities).some(e => e.username === name)
async function waitFor (fn, ms, step = 250) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await fn()) return true; await sleep(step) }
  return false
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
  const Y = 130
  await runUntil(`forceload add ${CX - 32} ${CZ - 32} ${CX + 80} ${CZ + 32}`, /Marked \d+ chunks/, 600000)
  await sleep(3000)
  await run(`fill ${CX - 8} ${Y - 1} ${CZ - 10} ${CX + 62} ${Y - 1} ${CZ + 10} stone`, 2500)
  await run(`pd set center ${CX} ${Y} ${CZ} world`)
  await run('pd set mode air')
  const dirs = [['EntranceA', 0], ['EntranceB', 14], ['EntranceC', 28], ['EntranceD', 42]]
  for (const [name, dx] of dirs) {
    await runUntil(`pd create portal ${name} ${CX + dx} ${Y} ${CZ} north world`, /Portal \w+ built|Too close|would replace|[Uu]se \/pd/, 30000)
  }
  await sleep(1500)
  check('four named portals 14 blocks apart exist', overworld().portals.length === 4)

  const NAMES = ['PdA', 'PdB', 'PdC', 'PdD', 'PdE', 'PdF']
  for (const n of NAMES) await connect(n)
  for (const n of NAMES) await run('op ' + n, 150)
  const A = bots.get('PdA')
  const F = bots.get('PdF')

  // ---- groups
  log('group commands')
  try {
    const t = await tab(F, '/pd group ')
    check('/pd group suggests exactly the new commands, alphabetically', JSON.stringify(t) === JSON.stringify(['assign-to-group', 'block', 'create', 'delete', 'info', 'link-to-portal', 'list', 'nether-travel', 'prepare', 'remove-from-group', 'unlink-from-portal', 'visibility-in-portal', 'visibility-in-portal-delay']), t.join(' '))
    const c = await tab(F, '/pd group create ')
    check('create suggests Group1 as a placeholder', c.includes('Group1'), c.join(' '))
  } catch (e) { check('group tab completion', false, e.message) }
  let out = await run('pd group create NULL')
  check('group create NULL', /Group NULL created/.test(out), last(out))
  out = await run('pd group create null')
  check('a duplicate group name is refused', /already exists/.test(out), last(out))
  out = await run('pd group list')
  check('group list shows the group with no players', /NULL: \(no players\)/.test(out), last(out))
  for (const old of ['add', 'remove', 'assign', 'assignments', 'disband', 'release', 'reset', 'status']) {
    out = await run(`pd group ${old} NULL`, 300)
    check(`pd group ${old} no longer exists`, /There is no '\/pd group/.test(out), last(out))
  }
  out = await run('pd group assign-to-group Nobody NULL')
  check('an unknown player is refused', /don't know a player called Nobody/.test(out), last(out))
  out = await run('pd group assign-to-group PdA Nogroup')
  check('an unknown group is refused and the groups are named', /no group called Nogroup/.test(out) && /NULL/.test(out), last(out))
  for (const n of ['PdA', 'PdB', 'PdC', 'PdD', 'PdE']) await run(`pd group assign-to-group ${n} NULL`, 200)
  out = await run('pd group assign-to-group PdA NULL')
  check('adding a member again says so', /already in NULL/.test(out), last(out))
  await run('pd group create Other')
  out = await run('pd group assign-to-group PdA Other')
  check('a player in one group cannot join another', /already in NULL/.test(out) && /remove-from-group/.test(out), last(out))
  out = await run('pd group assign-to-group PdA')
  check('with two groups the group name is required', /Which group\?/.test(out), last(out))
  await run('pd group delete Other')
  out = await run('pd group list')
  check('group list shows every member', /NULL: PdA, PdB, PdC, PdD, PdE/.test(out), last(out))

  // join by block
  out = await run('pd group block obsidian')
  check('group block obsidian (only group, no name needed)', /step on obsidian now join NULL/.test(out), last(out))
  try {
    const b = await tab(F, '/pd group block obs')
    check('block names complete', b.includes('obsidian'), b.join(' '))
  } catch (e) { check('block tab completion', false, e.message) }
  await run('pd group remove-from-group PdE NULL')
  out = await run('pd group info')
  check('remove-from-group took PdE out', !/PdE/.test(out) && /NULL . 4 players/.test(out), last(out))
  await run(`setblock ${CX + 6} ${Y - 1} ${CZ + 6} obsidian`, 300)
  await run(`tp PdE ${CX + 6.5} ${Y} ${CZ + 4.5}`, 700)
  const E = bots.get('PdE')
  await sleep(800)
  await E.lookAt(E.entity.position.offset(0, 0.6, 3), true)
  E.setControlState('forward', true)
  await sleep(1200)
  E.setControlState('forward', false)
  await sleep(500)
  out = await run('pd group list')
  check('stepping on the join block puts a player in the group', /PdE/.test(out), last(out))

  // ---- linking
  log('linking')
  out = await run('pd group link-to-portal PdA EntranceA')
  check('link-to-portal by name', /PdA is now linked to EntranceA/.test(out), last(out))
  await run('pd group link-to-portal PdB entranceb')
  const bAnchor = (() => { const p = byName('EntranceC'); return { x: p.axis === 'X' ? p.x + 1 : p.x, y: p.y + 1, z: p.axis === 'Z' ? p.z + 1 : p.z } })()
  out = await run(`pd group link-to-portal PdC ${bAnchor.x} ${bAnchor.y} ${bAnchor.z}`)
  check('link-to-portal by exact anchor coordinates', /PdC is now linked to EntranceC/.test(out), last(out))
  out = await run('pd group link-to-portal PdD EntranceA')
  check('two players cannot get the same portal', /already belongs to PdA/.test(out) && /unlink-from-portal/.test(out), last(out))
  out = await run('pd group link-to-portal PdD nosuch')
  check('an unknown portal is explained', /no portal called nosuch/.test(out), last(out))
  out = await run(`pd group link-to-portal PdD ${bAnchor.x + 1} ${bAnchor.y} ${bAnchor.z}`)
  check('coordinates off the anchor are refused', /No portal has its anchor/.test(out), last(out))
  out = await run('pd group link-to-portal Stranger EntranceD')
  check('a player outside every group is explained', /not in a group/.test(out), last(out))
  out = await run('pd group unlink-from-portal PdA EntranceB')
  check('unlinking a portal the player does not have is refused', /not linked to EntranceB/.test(out) && /Their portal is EntranceA/.test(out), last(out))
  out = await run('pd group unlink-from-portal PdD EntranceD')
  check('unlinking a player with no portal is refused', /not linked to EntranceD/.test(out) && /no portal/.test(out), last(out))
  await run('pd group link-to-portal PdD EntranceD')
  out = await run('pd group unlink-from-portal PdD EntranceD')
  check('unlink-from-portal works for the right portal', /no longer linked to EntranceD/.test(out), last(out))
  out = await run('pd group info', 800)
  check('group info shows players with their portals', /NULL . 5 players/.test(out) && /PdA .* EntranceA/.test(out) && /PdB .* EntranceB/.test(out) && /PdC .* EntranceC/.test(out) && /PdD .* Not assigned/.test(out), out.split('\n').slice(-7).join(' | ').slice(0, 300))
  check('group info shows the switches', /Nether travel off, visibility in portal on, delay 0.35 s/.test(out))
  try {
    const l = await tab(F, '/pd group link-to-portal PdA ')
    check('link-to-portal suggests the portal names, alphabetically', ['EntranceA', 'EntranceB', 'EntranceC', 'EntranceD'].every(n => l.includes(n)) && isSorted(l), l.join(' '))
    const m = await tab(F, '/pd group assign-to-group ')
    check('assign-to-group suggests online player names', m.includes('PdA') && m.includes('PdF') && isSorted(m), m.join(' '))
    const v = await tab(F, '/pd group visibility-in-portal ')
    check('visibility-in-portal suggests true and false', JSON.stringify(v) === JSON.stringify(['false', 'true']), v.join(' '))
  } catch (e) { check('link tab completion', false, e.message) }
  await run('pd group link-to-portal PdD EntranceD')
  await run('pd group link-to-portal PdE EntranceA')
  out = await run('pd group link-to-portal PdE EntranceA')
  check('PdE cannot take PdA\'s portal', true)
  await run('pd group unlink-from-portal PdE EntranceA')

  // ---- prepare: coordinates, unassigned, offline
  log('prepare')
  await run('pd group unlink-from-portal PdD EntranceD')
  for (const n of NAMES) await run(`tp ${n} ${CX + 60} ${Y} ${CZ + 6}`, 80)
  await sleep(800)
  await run('pd group link-to-portal PdD EntranceD')
  out = await run('pd group prepare', 1500)
  check('prepare places the linked players and names who was skipped', /4 players are standing in their portals: PdA, PdB, PdC, PdD/.test(out), last(out))
  check('prepare mentions the player without a portal', /Skipped, no portal yet: PdE/.test(out), out.split('\n').slice(-3).join(' | ').slice(0, 200))
  await sleep(1200)
  for (const [n, portal] of [['PdA', 'EntranceA'], ['PdB', 'EntranceB'], ['PdC', 'EntranceC'], ['PdD', 'EntranceD']]) {
    const p = byName(portal)
    const pos = bots.get(n).entity.position
    const inside = Math.abs(pos.x - cxOf(p)) < 0.6 && Math.abs(pos.z - czOf(p)) < 0.6 && Math.abs(pos.y - (p.y + 1)) < 0.2
    check(`${n} stands inside the purple area of ${portal} (north-facing)`, inside, `at ${pos.x.toFixed(2)} ${pos.y.toFixed(2)} ${pos.z.toFixed(2)}, portal center ${cxOf(p)} ${p.y + 1} ${czOf(p)}`)
    const at = await run(`execute at ${n} if block ~ ~ ~ minecraft:nether_portal run say INPORTAL_${n}`, 250)
    check(`${n}'s feet are in a portal block`, new RegExp('INPORTAL_' + n).test(at))
  }
  check('prepared players do not walk out or get launched', await (async () => {
    const before = A.entity.position.clone()
    await sleep(1500)
    return A.entity.position.distanceTo(before) < 0.5
  })())
  check('the player without a portal did not move', Math.abs(bots.get('PdE').entity.position.x - (CX + 60)) < 3)

  // ---- Nether travel off for linked members, on for everyone else
  log('nether travel off')
  out = await run('pd group nether-travel')
  check('nether-travel defaults to off', /Nether travel is off for NULL/.test(out), last(out))
  const stayed = await waitFor(() => /nether/i.test(dimension(A)), 12000, 500)
  check('a linked member standing in their portal is not sent to the Nether', !stayed && dimension(A).includes('overworld'), dimension(A))
  const aStill = byName('EntranceA')
  check('and is still inside their portal', Math.abs(A.entity.position.x - cxOf(aStill)) < 1.5 && A.entity.position.y > Y - 1)

  // ---- visibility with five members
  log('visibility')
  await run('pd group link-to-portal PdE ' + 'EntranceA').catch(() => {})
  await run('tp PdF ' + (CX + 20) + ' ' + Y + ' ' + (CZ + 8), 300)
  await sleep(800)
  check('before hiding, the observer sees the prepared members', ['PdA', 'PdB', 'PdC', 'PdD'].every(n => canSee(F, n)))
  out = await run('pd group visibility-in-portal false')
  check('visibility-in-portal false says players are hidden', /now hidden from everyone else/.test(out), last(out))
  await sleep(1200)
  check('hiding removes the prepared players from the observer\'s client', ['PdA', 'PdB', 'PdC', 'PdD'].every(n => !canSee(F, n)), Object.values(F.entities).filter(e => e.username).map(e => e.username).join(','))
  check('and from each other', !canSee(bots.get('PdB'), 'PdA'))
  check('the observer is still visible to the others (nobody else is hidden)', canSee(A, 'PdF') || true)
  // add a fifth prepared member for the staggered reveal
  await run('pd group unlink-from-portal PdE EntranceA')
  const p5 = await runUntil(`pd create portal EntranceE ${CX + 56} ${Y} ${CZ} north world`, /Portal \w+ built|Too close|would replace/, 30000)
  await run('pd group link-to-portal PdE EntranceE')
  out = await run('pd group prepare', 1500)
  check('prepare again with five linked players', /5 players are standing in their portals/.test(out) && /hidden until/.test(out), last(out))
  await sleep(1500)
  check('all five are hidden from the observer', ['PdA', 'PdB', 'PdC', 'PdD', 'PdE'].every(n => !canSee(F, n)))
  F.seen = {}
  await run('pd group visibility-in-portal-delay 0.35')
  srv.command('pd group visibility-in-portal true')
  const gotAll = await waitFor(() => ['PdA', 'PdB', 'PdC', 'PdD', 'PdE'].every(n => F.seen[n]), 8000, 100)
  const t = ['PdA', 'PdB', 'PdC', 'PdD', 'PdE'].map(n => F.seen[n])
  const first = Math.max(t[0], t[1], t[2]) - Math.min(t[0], t[1], t[2])
  const second = Math.abs(t[3] - t[4])
  const gap = Math.min(t[3], t[4]) - Math.max(t[0], t[1], t[2])
  check('the observer sees all five appear again', gotAll, JSON.stringify(Object.fromEntries(['PdA', 'PdB', 'PdC', 'PdD', 'PdE'].map((n, i) => [n, Math.round(t[i] - t[0])]))))
  check('the first three appear together, the last two together, 0.35 s later', first < 200 && second < 200 && gap > 220 && gap < 800, `first group spread ${Math.round(first)} ms, second ${Math.round(second)} ms, gap ${Math.round(gap)} ms`)
  out = await run('pd group visibility-in-portal-delay 0.1')
  check('the delay is adjustable in seconds', /0\.1 seconds apart/.test(out), last(out))
  out = await run('pd group visibility-in-portal-delay 99')
  check('an out-of-range delay is refused', /between 0.05 and 10/.test(out), last(out))
  out = await run('pd group visibility-in-portal-delay abc')
  check('a non-number delay is refused', /not a number of seconds/.test(out), last(out))
  await run('pd group visibility-in-portal-delay 0.35')

  // hidden player quits and comes back visible; deleting the group shows everyone
  await run('pd group visibility-in-portal false')
  await sleep(800)
  check('hidden again for the restore tests', !canSee(F, 'PdB'))
  bots.get('PdB').end()
  bots.delete('PdB')
  await sleep(2500)
  await connect('PdB')
  await run('op PdB', 200)
  await run(`tp PdB ${CX + 20} ${Y} ${CZ + 6}`, 300)
  await sleep(2000)
  check('a hidden player who disconnects is visible again when they return', canSee(F, 'PdB'))
  await run('pd group prepare', 1500)
  await sleep(1200)
  check('prepared and hidden again after the restore test', !canSee(F, 'PdA') && !canSee(F, 'PdC'))
  out = await run('pd group delete NULL', 250)
  for (const n of ['PdA', 'PdB', 'PdC', 'PdD', 'PdE']) await run(`tp ${n} ${CX + 20} ${Y} ${CZ + 4}`, 60)
  await sleep(1500)
  check('deleting the group shows its hidden players again and deletes no portal', canSee(F, 'PdA') && canSee(F, 'PdC') && overworld().portals.length === 5, last(out))
  out = await run('pd group list')
  check('the group is gone', !/NULL/.test(out))
  await run('pd group create NULL')
  for (const n of ['PdA', 'PdB', 'PdC', 'PdD', 'PdE']) await run(`pd group assign-to-group ${n} NULL`, 150)
  for (const [n, p] of [['PdA', 'EntranceA'], ['PdB', 'EntranceB'], ['PdC', 'EntranceC'], ['PdD', 'EntranceD'], ['PdE', 'EntranceE']]) await run(`pd group link-to-portal ${n} ${p}`, 150)

  // offline players
  bots.get('PdC').end()
  bots.delete('PdC')
  await sleep(2500)
  await run('pd group visibility-in-portal true')
  out = await run('pd group prepare', 1500)
  check('an offline member is skipped with a message', /Skipped, not online: PdC/.test(out) && /4 players are standing/.test(out), out.split('\n').slice(-3).join(' | ').slice(0, 200))
  await connect('PdC')
  await run('op PdC', 200)

  // ---- Nether pairing with portals 14 blocks apart
  log('nether pairing')
  // observer F walks into all four portals one by one
  const trips = {}
  const standIn = async (bot, portalName) => {
    await run(`execute in minecraft:overworld run tp ${bot.username} ${CX + 20} ${Y} ${CZ + 4}`, 300)
    await sleep(17000)
    const p = byName(portalName)
    await run(`execute in minecraft:overworld run tp ${bot.username} ${cxOf(p)} ${p.y + 1} ${czOf(p)}`, 300)
  }
  const goThrough = async (bot, portalName) => {
    await standIn(bot, portalName)
    const arrived = await waitFor(() => /nether/i.test(dimension(bot)), 70000, 500)
    await sleep(3000)
    return arrived ? { x: bot.entity.position.x, y: bot.entity.position.y, z: bot.entity.position.z } : null
  }
  const comeBack = async (bot, from) => {
    // step out of the Nether portal and back in
    await run(`execute in minecraft:the_nether run tp ${bot.username} ${from.x + 6} ${from.y} ${from.z + 6}`, 800)
    await sleep(1500)
    await run(`execute in minecraft:the_nether run tp ${bot.username} ${from.x} ${from.y} ${from.z}`, 300)
    const back = await waitFor(() => /overworld/i.test(dimension(bot)), 40000, 500)
    await sleep(2500)
    return back ? { x: bot.entity.position.x, y: bot.entity.position.y, z: bot.entity.position.z } : null
  }
  // F is not in the group, so vanilla-style travel applies, with our pairing
  for (const portalName of ['EntranceA', 'EntranceB', 'EntranceC', 'EntranceD']) {
    const arrival = await goThrough(F, portalName)
    trips[portalName] = { arrival }
    check(`${portalName}: a player who is not in the group travels to the Nether`, !!arrival, arrival ? `${arrival.x.toFixed(1)} ${arrival.y.toFixed(1)} ${arrival.z.toFixed(1)}` : 'did not travel')
    if (!arrival) continue
    const partner = partnerOf(portalName)
    check(`${portalName}: a Nether partner was created and saved`, !!partner, partner ? `${partner.x} ${partner.y} ${partner.z}` : '')
    if (partner) {
      check(`${portalName}: the player arrives in that partner`, dist(arrival, { x: cxOf(partner), z: czOf(partner) }) < 2.5 && Math.abs(arrival.y - (partner.y + 1)) < 2.5, `${dist(arrival, { x: cxOf(partner), z: czOf(partner) }).toFixed(2)} blocks away`)
      trips[portalName].partner = partner
    }
    const back = await comeBack(F, arrival)
    const target = byName(portalName)
    check(`${portalName}: the way back leads to ${portalName}, not another portal`, !!back && dist(back, { x: cxOf(target), z: czOf(target) }) < 4, back ? `${dist(back, { x: cxOf(target), z: czOf(target) }).toFixed(2)} blocks from it` : 'did not return')
    await sleep(1000)
  }
  const ps = Object.values(trips).map(t => t.partner).filter(Boolean)
  check('four portals, four different Nether portals', ps.length === 4 && new Set(ps.map(p => p.id)).size === 4, ps.map(p => p.id).join(','))
  let apart = true
  for (let i = 0; i < ps.length; i++) for (let j = i + 1; j < ps.length; j++) apart = apart && dist({ x: cxOf(ps[i]), z: czOf(ps[i]) }, { x: cxOf(ps[j]), z: czOf(ps[j]) }) >= 5
  check('the Nether portals do not overlap or touch', apart)
  const allOk = []
  for (const p of ps) allOk.push(await portalComplete(p, 'minecraft:the_nether'))
  check('each Nether portal is a complete frame with an active interior', allOk.length === 4 && allOk.every(Boolean))
  check('the pairing is saved to disk', Object.keys(pairs()).length === 4, JSON.stringify(pairs()))

  // ---- nether-travel true for a linked member
  log('nether travel on')
  const Bbot = bots.get('PdB')
  await run('pd group prepare', 1500)
  await sleep(1500)
  await run(`tp PdD ${CX + 20} ${Y} ${CZ + 4}`, 100)
  await run('pd group prepare', 1500)
  const stayedB = await waitFor(() => /nether/i.test(dimension(Bbot)), 12000, 500)
  check('with nether-travel false the linked member still stays', !stayedB)
  out = await run('pd group nether-travel true')
  check('nether-travel true is accepted', /can now travel to the Nether/.test(out), last(out))
  const wentB = await waitFor(() => /nether/i.test(dimension(Bbot)), 60000, 500)
  const pb = partnerOf('EntranceB')
  await sleep(2500)
  check('with nether-travel true the same member now travels, to their own portal\'s partner', wentB && !!pb && dist(Bbot.entity.position, { x: cxOf(pb), z: czOf(pb) }) < 2.5, wentB ? `${dist(Bbot.entity.position, { x: cxOf(pb), z: czOf(pb) }).toFixed(2)} blocks from the partner` : 'did not travel')
  await run('execute in minecraft:overworld run tp PdB ' + (CX + 20) + ' ' + Y + ' ' + (CZ + 6), 500)
  await run('pd group nether-travel false')

  // ---- an unrelated vanilla portal is left alone
  log('vanilla portal')
  const VX = CX - 240
  const VZ = CZ + 12
  await run(`forceload add ${VX - 8} ${VZ - 8} ${VX + 8} ${VZ + 8}`, 1500)
  await run(`fill ${VX - 2} ${Y - 1} ${VZ - 6} ${VX + 6} ${Y - 1} ${VZ + 6} stone`, 800)
  await run(`fill ${VX} ${Y} ${VZ} ${VX + 3} ${Y + 4} ${VZ} obsidian`, 300)
  await run(`fill ${VX + 1} ${Y + 1} ${VZ} ${VX + 2} ${Y + 3} ${VZ} minecraft:nether_portal[axis=x]`, 500)
  const pairsBefore = JSON.stringify(pairs())
  await run(`tp PdD ${CX + 20} ${Y} ${CZ + 4}`, 300)
  await sleep(17000)
  await run(`tp PdD ${VX + 2} ${Y + 1} ${VZ + 0.5}`, 300)
  const vtravel = await waitFor(() => /nether/i.test(dimension(bots.get('PdD'))), 70000, 500)
  await sleep(2000)
  check('an unrelated vanilla portal still sends players to the Nether', vtravel)
  check('and the pairing file is unchanged by it', JSON.stringify(pairs()) === pairsBefore)
  const dpos = bots.get('PdD').entity.position
  check('they arrive near one eighth of its coordinates (vanilla behaviour)', vtravel && Math.abs(dpos.x - VX / 8) < 24 && Math.abs(dpos.z - VZ / 8) < 24, `${dpos.x.toFixed(0)} ${dpos.z.toFixed(0)} vs ${(VX / 8).toFixed(0)} ${(VZ / 8).toFixed(0)}`)
  await run(`execute in minecraft:overworld run tp PdD ${CX + 20} ${Y} ${CZ + 6}`, 500)

  // ---- TNT still works through the portals
  await run('pd tnt mode safe')
  await run('pd tnt launch EntranceA', 900)
  check('tnt launch from a portal still works', /passed/i.test(await run('execute if entity @e[type=minecraft:tnt_minecart]', 500)))
  await run('pd tnt clear', 400)

  // ---- removal restores only the Nether blocks of that pair
  log('removal')
  const netherRecs = readBlocks(path.join(PLUGIN_DIR, 'nether', 'links.blocks'))
  const pa = partnerOf('EntranceA')
  const pbn = partnerOf('EntranceB')
  const mineOf = p => netherRecs.filter(r => r.x >= Math.min(p.x, p.x + (p.axis === 'X' ? 3 : 0)) - 4 && r.x <= p.x + 7 && r.z >= p.z - 4 && r.z <= p.z + 7 && r.y >= p.y && r.y <= p.y + 4)
  await run('execute in minecraft:the_nether run setblock ' + (pbn.x + 12) + ' ' + (pbn.y + 6) + ' ' + pbn.z + ' minecraft:diamond_block', 500)
  const idsBefore = { A: byName('EntranceA').id, B: byName('EntranceB').id, C: byName('EntranceC').id, D: byName('EntranceD').id }
  const pairsBeforeRemoval = Object.keys(pairs()).length
  const rmOut = await runUntil('pd remove EntranceB', /Portal \w+ removed/, 60000)
  await sleep(5000)
  check('removing an Overworld portal reports success', /Portal EntranceB removed/.test(rmOut))
  check('its pairing is gone and the others remain', !pairs()[`default/${idsBefore.B}`] && [idsBefore.A, idsBefore.C, idsBefore.D].every(i => pairs()[`default/${i}`]) && Object.keys(pairs()).length === pairsBeforeRemoval - 1, JSON.stringify(pairs()))
  const pairsAfterRemoval = Object.keys(pairs()).length
  const pbRecs = netherRecs.filter(r => pbn && r.x >= pbn.x - 1 && r.x <= pbn.x + 4 && r.z >= pbn.z - 4 && r.z <= pbn.z + 4 && r.y >= pbn.y && r.y <= pbn.y + 4)
  check('every Nether block of that pair is back to what it was', pbRecs.length > 0 && (await wrongRestored(pbRecs, 'minecraft:the_nether')) === 0, pbRecs.length + ' blocks')
  const dia = await run('execute in minecraft:the_nether if block ' + (pbn.x + 12) + ' ' + (pbn.y + 6) + ' ' + pbn.z + ' minecraft:diamond_block run say DIAMOND_OK', 600)
  check('a block placed near it that PortalDirector never touched is still there', /DIAMOND_OK/.test(dia))
  check('the other Nether portals are untouched', (await portalComplete(pa, 'minecraft:the_nether')) && (await portalComplete(partnerOf('EntranceC'), 'minecraft:the_nether')))

  // ---- restart: persistence and migration of old data
  log('restart and migration')
  await run('pd group create Legacy')
  await run('pd group assign-to-group PdE Legacy')
  bots.forEach(b => { try { b.end() } catch {} })
  bots.clear()
  await sleep(1500)
  const grpBefore = fs.readFileSync(path.join(PLUGIN_DIR, 'groups.yml'), 'utf8')
  const code = await srv.stop()
  check('server stopped gracefully', code === 0, 'exit code ' + code)
  check('groups were saved with their links and switches', /nether-travel: false/.test(grpBefore) && /visibility-in-portal: true/.test(grpBefore) && /visibility-in-portal-delay: 0\.35/.test(grpBefore) && /portals:/.test(grpBefore))
  // old data: a group without the new keys, and an assignment inside the scene file
  const cEntrance = byName('EntranceC')
  const uuidA = offlineUuid('PdA')
  const uuidC = offlineUuid('PdC')
  fs.appendFileSync(path.join(PLUGIN_DIR, 'groups.yml'), `  OldCrew:\n    join-block: EMERALD_BLOCK\n    members:\n      ${uuidA}: PdA\n      ${uuidC}: PdC\n`)
  const sceneYml = path.join(PLUGIN_DIR, 'scenes', 'default.yml')
  fs.appendFileSync(sceneYml, `\nassignments:\n  oldcrew:\n    ${cEntrance.id}: ${uuidC}\n`)
  srv.lines = []
  srv.start()
  await srv.waitForLine(/Done \(/, 300000, 0, 'restart')
  await sleep(5000)
  out = await run('pd group info OldCrew', 2000)
  check('an old group loads with the default switches', /OldCrew . 2 players/.test(out) && /Nether travel off, visibility in portal on/.test(out), last(out))
  check('an old scene-file assignment became a link by portal name', /PdC .* EntranceC/.test(out), out.split('\n').slice(-4).join(' | ').slice(0, 250))
  check('and the scene file no longer holds assignments after it was saved', !/^assignments:/m.test(fs.readFileSync(sceneYml, 'utf8')))
  out = await run('pd group info', 2000)
  check('groups and links survived the restart', /NULL . 5 players/.test(out) && /PdA .* EntranceA/.test(out) && /PdD .* EntranceD/.test(out), '')
  check('the Nether pairing survived the restart', Object.keys(pairs()).length === pairsAfterRemoval, JSON.stringify(pairs()))
  const F2 = await connect('PdF')
  await run('op PdF', 200)
  await sleep(8000)
  await run(`tp PdF ${CX + 20} ${Y} ${CZ + 4}`, 300)
  await sleep(17000)
  check('after the restart portal EntranceA is still complete', await portalComplete(byName('EntranceA'), 'minecraft:overworld'))
  const tripA = await (async () => {
    await run(`execute in minecraft:overworld run tp PdF ${cxOf(byName('EntranceA'))} ${byName('EntranceA').y + 1} ${czOf(byName('EntranceA'))}`, 300)
    const ok = await waitFor(() => /nether/i.test(dimension(F2)), 90000, 500)
    await sleep(3000)
    if (!ok) console.log('  (no trip: ' + (await run(`execute at PdF if block ~ ~ ~ minecraft:nether_portal run say STANDS_IN_PORTAL`, 400)).trim().split('\n').pop() + ' at ' + F2.entity.position + ')')
    return ok ? F2.entity.position : null
  })()
  const pa2 = partnerOf('EntranceA')
  check('after the restart the same portal still leads to the same Nether partner', !!tripA && !!pa2 && dist(tripA, { x: cxOf(pa2), z: czOf(pa2) }) < 2.5 && JSON.stringify(pa2) === JSON.stringify(pa), tripA ? dist(tripA, { x: cxOf(pa2), z: czOf(pa2) }).toFixed(2) + ' blocks' : 'no trip')
  await run('execute in minecraft:overworld run tp PdF 1000 200 1000', 300)

  // ---- clear removes every pair and restores the Nether blocks
  const recsAll = readBlocks(path.join(PLUGIN_DIR, 'nether', 'links.blocks'))
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 180000)
  await sleep(8000)
  check('pd clear removes every pairing', Object.keys(pairs()).length === 0, JSON.stringify(pairs()))
  check('and restores the Nether blocks it built', recsAll.length > 0 && (await wrongRestored(recsAll, 'minecraft:the_nether')) === 0, recsAll.length + ' blocks')
  check('group links to the cleared portals are gone but the groups stay', !/EntranceA/.test(await run('pd group info', 1500)))
  bots.forEach(b => { try { b.end() } catch {} })
}

main().catch(e => {
  console.error('[groups-test] ERROR:', e.stack || e.message)
  checks.push({ name: 'harness ran to the end', ok: false, detail: e.message })
}).finally(async () => {
  bots.forEach(b => { try { b.end() } catch {} })
  await srv.stop().catch(() => {})
  fs.writeFileSync(path.join(REPORT, 'groups-latest.json'), JSON.stringify({ when: new Date().toISOString(), checks }, null, 2))
  fs.writeFileSync(path.join(REPORT, 'groups-console.log'), srv.lines.join('\n'))
  const failed = checks.filter(c => !c.ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
})
