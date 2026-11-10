'use strict'
/*
 * Player targets live test on an isolated Paper server, with real bot clients: /pd create portal <name> <player> <direction>,
 * /pd create ... around <player> and a sequence run on a player, while the player stands still and while they are running.
 * The portal must go where the player is when the command is processed (not where they were earlier, not where they end up),
 * in their world, whatever center is pinned. Also names that are not case sensitive, refusals, and tab completion. Nothing here
 * is visual verification.
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
const log = (...a) => console.log('[target-test]', ...a)
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

const body = l => l.replace(/^\[[^\]]*\]: /, '')
async function run (cmd, settle = 600) {
  const from = srv.lines.length
  srv.command(cmd)
  await sleep(settle)
  return srv.lines.slice(from).map(body).join('\n')
}
/** Runs a command and returns its output once a line matching {@code end} has appeared. */
async function runBlock (cmd, end, timeout = 30000) {
  const from = srv.lines.length
  srv.command(cmd)
  await srv.waitForLine(end, timeout, from, String(end))
  await sleep(250)
  return srv.lines.slice(from).map(body).join('\n')
}
async function runUntil (cmd, regex, timeout = 120000) {
  const from = srv.lines.length
  srv.command(cmd)
  return body(await srv.waitForLine(regex, timeout, from, String(regex)))
}
async function waitFor (fn, ms, step = 250) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await fn()) return true; await sleep(step) }
  return false
}
const last = out => out.trim().split('\n').pop().slice(0, 240)

