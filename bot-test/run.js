'use strict'
/*
 * PortalDirector live test (lightweight).
 *
 * Starts the isolated localhost-only Paper server (..\portal-director-server), then:
 *   1. generates scenes without any bots (ground, air, mixed; random, grid, circle, wave; 30 portals) and checks
 *      every block on the server, spacing and removal/restoration,
 *   2. places portals by hand at exact coordinates in all four directions,
 *   3. connects 4 Mineflayer bots for joining, assignment, entrances, reconnects and cancellation,
 *   4. launches TNT minecarts from the manual portals,
 *   5. restarts the server and checks persistence, rebuild and safe removal.
 * Bots cannot see anything; see README for what this does not prove.
 */
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const mineflayer = require('@wp2508/mineflayer')
const { TestServer } = require('./lib/server')

const PORT = +(process.env.PD_PORT || 25590)
const HOST = '127.0.0.1'
const SERVER_DIR = path.resolve(process.env.PD_SERVER_DIR || path.join(__dirname, '..', '..', 'portal-director-server'))
const PLUGIN_DIR = path.join(SERVER_DIR, 'plugins', 'PortalDirector')
const REPORT = path.join(__dirname, 'reports')
const NAMES = ['PdBot1', 'PdBot2', 'PdBot3', 'PdBot4']

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log('[pd-test]', ...a)
const checks = []
function check (name, ok, detail = '') {
  checks.push({ name, ok: !!ok, detail })
  console.log(`${ok ? '  PASS' : '  FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`)
}

const srv = new TestServer({ dir: SERVER_DIR, jar: 'paper-26.3-231.jar' })

function offlineUuid (name) {
  const b = crypto.createHash('md5').update('OfflinePlayer:' + name).digest()
  b[6] = (b[6] & 0x0f) | 0x30
  b[8] = (b[8] & 0x3f) | 0x80
  const h = b.toString('hex')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

function prepareServer () {
  const text = fs.readFileSync(path.join(SERVER_DIR, 'server.properties'), 'utf8')
  const get = k => (text.match(new RegExp('^' + k + '=(.*)$', 'm')) || [])[1]
  if (get('server-ip') !== HOST) throw new Error('Refusing to run: server-ip must be 127.0.0.1')
  if (get('server-port') !== String(PORT)) throw new Error('Refusing to run: server-port must be ' + PORT)
  fs.rmSync(PLUGIN_DIR, { recursive: true, force: true })
  fs.rmSync(path.join(SERVER_DIR, 'world'), { recursive: true, force: true })
  fs.writeFileSync(path.join(SERVER_DIR, 'ops.json'), JSON.stringify(NAMES.map(n => ({ uuid: offlineUuid(n), name: n, level: 4, bypassesPlayerLimit: false })), null, 2))
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

async function count (selector) {
  const m = (await run(`execute if entity ${selector}`, 450)).match(/count: (\d+)/i)
  return m ? +m[1] : 0
}

function readScene (name) {
  const lines = fs.readFileSync(path.join(PLUGIN_DIR, 'scenes', name + '.yml'), 'utf8').split(/\r?\n/)
  const scene = { portals: [], assignments: {} }
  let section = ''
  let group = ''
  let cur = null
  for (const l of lines) {
    if (/^portals:/.test(l)) { section = 'portals'; continue }
    if (/^assignments:/.test(l)) { section = 'assignments'; continue }
    if (section === 'portals') {
      let m = l.match(/^- id: (\d+)/)
      if (m) { cur = { id: +m[1] }; scene.portals.push(cur); continue }
      m = l.match(/^ {2}(\w+): (.+)$/)
      if (m && cur) cur[m[1]] = m[2] === 'true' ? true : m[2] === 'false' ? false : /^-?\d+$/.test(m[2]) ? +m[2] : m[2]
    } else if (section === 'assignments') {
      let m = l.match(/^ {2}(\S+):$/)
      if (m) { group = m[1].replace(/'/g, ''); scene.assignments[group] = {}; continue }
      m = l.match(/^ {4}'?(\d+)'?: (\S+)/)
      if (m && group) scene.assignments[group][+m[1]] = m[2]
    }
  }
  return scene
}

function geom (p) {
  const stepX = p.facing === 'EAST' ? 1 : p.facing === 'WEST' ? -1 : 0
  const stepZ = p.facing === 'SOUTH' ? 1 : p.facing === 'NORTH' ? -1 : 0
  return {
    stepX,
    stepZ,
    cx: p.axis === 'X' ? p.x + 2 : p.x + 0.5,
    cz: p.axis === 'Z' ? p.z + 2 : p.z + 0.5,
    standY: p.y + 1,
    box: {
      minX: p.axis === 'X' ? p.x : p.x - (stepX < 0 ? 3 : 1),
      maxX: p.axis === 'X' ? p.x + 3 : p.x + (stepX > 0 ? 3 : 1),
      minZ: p.axis === 'Z' ? p.z : p.z - (stepZ < 0 ? 3 : 1),
      maxZ: p.axis === 'Z' ? p.z + 3 : p.z + (stepZ > 0 ? 3 : 1),
      minY: p.y,
      maxY: p.y + 4
    }
  }
}

function separated (a, b, gap) {
  const A = geom(a).box
  const B = geom(b).box
  return A.maxX + gap < B.minX || B.maxX + gap < A.minX || A.maxZ + gap < B.minZ || B.maxZ + gap < A.minZ ||
    A.maxY + gap < B.minY || B.maxY + gap < A.minY
}

function clearMarks () { srv.lines = srv.lines.filter(l => !/\[Server\] (BAD|RBAD|SUPPORT)/.test(l)) }

async function flush (tag) {
  const from = srv.lines.length
  srv.command('say ' + tag)
  await srv.waitForLine(new RegExp(tag), 120000, from)
  await sleep(250)
}

/** Asks the server for every frame and interior block of every portal; returns the number of wrong blocks. */
async function badBlocks (portals) {
  clearMarks()
  for (const p of portals) {
    for (let i = 0; i < 4; i++) {
      for (let j = 0; j < 5; j++) {
        const x = p.axis === 'X' ? p.x + i : p.x
        const z = p.axis === 'Z' ? p.z + i : p.z
        const frame = i === 0 || i === 3 || j === 0 || j === 4
        const want = frame ? 'minecraft:obsidian' : `minecraft:nether_portal[axis=${p.axis.toLowerCase()}]`
        srv.command(`execute unless block ${x} ${p.y + j} ${z} ${want} run say BAD ${p.id} ${x} ${p.y + j} ${z}`)
      }
    }
  }
  await flush('CHECKDONE' + Date.now())
  return srv.lines.filter(l => /\[Server\] BAD \d+/.test(l)).length
}

/** Ground portals must have something under their floor. */
async function airUnderGround (portals) {
  clearMarks()
  for (const p of portals.filter(q => !q.floating)) {
    for (let i = 0; i < 4; i++) {
      const x = p.axis === 'X' ? p.x + i : p.x
      const z = p.axis === 'Z' ? p.z + i : p.z
      srv.command(`execute if block ${x} ${p.y - 1} ${z} minecraft:air run say SUPPORT ${p.id}`)
    }
  }
  await flush('SUPPORTDONE' + Date.now())
  return srv.lines.filter(l => /\[Server\] SUPPORT \d+/.test(l)).length
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

async function wrongRestored (recs, skip = []) {
  clearMarks()
  for (const r of recs) {
    if (skip.some(s => s.x === r.x && s.y === r.y && s.z === r.z)) continue
    srv.command(`execute unless block ${r.x} ${r.y} ${r.z} ${r.original} run say RBAD ${r.x} ${r.y} ${r.z}`)
  }
  await flush('RCHECKDONE' + Date.now())
  return srv.lines.filter(l => /\[Server\] RBAD/.test(l)).length
}

function spacingOk (portals, gap) {
  for (let i = 0; i < portals.length; i++) for (let j = i + 1; j < portals.length; j++) if (!separated(portals[i], portals[j], gap)) return false
  return true
}

// scenes without bots

async function sceneCase (label, setup, count, expect) {
  const name = label.replace(/[^a-z0-9]/g, '-')
  await run(`pd scene new ${name}`, 300)
  for (const s of setup) await run(s, 150)
  const t0 = Date.now()
  const hit = await runUntil(`pd generate ${count}`, /Done: \d+ of \d+ portals placed|Generation stopped|already has/, 180000)
  const ms = Date.now() - t0
  const sc = readScene(name)
  const placed = sc.portals.length
  check(`${label}: generated ${placed}/${count} portals in ${ms} ms`, placed >= Math.floor(count * 0.7) && /Done/.test(hit), hit.replace(/^\[[^\]]*\] /, ''))
  check(`${label}: ids unique from 1`, new Set(sc.portals.map(p => p.id)).size === placed && Math.min(...sc.portals.map(p => p.id)) === 1)
  check(`${label}: spacing >= 8 and no overlap`, spacingOk(sc.portals, 8))
  if (expect.floating !== undefined) check(`${label}: all ${expect.floating ? 'floating' : 'on the ground'}`, sc.portals.every(p => p.floating === expect.floating))
  if (expect.mixed) check(`${label}: both ground and floating`, sc.portals.some(p => p.floating) && sc.portals.some(p => !p.floating), `${sc.portals.filter(p => p.floating).length} floating`)
  if (expect.bothAxes) check(`${label}: both orientations`, sc.portals.some(p => p.axis === 'X') && sc.portals.some(p => p.axis === 'Z'))
  if (expect.grid) check(`${label}: grid rows line up`, new Set(sc.portals.map(p => (p.axis === 'X' ? p.z : p.x))).size < placed)
  const bad = await badBlocks(sc.portals)
  check(`${label}: frame obsidian, interiors active portal blocks (server-queried, ${placed * 20} blocks)`, bad === 0, bad + ' wrong')
  const air = await airUnderGround(sc.portals)
  check(`${label}: ground portals have support under the floor`, air === 0, air + ' unsupported columns')
  const recs = readBlocks(path.join(PLUGIN_DIR, 'scenes', name + '.blocks'))
  const out = await runUntil(`pd scene clear ${name}`, /cleared: \d+ blocks restored|Stopped/, 120000)
  const wrong = await wrongRestored(recs)
  check(`${label}: clear restores all ${recs.length} changed blocks`, wrong === 0 && /cleared/.test(out), wrong + ' differ')
  return sc
}

// bots

const bots = new Map()
async function connect (username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, auth: 'offline', version: '26.3', checkTimeoutInterval: 60000 })
  bot.on('error', e => console.error(`[${username}] error:`, e.message))
  bot.on('kicked', r => console.error(`[${username}] kicked:`, JSON.stringify(r)))
  bot.gameInfo = []
  bot.forced = []
  for (const pkt of ['action_bar', 'set_action_bar_text']) bot._client.on(pkt, p => bot.gameInfo.push(JSON.stringify(p)))
  bot._client.on('system_chat', p => { if (p.isActionBar || p.overlay) bot.gameInfo.push(JSON.stringify(p.content)) })
  bot.on('forcedMove', () => bot.forced.push(performance.now()))
  bots.set(username, bot)
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`${username} did not spawn`)), 40000)
    bot.once('spawn', () => { clearTimeout(t); resolve() })
  })
  return bot
}