function readScene () {
  const f = path.join(PLUGIN_DIR, 'scenes', 'default.yml')
  if (!fs.existsSync(f)) return []
  const out = []
  let cur = null
  let on = false
  for (const l of fs.readFileSync(f, 'utf8').split(/\r?\n/)) {
    if (/^portals:/.test(l)) { on = true; continue }
    if (/^\S/.test(l) && !/^- /.test(l)) on = false
    if (!on) continue
    let m = l.match(/^- id: (\d+)/)
    if (m) { cur = { id: +m[1] }; out.push(cur); continue }
    m = l.match(/^ {2}(\w+): (.+)$/)
    if (m && cur) cur[m[1]] = /^-?\d+$/.test(m[2]) ? +m[2] : m[2] === 'true' ? true : m[2] === 'false' ? false : m[2]
  }
  return out
}
/** Anchor: the lower bottom block inside the doorway. */
const anchor = p => (p.axis === 'X' ? { x: p.x + 1, y: p.y + 1, z: p.z } : { x: p.x, y: p.y + 1, z: p.z + 1 })
const centerOf = p => ({ x: p.axis === 'X' ? p.x + 2 : p.x + 0.5, z: p.axis === 'Z' ? p.z + 2 : p.z + 0.5 })
async function serverPos (name) {
  const from = srv.lines.length
  srv.command('data get entity ' + name + ' Pos')
  try {
    const line = body(await srv.waitForLine(new RegExp(name + ' has the following entity data: '), 8000, from, 'pos'))
    const m = line.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
    return m ? { x: +m[1], y: +m[2], z: +m[3] } : null
  } catch (e) { return null }
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

async function main () {
  prepareServer()
  fs.mkdirSync(REPORT, { recursive: true })
  srv.start()
  await srv.waitForLine(/Done \(/, 300000, 0, 'server start')
  check('plugin enabled', srv.lines.some(l => /PortalDirector ready/.test(l)))
  const found = await runUntil('execute in minecraft:overworld run locate biome minecraft:plains', /\[(-?\d+), (?:~|-?\d+), (-?\d+)\]/, 120000)
  const loc = found.match(/\[(-?\d+), (?:~|-?\d+), (-?\d+)\]/)
  const X0 = +loc[1] + 40
  const Z0 = +loc[2] + 40
  const Y = 150
  await runUntil(`forceload add ${X0 - 70} ${Z0 - 40} ${X0 + 110} ${Z0 + 40}`, /Marked \d+ chunks/, 600000)
  await sleep(3000)
  // a long stone road in the sky for the runner, wide enough for portals to stand beside it
  await run(`fill ${X0 - 10} ${Y - 1} ${Z0 - 3} ${X0 + 100} ${Y - 1} ${Z0 + 3} stone`, 3000)

  await connect('Runner')
  await connect('Obs')
  await run('op Runner', 150)
  await run('op Obs', 150)
  const R = bots.get('Runner')
  const OBS = bots.get('Obs')
  await run(`execute in minecraft:overworld run tp Obs ${X0 - 5} ${Y} ${Z0 + 20}`, 300)

  // ---- standing still
  log('standing player')
  await run(`execute in minecraft:overworld run tp Runner ${X0 + 0.5} ${Y} ${Z0 + 0.5}`, 800)
  const stand = await serverPos('Runner')
  let out = await runUntil('pd create portal Still Runner north', /Portal Still built|no online player|replace|Too close|outside/, 60000)
  check('a portal is built where a standing player is', /Portal Still built/.test(out), out)
  await sleep(1500)
  let p = readScene().find(x => x.name === 'Still')
  const a = p && anchor(p)
  check('its anchor is the block the player stood in', a && a.x === Math.floor(stand.x) && a.y === Math.floor(stand.y) && a.z === Math.floor(stand.z), `${JSON.stringify(a)} vs ${JSON.stringify(stand)}`)
  check('and it faces north', p && /NORTH/i.test(p.facing))
  out = await runBlock('pd list', /Still/)
  check('/pd list shows it', /Still/.test(out), last(out))
  await run('pd remove Still', 2500)

  // ---- a name that is not case sensitive, and refusals
  out = await runUntil('pd create portal Upper RUNNER east', /Portal Upper built|no online player/, 60000)
  check('the name is not case sensitive', /Portal Upper built/.test(out), out)
  await sleep(1000)
  await run('pd remove Upper', 2500)
  out = await run('pd create portal Ghost Nobody north', 600)
  check('an offline player is refused and who is online is listed', /no online player called Nobody/.test(out) && /Runner/.test(out), last(out))
  check('and nothing was built', !readScene().some(x => x.name === 'Ghost'))
  out = await run('pd create portal Ghost Runner', 600)
  check('a missing direction is explained', /Which way should it face/.test(out), last(out))
  out = await run('pd create portal Ghost Runner north world', 600)
  check('a world name with a player is refused (the player decides the world)', /world name is not needed/.test(out), last(out))
  out = await run('pd create portal Ghost 1a north', 600)
  check('a typo is a clear message, not a crash', /no online player called 1a/.test(out), last(out))
  out = await run(`pd create portal Coords ${Math.floor(stand.x) + 20} ${Math.floor(stand.y)} ${Math.floor(stand.z)} north world`, 1500)
  await sleep(1500)
  check('here and x y z still work next to it', readScene().some(x => x.name === 'Coords'), last(out))
  await run('pd remove Coords', 2500)

  // ---- a running player
  log('running player')
  await run(`execute in minecraft:overworld run tp Runner ${X0 + 1.5} ${Y} ${Z0 + 0.5} 270 0`, 800)
  R.setControlState('sprint', true)
  R.setControlState('forward', true)
  R.look(-Math.PI / 2, 0, true) // east
  await sleep(1200)
  const seen = []
  for (let i = 0; i < 3; i++) {
    const before = await serverPos('Runner')
    const from = srv.lines.length
    srv.command(`pd create portal Run${i} Runner east`)
    const after = await serverPos('Runner')
    await srv.waitForLine(new RegExp(`Portal Run${i} built|Too close|replace`), 60000, from, 'run ' + i)
    await sleep(600)
    const q = readScene().find(x => x.name === 'Run' + i)
    seen.push({ i, before, after, anchor: q && anchor(q), later: await serverPos('Runner') })
    await sleep(900)
  }
  R.setControlState('forward', false)
  R.setControlState('sprint', false)
  let betweenOk = true
  const detail = []
  for (const s of seen) {
    if (!s.anchor || !s.before || !s.after) { betweenOk = false; detail.push('missing ' + s.i); continue }
    const lo = Math.floor(Math.min(s.before.x, s.after.x)) - 1
    const hi = Math.floor(Math.max(s.before.x, s.after.x)) + 3
    detail.push(`run${s.i}: portal x ${s.anchor.x}, player ${s.before.x.toFixed(1)} -> ${s.after.x.toFixed(1)} -> ${s.later.x.toFixed(1)}`)
    if (s.anchor.x < lo || s.anchor.x > hi) betweenOk = false
    if (Math.abs(s.anchor.z - Math.floor(s.before.z)) > 1) betweenOk = false
    if (!(s.later.x > s.anchor.x)) betweenOk = false // the runner ran on past it
  }
  check('each portal went where the runner was when the command ran, not where they started or ended', betweenOk, detail.join(' | '))
  const xs = seen.map(s => s.anchor && s.anchor.x)
  check('the three portals are in different places along the path (the player was not pinned)', new Set(xs).size === 3 && xs[0] < xs[1] && xs[1] < xs[2], xs.join(' '))
  check('the runner really kept moving afterwards', seen[2].later && seen[2].later.x - seen[0].before.x > 8, `${seen[0].before && seen[0].before.x.toFixed(1)} -> ${seen[2].later && seen[2].later.x.toFixed(1)}`)
  for (let i = 0; i < 3; i++) await run('pd remove Run' + i, 1800)

  // ---- a formation around a player, whatever center is pinned
  log('formation around a player')
  const farX = X0 + 80
  await run(`execute in minecraft:overworld run tp Runner ${farX} ${Y} ${Z0}`, 800)
  await run(`pd set center ${X0 - 5} ${Y} ${Z0 - 30} world`)
  await run('pd set layout random')
  await run('pd set mode air')
  await run('pd set radius 15')
  await run('pd set altitude-min 5')
  await run('pd set altitude-max 25')
  out = await runUntil('pd create 4 around Runner', /Built \d+ of \d+|went wrong|There are already|Stopped/, 120000)
  check('create around a player builds the portals', /Built 4 of 4/.test(out), out)
  await sleep(1500)
  const around = readScene()
  const rpos = await serverPos('Runner')
  const dists = around.map(q => Math.max(Math.abs(centerOf(q).x - rpos.x), Math.abs(centerOf(q).z - rpos.z)))
  check('all four are within the radius of the player, not of the pinned center', around.length === 4 && dists.every(d => d <= 17), dists.map(d => d.toFixed(0)).join(' '))
  check('and none is anywhere near the pinned center', around.every(q => centerOf(q).x > X0 + 40))
  out = await run('pd settings', 800)
  check('the pinned center was not changed by it', new RegExp(`Center: ${X0 - 5} ${Y} ${Z0 - 30}`).test(out) || /pinned/.test(out), last(out))
  await run('pd clear', 5000)
  await waitFor(() => readScene().length === 0, 30000)
  out = await run('pd create 4 around Nobody', 600)
  check('around an offline player is refused', /no online player called Nobody/.test(out), last(out))
  out = await run('pd create 4 around', 600)
  check('around without a name is explained', /Around whom/.test(out), last(out))

  // ---- a sequence on a player
  log('sequence on a player')
  await run('pd set center reset')
  await run(`execute in minecraft:overworld run tp Runner ${X0 + 40} ${Y} ${Z0}`, 800)
  await run('pd sequence tnt-strike set portals 3')
  await run('pd sequence tnt-strike set radius 25')
  await run('pd sequence tnt-strike set tnt-min 1')
  await run('pd sequence tnt-strike set tnt-max 1')
  const before = await serverPos('Runner')
  out = await runUntil('pd sequence tnt-strike run Runner', /TNT Strike (done|could not|failed)/, 180000)
  check('a sequence runs on a player', /TNT Strike done: 3 portals/.test(out), out)
  const ts = readScene().filter(q => /^tnt-strike/.test(q.name))
  const dd = ts.map(q => Math.max(Math.abs(centerOf(q).x - before.x), Math.abs(centerOf(q).z - before.z)))
  check('its portals float around the player', ts.length === 3 && dd.every(d => d <= 27), dd.map(d => d.toFixed(0)).join(' '))
  out = await run('pd sequence tnt-strike run Nobody', 600)
  check('and refuses an offline player', /no online player called Nobody/.test(out), last(out))
  await run('pd tnt clear')
  await run('pd clear', 5000)

  // ---- tab completion
  log('tab completion')
  try {
    let t = await tab(OBS, '/pd create portal Test ')
    check('create portal suggests here, the players online and coordinates', t.includes('here') && t.includes('Runner') && t.includes('Obs'), t.join(' '))
    check('in A to Z order', isSorted(t.filter(x => !/^\d|^~/.test(x))), t.join(' '))
    t = await tab(OBS, '/pd create portal Test Runner ')
    check('then the four directions', JSON.stringify(t) === JSON.stringify(['east', 'north', 'south', 'west']), t.join(' '))
    t = await tab(OBS, '/pd create 3 ')
    check('create suggests around', t.includes('around'), t.join(' '))
    t = await tab(OBS, '/pd create 3 around ')
    check('and then the players online', JSON.stringify(t) === JSON.stringify(['Obs', 'Runner']), t.join(' '))
    t = await tab(OBS, '/pd sequence sky-army run ')
    check('a sequence run suggests here, the players and coordinates', t.includes('here') && t.includes('Runner'), t.join(' '))
    t = await tab(OBS, '/pd sequence nether-arrival run Runner ')
    check('nether-arrival then suggests the directions', JSON.stringify(t) === JSON.stringify(['east', 'north', 'south', 'west']), t.join(' '))
  } catch (e) { check('tab completion', false, e.message) }

  out = await run('pd help portals', 900)
  check('help shows the player form', /<player> <direction>/.test(out), last(out))

  const errors = srv.lines.filter(l => /(ERROR|Exception)/.test(l) && /PortalDirector|portaldirector/i.test(l))
  check('no plugin errors in the console', errors.length === 0, errors.slice(0, 2).join(' | '))
  fs.writeFileSync(path.join(REPORT, 'target-latest.json'), JSON.stringify({ checks }, null, 2))
  fs.writeFileSync(path.join(REPORT, 'target-console.log'), srv.lines.join('\n'))
  for (const b of bots.values()) { try { b.end() } catch (e) { /* ignore */ } }
  await sleep(1000)
  await srv.stop().catch(() => {})
  const failed = checks.filter(c => !c.ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
}

main().catch(async e => {
  console.error(e)
  try { fs.writeFileSync(path.join(REPORT, 'target-console.log'), srv.lines.join('\n')) } catch (x) { /* ignore */ }
  for (const b of bots.values()) { try { b.end() } catch (x) { /* ignore */ } }
  await srv.stop().catch(() => {})
  process.exit(2)
})