const dist2 = (a, b) => Math.hypot(a.x - b.x, a.z - b.z)
const along = (pos, g) => (pos.x - g.cx) * g.stepX + (pos.z - g.cz) * g.stepZ
const sideways = (pos, g) => Math.abs((pos.x - g.cx) * g.stepZ) + Math.abs((pos.z - g.cz) * g.stepX)

async function walk (names, dir) {
  await Promise.all(names.map(async n => {
    const bot = bots.get(n)
    await bot.lookAt(bot.entity.position.offset(10 * dir, 0, 0), true)
    bot.setControlState('forward', true)
    await sleep(1800)
    bot.setControlState('forward', false)
  }))
}

async function main () {
  prepareServer()
  fs.mkdirSync(REPORT, { recursive: true })
  log('Starting the isolated test server on 127.0.0.1:' + PORT)
  srv.start()
  await srv.waitForLine(/Done \(/, 180000, 0, 'server start')
  check('plugin enabled', srv.lines.some(l => /PortalDirector ready/.test(l)))
  const found = await runUntil('execute in minecraft:overworld run locate biome minecraft:plains', /\[(-?\d+), (?:~|-?\d+), (-?\d+)\]/, 90000)
  const loc = found.match(/\[(-?\d+), (?:~|-?\d+), (-?\d+)\]/)
  const CX = +loc[1] + 40
  const CZ = +loc[2] + 40
  log(`Scene center ${CX}, ${CZ} (plains)`)
  await run(`forceload add ${CX - 150} ${CZ - 150} ${CX + 150} ${CZ}`, 1000)
  await run(`forceload add ${CX - 150} ${CZ + 1} ${CX + 150} ${CZ + 150}`, 1000)
  await sleep(20000)
  await run(`pd center ${CX} ${CZ} world`)

  let out = await run('pd radius 900')
  check('invalid setting gives a helpful message', /radius: must be between 8 and 400/.test(out), out.trim().split('\n').pop())
  await run('pd radius 400')
  out = (await run('pd scene new toobig', 300)) + (await run('pd generate 10'))
  check('too large an area is refused (chunk limit)', /chunks, more than the limit/.test(out))
  await run('pd scene delete toobig', 300)
  await run('pd radius 100')

  // 1. generated scenes, no bots
  log('Generated scenes')
  await sceneCase('ground random', ['pd placement ground', 'pd layout random', 'pd orientation random'], 20, { floating: false, bothAxes: true })
  await sceneCase('air random', ['pd placement air', 'pd layout random', 'pd height 25 60'], 20, { floating: true })
  await sceneCase('mixed grid', ['pd placement mixed', 'pd layout grid', 'pd orientation x'], 20, { grid: true })
  await sceneCase('mixed circle', ['pd placement mixed', 'pd layout circle', 'pd orientation random'], 20, { mixed: true })
  await sceneCase('mixed wave', ['pd placement mixed', 'pd layout wave'], 20, { mixed: true })
  await run('pd layout random')
  const tpsBefore = srv.lines.length
  const big = await sceneCase('mixed 30 scale', ['pd placement mixed', 'pd layout random'], 30, { mixed: true, bothAxes: true })
  check('30 portals: server never reported falling behind', !srv.lines.slice(tpsBefore).some(l => /Can't keep up|overloaded/i.test(l)))
  check('30 portals: floating heights vary', new Set(big.portals.filter(p => p.floating).map(p => p.y)).size > 3)

  // 2. manual portals
  log('Manual portals')
  await run('pd scene new manual', 300)
  await run('pd placement mixed')
  const Y = 150
  const spots = [[CX - 40, 'north'], [CX - 20, 'south'], [CX, 'east'], [CX + 20, 'west']]
  const created = []
  for (const [x, f] of spots) {
    out = await runUntil(`pd portal create ${x} ${Y} ${CZ} ${f} world`, /Portal \d+ built|\[PD\] (Too close|y \d+|That would)/, 30000)
    created.push(/Portal \d+ built/.test(out))
  }
  check('portal create works for north, south, east and west', created.every(Boolean), out.trim().split('\n').pop())
  const sc = readScene('manual')
  check('four manual portals with ids 1-4', sc.portals.length === 4 && sc.portals.map(p => p.id).join() === '1,2,3,4')
  check('facing and axis match the request',
    ['NORTH', 'SOUTH', 'EAST', 'WEST'].every((f, i) => sc.portals[i].facing === f && sc.portals[i].axis === (i < 2 ? 'X' : 'Z')))
  check('coordinates are the lower bottom interior block (floor one below)',
    sc.portals.every((p, i) => (p.axis === 'X' ? p.x + 1 : p.x) === spots[i][0] && p.y + 1 === Y && (p.axis === 'Z' ? p.z + 1 : p.z) === CZ))
  check('manual portals are floating at the given elevation', sc.portals.every(p => p.floating && p.y === Y - 1))
  check('manual portal blocks verified on the server', (await badBlocks(sc.portals)) === 0)
  out = await run(`pd portal create ${CX - 38} ${Y} ${CZ} north world`)
  check('overlapping portal is refused', /Too close to portal 1/.test(out), out.trim().split('\n').pop())
  out = await run(`pd portal create ${CX} 5000 ${CZ} north world`)
  check('invalid height is refused', /outside the build limits/.test(out), out.trim().split('\n').pop())
  out = await run(`pd portal create ${CX} ${Y + 50} ${CZ} up world`)
  check('bad direction is refused', /not a direction/.test(out), out.trim().split('\n').pop())
  out = await run(`pd portal create ${CX} ${Y + 50} ${CZ} north nowhere`)
  check('unknown world is refused', /neither a loaded world/.test(out))
  await run(`fill ${CX + 40} ${Y + 40} ${CZ - 3} ${CX + 46} ${Y + 46} ${CZ + 3} minecraft:stone_bricks`, 500)
  out = await run(`pd portal create ${CX + 42} ${Y + 42} ${CZ} north world`, 1200)
  check('placing over a built structure is refused', /looks built by a player/.test(out), out.trim().split('\n').pop())
  out = await run(`pd portal create ${CX + 42} ${Y + 42} ${CZ} north world force`, 1500)
  check('force replaces it', /Portal 5 built/.test(out), out.trim().split('\n').pop())
  out = await run('pd portal remove 5', 2500)
  check('portal remove restores the blocks and drops the id', /Portal 5 removed/.test(out) && !readScene('manual').portals.some(p => p.id === 5), out.trim().split('\n').pop())
  const stoneBack = await run(`execute if block ${CX + 40} ${Y + 40} ${CZ} minecraft:stone_bricks`, 400)
  check('removal gave the stone bricks back', /Test passed/.test(stoneBack))
  check('other portals untouched by remove', (await badBlocks(readScene('manual').portals)) === 0)
  out = await run('pd portal remove 99')
  check('removing a missing portal gives an error', /There is no portal 99/.test(out))
  out = await run('pd portal list')
  check('portal list shows ids and coordinates', /#1 .*north/.test(out) && /#4 .*west/.test(out))
  await run(`fill ${CX + 40} ${Y + 40} ${CZ - 3} ${CX + 46} ${Y + 46} ${CZ + 3} minecraft:air`, 500)

  // 3. bots: join, assignments, entrances
  log('Connecting bots')
  await run('forceload add 985 985 1045 1045', 800)
  await sleep(4000)
  await run('fill 985 199 985 1045 199 1045 stone')
  await run('fill 985 200 985 1045 205 1045 air')
  await run('fill 1005 199 985 1005 199 1045 emerald_block')
  await run('pd group create NULL')
  out = await run('pd group block set emerald_block')
  check('group block set', /emerald_block now join NULL/.test(out))
  for (const n of NAMES) await connect(n)
  await sleep(1500)
  for (let i = 0; i < NAMES.length; i++) await run(`tp ${NAMES[i]} 1002.5 200 ${990 + i * 3}`, 60)
  await sleep(2000)
  await walk(NAMES, 1)
  await sleep(800)
  out = await run('pd group info NULL', 900)
  const members = [...out.matchAll(/(\d+)\. (PdBot\d+) .*?-> portal (\d+)/g)].map(m => ({ n: +m[1], name: m[2], portal: +m[3] }))
  check('all 4 bots joined by stepping on the block and got portals 1-4 in order', members.length === 4 && members.every(m => m.portal === m.n))
  await walk(NAMES, -1)
  await walk(NAMES, 1)
  out = await run('pd group info NULL', 900)
  check('stepping on the block again does not duplicate anyone', [...out.matchAll(/(\d+)\. PdBot/g)].length === 4)
  check('exactly one join message per player, none in chat', NAMES.every(n => bots.get(n).gameInfo.length === 1), NAMES.map(n => bots.get(n).gameInfo.length + ':' + bots.get(n).gameInfo.join('|')).join(' ; '))
  const readSlots = async () => {
    const o = await run('pd group info NULL', 900)
    const m = {}
    for (const x of o.matchAll(/(PdBot\d+) .*?-> portal (\d+)/g)) m[x[1]] = +x[2]
    return m
  }
  const holder = (m, id) => Object.keys(m).find(k => m[k] === id)
  const s0 = await readSlots()
  await run('pd group assign NULL swap 1 4')
  const s1b = await readSlots()
  check('assignments can be swapped (portals 1 and 4 trade players)', holder(s1b, 1) === holder(s0, 4) && holder(s1b, 4) === holder(s0, 1), JSON.stringify(s1b))
  const mover = holder(s1b, 3)
  const displaced = holder(s1b, 2)
  await run(`pd group assign NULL 2 ${mover}`)
  out = await run('pd group assignments NULL')
  check('manual assign moves a player onto another portal and frees their old one', out.includes(`2: ${mover}`) && out.includes(`Without a portal: ${displaced}`), out.trim().split('\n').slice(-2).join(' | '))
  await run('pd group assign NULL auto')
  const slot = {}
  out = await run('pd group info NULL', 900)
  for (const m of out.matchAll(/(PdBot\d+) .*?-> portal (\d+)/g)) slot[m[1]] = +m[2]
  const portalById = Object.fromEntries(readScene('manual').portals.map(p => [p.id, p]))
  const before = Object.fromEntries(NAMES.map(n => [n, bots.get(n).entity.position.clone()]))

  out = await run('pd group prepare NULL', 2500)
  await sleep(2500)
  check('prepare staged 4 players', /4 players are in place/.test(out))
  let atPortal = 0
  for (const n of NAMES) {
    const g = geom(portalById[slot[n]])
    const pos = bots.get(n).entity.position
    if (dist2(pos, { x: g.cx, z: g.cz }) < 1.2 && Math.abs(pos.y - g.standY) < 1.5) atPortal++
  }
  check('every player stands in their own manual portal', atPortal === 4, atPortal + '/4')
  const s1 = NAMES.map(n => bots.get(n).entity.position.clone())
  await sleep(1200)
  check('staged players are frozen', NAMES.every((n, i) => dist2(bots.get(n).entity.position, s1[i]) < 0.3))

  bots.get('PdBot2').end()
  await sleep(2000)
  await connect('PdBot2')
  await sleep(2500)
  {
    const g = geom(portalById[slot.PdBot2])
    check('a player who reconnects while prepared is put back in their portal', dist2(bots.get('PdBot2').entity.position, { x: g.cx, z: g.cz }) < 1.2)
  }

  for (const n of NAMES) bots.get(n).forced.length = 0
  const traj = Object.fromEntries(NAMES.map(n => [n, []]))
  const sampler = setInterval(() => { for (const n of NAMES) traj[n].push({ t: performance.now(), p: bots.get(n).entity.position.clone() }) }, 50)
  const tCmd = performance.now()
  out = await run('pd group release NULL', 100)
  await sleep(9000)
  clearInterval(sampler)
  check('release message', /Release! 4 players/.test(out))
  const firsts = NAMES.map(n => bots.get(n).forced.find(t => t >= tCmd)).filter(Boolean)
  const spread = Math.max(...firsts) - Math.min(...firsts)
  check('all clients got their teleport within 200 ms of each other', firsts.length === 4 && spread < 200, `${spread.toFixed(0)} ms`)
  for (const n of NAMES) {
    const g = geom(portalById[slot[n]])
    const t = traj[n]
    const peak = Math.max(...t.map(s => along(s.p, g)))
    const side = Math.max(...t.slice(0, 40).map(s => sideways(s.p, g)))
    const f = portalById[slot[n]].facing
    check(`${n} came out of portal ${slot[n]} (${f.toLowerCase()}) and walked >= 3 blocks in that direction`, peak >= 3 && side < 1.5, `forward ${peak.toFixed(1)}, sideways ${side.toFixed(1)}`)
  }
  check('nobody took fall damage from 80+ block drops', NAMES.every(n => bots.get(n).health === 20), NAMES.map(n => bots.get(n).health).join(','))
  check('nobody was sent to the Nether by vanilla portals', NAMES.every(n => bots.get(n).game.dimension === 'overworld'))

  await run('pd group reset NULL', 2000)
  await sleep(2500)
  check('reset sends everyone back to where they were', NAMES.every(n => dist2(bots.get(n).entity.position, before[n]) < 4))

  await run('pd group prepare NULL', 1500)
  await sleep(2500)
  for (const n of NAMES) bots.get(n).forced.length = 0
  const tS = performance.now()
  out = await run('pd group release NULL staggered 2 2', 100)
  await sleep(5500)
  check('staggered message', /2 waves of 2, one every 2s/.test(out), out.trim().split('\n').pop())
  const times = NAMES.map(n => ({ n, t: (Math.max(...bots.get(n).forced.filter(x => x >= tS)) - tS) / 1000, slot: slot[n] })).sort((a, b) => a.slot - b.slot)
  const w1 = (times[0].t + times[1].t) / 2
  const w2 = (times[2].t + times[3].t) / 2
  check('two waves two seconds apart (portals 1-2 then 3-4)', Math.abs(w2 - w1 - 2) < 0.6 && Math.abs(times[0].t - times[1].t) < 0.3, `${w1.toFixed(2)}s then ${w2.toFixed(2)}s`)
  await run('pd group reset NULL', 1500)
  await sleep(2500)

  await run('pd group prepare NULL', 1500)
  await sleep(2500)
  for (const n of NAMES) bots.get(n).forced.length = 0
  const tC = performance.now()
  await run('pd group release NULL staggered 3 2', 100)
  await sleep(1200)
  await run('pd group reset NULL', 500)
  await sleep(5000)
  const lateWave = times.slice(2).filter(x => bots.get(x.n).forced.some(f => f > tC + 2800 && f < tC + 4600))
  check('reset cancels waves that have not happened yet', lateWave.length === 0)

  await run('pd group prepare NULL', 1500)
  await sleep(2000)
  bots.get('PdBot4').end()
  await sleep(2000)
  out = await run('pd group release NULL', 800)
  check('release with a player offline still works for the rest', /Release!/.test(out) && !/Exception|went wrong/.test(out), out.trim().split('\n').pop())
  await sleep(3000)
  check('server log has no exceptions from the plugin', !srv.lines.some(l => /PortalDirector.*(Exception|Error)|at dev\.portaldirector/.test(l)))
  await run('pd group reset NULL', 1500)
  await connect('PdBot4')
  await sleep(2000)

  // 4. TNT
  log('TNT')
  await run('tp @a 1002.5 200 1000', 60)
  await run('pd scene load manual', 300)
  check('no TNT exists before any tnt command', (await count('@e[type=minecraft:tnt_minecart]')) === 0)
  await run('pd tnt preview on')
  for (const id of [1, 2, 3, 4]) {
    const p = portalById[id]
    const g = geom(p)
    await run(`pd tnt launch ${id} 1`, 100)
    await sleep(350)
    const posOut = await run('data get entity @e[type=minecraft:tnt_minecart,limit=1] Pos', 400)
    const pm = posOut.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
    const pos = pm ? { x: +pm[1], z: +pm[3] } : null
    check(`minecart from portal ${id} (${p.facing.toLowerCase()}) leaves that way`, pos && along(pos, g) > 0.8 && sideways(pos, g) < 1, pos ? `forward ${along(pos, g).toFixed(2)}, sideways ${sideways(pos, g).toFixed(2)}` : posOut.trim())
    await run('pd tnt clear', 300)
  }
  await run('pd tnt launch 1 1', 100)
  await sleep(300)
  const v1 = (await run('data get entity @e[type=minecraft:tnt_minecart,limit=1] Motion', 400)).match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
  check('launch velocity is applied (horizontal motion near tnt.speed)', v1 && Math.hypot(+v1[1], +v1[3]) > 0.3, v1 ? v1.slice(1).join(', ') : '')
  await run('pd tnt clear', 300)
  await run('pd tnt launch all')
  await sleep(1200)
  check('launch all: one minecart per portal at once', (await count('@e[type=minecraft:tnt_minecart]')) === 4)
  await sleep(10000)
  check('preview mode: nothing exploded', (await count('@e[type=minecraft:tnt_minecart]')) === 4)
  out = await run('pd tnt clear', 400)
  check('tnt clear removes them', /Removed 4/.test(out) && (await count('@e[type=minecraft:tnt_minecart]')) === 0)
  await run('pd tnt launch all 2 stagger 1', 100)
  await sleep(500)
  const a1 = await count('@e[type=minecraft:tnt_minecart]')
  await sleep(2000)
  const a2 = await count('@e[type=minecraft:tnt_minecart]')
  await sleep(2500)
  const a3 = await count('@e[type=minecraft:tnt_minecart]')
  check('staggered launch (2 each, 1s between portals) adds minecarts over time', a1 < a2 && a2 < a3 && a3 === 8, `${a1}, ${a2}, ${a3}`)
  await run('pd tnt clear', 300)
  await run('pd tnt wave 3 2')
  await sleep(900)
  const c1 = await count('@e[type=minecraft:tnt_minecart]')
  await sleep(2000)
  const c2 = await count('@e[type=minecraft:tnt_minecart]')
  await sleep(2000)
  const c3 = await count('@e[type=minecraft:tnt_minecart]')
  check('tnt wave 3 2: 4, 8, 12 minecarts two seconds apart', c1 === 4 && c2 === 8 && c3 === 12, `${c1}, ${c2}, ${c3}`)
  await run('pd tnt clear', 300)
  await run('pd tnt wave 5 3')
  await sleep(900)
  out = await run('pd tnt stop', 400)
  await sleep(6000)
  check('tnt stop cancels the rest of a wave sequence', /Stopped 1/.test(out) && (await count('@e[type=minecraft:tnt_minecart]')) === 4)
  await run('pd tnt clear', 300)
  await run('pd set tnt.max-active 6')
  await run('pd tnt launch all 3')
  await sleep(2500)
  const capped = await count('@e[type=minecraft:tnt_minecart]')
  check('entity limit caps minecarts', capped > 0 && capped <= 6, capped + ' minecarts')
  await run('pd tnt clear', 300)
  await run('pd set tnt.max-active 300')
  await run('pd tnt preview off')
  await run('pd set tnt.fuse-ticks 30')
  await run('pd tnt launch all 2')
  await sleep(8000)
  check('real explosions leave portals intact', (await badBlocks(readScene('manual').portals)) === 0)
  await run('pd set tnt.fuse-ticks -1')
  await run('pd tnt clear', 300)

  // 5. persistence
  log('Persistence')
  out = await run('pd scene save invasion')
  check('scene save renames the scene', /saved as invasion/.test(out))
  for (const b of bots.values()) { try { b.end() } catch {} }
  bots.clear()
  await sleep(2000)
  const code = await srv.stop()
  check('server stopped gracefully', code === 0, 'exit code ' + code)
  srv.lines = []
  srv.start()
  await srv.waitForLine(/Done \(/, 180000, 0, 'server restart')
  await sleep(5000)
  out = await run('pd scene info', 800)
  check('scene is back after restart', /Scene invasion \(current\)/.test(out) && /Portals: 4/.test(out))
  out = await run('pd group info NULL', 800)
  check('group, members and join block survive restart', /PdBot1/.test(out) && /emerald_block/.test(out))
  check('assignments saved with the scene', Object.keys(readScene('invasion').assignments.null || {}).length === 4)
  check('portal blocks still in the world after restart', (await badBlocks(readScene('invasion').portals)) === 0)
  await run(`setblock ${CX - 20 - 1} ${Y} ${CZ} minecraft:air`, 300)
  await run(`setblock ${CX - 20} ${Y - 1} ${CZ} minecraft:air`, 300)
  check('breaking a portal is detected', (await badBlocks(readScene('invasion').portals)) > 0)
  out = await runUntil('pd scene rebuild', /Rebuilt \d+ portals/, 60000)
  check('scene rebuild puts broken portals back', (await badBlocks(readScene('invasion').portals)) === 0, out.replace(/^\[[^\]]*\] /, ''))

  const recs = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'invasion.blocks'))
  const p1 = readScene('invasion').portals[0]
  const mark = { x: p1.x, y: p1.y, z: p1.z }
  await run(`setblock ${mark.x} ${mark.y} ${mark.z} minecraft:diamond_block`, 300)
  out = await runUntil('pd clear', /cleared: \d+ blocks restored/, 120000)
  check('clear reports blocks left alone', /left alone/.test(out), out.replace(/^\[[^\]]*\] /, ''))
  check('every other changed block went back to the original', (await wrongRestored(recs, [mark])) === 0)
  const keep = await run(`execute if block ${mark.x} ${mark.y} ${mark.z} minecraft:diamond_block`, 400)
  check('a block somebody changed afterwards is not overwritten', /Test passed/.test(keep))
  const regen = await runUntil('pd generate 12 ground', /Done: \d+ of 12/, 120000)
  check('a cleared scene can be generated again', /Done: 12 of 12/.test(regen), regen)
}

main().catch(e => {
  console.error('[pd-test] ERROR:', e.stack || e.message)
  checks.push({ name: 'harness ran to the end', ok: false, detail: e.message })
}).finally(async () => {
  for (const b of bots.values()) { try { b.end() } catch {} }
  await srv.stop().catch(() => {})
  fs.writeFileSync(path.join(REPORT, 'latest.json'), JSON.stringify({ when: new Date().toISOString(), checks }, null, 2))
  fs.writeFileSync(path.join(REPORT, 'server-console.log'), srv.lines.join('\n'))
  const failed = checks.filter(c => !c.ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
})
