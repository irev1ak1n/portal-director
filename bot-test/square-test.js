'use strict'
/*
 * Square formation live test. Same isolated localhost server and bots as run.js, a fresh world each time.
 *   1. no bots: preview, create, geometry, facing flip, remove/restore, portals-per-side, errors,
 *      an elevated square and a large square (lag check)
 *   2. 4 bots on different sides: simultaneous, clockwise, opposite-sides and side-by-side releases, inward and outward
 *   3. TNT minecarts from the square in both directions
 *   4. restart persistence, rebuild and safe clear
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
const log = (...a) => console.log('[square-test]', ...a)
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
  if (get('server-ip') !== HOST || get('server-port') !== String(PORT)) throw new Error('Refusing to run: the server must be 127.0.0.1:' + PORT)
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
  const scene = { portals: [], square: {} }
  let section = ''
  let cur = null
  for (const l of lines) {
    if (/^portals:/.test(l)) { section = 'portals'; continue }
    if (/^square:/.test(l)) { section = 'square'; continue }
    if (/^\S/.test(l) && !/^- /.test(l)) section = ''
    if (section === 'portals') {
      let m = l.match(/^- id: (\d+)/)
      if (m) { cur = { id: +m[1] }; scene.portals.push(cur); continue }
      m = l.match(/^ {2}(\w+): (.+)$/)
      if (m && cur) cur[m[1]] = m[2] === 'true' ? true : m[2] === 'false' ? false : /^-?\d+$/.test(m[2]) ? +m[2] : m[2]
    } else if (section === 'square') {
      const m = l.match(/^ {2}([\w-]+): (.+)$/)
      if (m) scene.square[m[1]] = /^-?\d+$/.test(m[2]) ? +m[2] : m[2]
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
function noOverlap (portals) {
  for (let i = 0; i < portals.length; i++) for (let j = i + 1; j < portals.length; j++) if (!separated(portals[i], portals[j], 0)) return false
  return true
}

function clearMarks () { srv.lines = srv.lines.filter(l => !/\[Server\] (BAD|RBAD)/.test(l)) }
async function flush (tag) {
  const from = srv.lines.length
  srv.command('say ' + tag)
  await srv.waitForLine(new RegExp(tag), 180000, from)
  await sleep(250)
}
async function badBlocks (portals) {
  clearMarks()
  for (const p of portals) {
    for (let i = 0; i < 4; i++) {
      for (let j = 0; j < 5; j++) {
        const x = p.axis === 'X' ? p.x + i : p.x
        const z = p.axis === 'Z' ? p.z + i : p.z
        const frame = i === 0 || i === 3 || j === 0 || j === 4
        const want = frame ? 'minecraft:obsidian' : `minecraft:nether_portal[axis=${p.axis.toLowerCase()}]`
        srv.command(`execute unless block ${x} ${p.y + j} ${z} ${want} run say BAD ${p.id}`)
      }
    }
  }
  await flush('CHECKDONE' + Date.now())
  return srv.lines.filter(l => /\[Server\] BAD \d+/.test(l)).length
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
    srv.command(`execute unless block ${r.x} ${r.y} ${r.z} ${r.original} run say RBAD`)
  }
  await flush('RCHECKDONE' + Date.now())
  return srv.lines.filter(l => /\[Server\] RBAD/.test(l)).length
}

/** Square geometry checks from the saved scene. */
function squareChecks (label, sc, n, gap, facing) {
  const P = sc.portals
  const S = n * (4 + gap) + 4
  check(`${label}: ${4 * n} portals, ids 1-${4 * n} in clockwise order`, P.length === 4 * n && P.every((p, i) => p.id === i + 1))
  const sides = [0, 1, 2, 3].map(s => P.slice(s * n, s * n + n))
  check(`${label}: north/south portals run along X, east/west along Z`, sides.every((side, i) => side.every(p => p.axis === (i % 2 === 0 ? 'X' : 'Z'))))
  const extent = sides.map((side, i) => {
    const a = side.map(p => (i % 2 === 0 ? p.x : p.z))
    return Math.max(...a) + 3 - Math.min(...a) + 1
  })
  check(`${label}: all four sides are equally long (${extent[0]} blocks)`, extent.every(e => e === extent[0]) && extent[0] === n * 4 + (n - 1) * gap, extent.join(','))
  const rowsOk = sides.every((side, i) => side.every((p, k) => k === 0 || Math.abs((i % 2 === 0 ? p.x - side[k - 1].x : p.z - side[k - 1].z)) === 4 + gap))
  check(`${label}: neighbouring frames are exactly ${gap} blocks apart`, rowsOk)
  check(`${label}: no overlapping frames or walkways (corners included)`, noOverlap(P))
  const cx = (sc.square['center-x'])
  const cz = (sc.square['center-z'])
  const inward = P.every(p => {
    const g = geom(p)
    return g.stepX * (cx - g.cx) + g.stepZ * (cz - g.cz) > 0
  })
  const outward = P.every(p => {
    const g = geom(p)
    return g.stepX * (cx - g.cx) + g.stepZ * (cz - g.cz) < 0
  })
  check(`${label}: every portal faces ${facing}`, facing === 'inward' ? inward : outward)
  const open = P.every(p => { const b = geom(p).box; return !(cx >= b.minX && cx <= b.maxX && cz >= b.minZ && cz <= b.maxZ) })
  check(`${label}: outer size ${S} and the middle stays open`, sc.square.size === S && open)
}

// bots
const bots = new Map()
async function connect (username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, auth: 'offline', version: '26.3', checkTimeoutInterval: 60000 })
  bot.on('error', e => console.error(`[${username}] error:`, e.message))
  bot.forced = []
  bot.on('forcedMove', () => bot.forced.push(performance.now()))
  bots.set(username, bot)
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`${username} did not spawn`)), 40000)
    bot.once('spawn', () => { clearTimeout(t); resolve() })
  })
  return bot
}
const dist2 = (a, b) => Math.hypot(a.x - b.x, a.z - b.z)

async function main () {
  prepareServer()
  fs.mkdirSync(REPORT, { recursive: true })
  srv.start()
  await srv.waitForLine(/Done \(/, 240000, 0, 'server start')
  check('plugin enabled', srv.lines.some(l => /PortalDirector ready/.test(l)))
  const found = await runUntil('execute in minecraft:overworld run locate biome minecraft:plains', /\[(-?\d+), (?:~|-?\d+), (-?\d+)\]/, 90000)
  const loc = found.match(/\[(-?\d+), (?:~|-?\d+), (-?\d+)\]/)
  const CX = +loc[1] + 40
  const CZ = +loc[2] + 40
  log(`center ${CX}, ${CZ}`)
  for (const [a, b] of [[-150, 0], [1, 150]]) await runUntil(`forceload add ${CX - 150} ${CZ + a} ${CX + 150} ${CZ + b}`, /Marked \d+ chunks/, 600000)
  await sleep(3000)
  await run(`pd set center ${CX} ${CZ} world`)

  /** Natural terrain is rarely open enough, so the test flattens an arena: grass at y 71, air above, dirt below. */
  async function terraform (cx, cz, r) {
    for (let x = cx - r; x <= cx + r; x += 3) {
      srv.command(`fill ${x} 60 ${cz - r} ${x + 2} 70 ${cz + r} minecraft:dirt`)
      srv.command(`fill ${x} 71 ${cz - r} ${x + 2} 71 ${cz + r} minecraft:grass_block`)
      srv.command(`fill ${x} 72 ${cz - r} ${x + 2} 120 ${cz + r} minecraft:air`)
    }
    await flush('TERRAFORMED' + Date.now())
  }
  await terraform(CX, CZ, 140)
  check('flat test arena prepared', true)
  await run('pd set generate.max-portals 500')

  // 1. no bots
  let out = await run('pd square preview 60', 4000)
  check('preview reports the fit and builds nothing', /9 portals per side, 36 in all/.test(out) && /Fits: \d+ of 36/.test(out) && /Nothing was built/.test(out), out.trim().split('\n').slice(-2).join(' | '))
  check('no portals exist after a preview', !fs.existsSync(path.join(PLUGIN_DIR, 'scenes', 'default.yml')) || readScene('default').portals.length === 0)

  out = await run('pd square create 10')
  check('too small a square is refused', /too small/.test(out), out.trim().split('\n').pop())
  out = await run('pd square create 60 spacing 99')
  check('bad spacing is refused', /between 0 and 20/.test(out), out.trim().split('\n').pop())
  out = await run('pd square create 60 bogus')
  check('unknown option is refused', /Unknown option/.test(out))

  const t0 = Date.now()
  out = await runUntil('pd square create 60', /Square built|Not built|Nothing fits/, 120000)
  log('square 60 built in ' + (Date.now() - t0) + ' ms')
  check('square create 60 builds', /Square built: (\d+) portals/.test(out), out.replace(/^\[[^\]]*\] /, ''))
  let sc = readScene('default')
  squareChecks('square 60', sc, 9, 2, 'inward')
  check('square 60: all portals on the server are real portals (720 blocks queried)', (await badBlocks(sc.portals)) === 0)
  out = await run('pd square create 60')
  check('a second square in the same scene is refused', /already has a square/.test(out))
  out = await run('pd square info')
  check('square info', /60 x 60|58 x 58|\d+ x \d+/.test(out) && /north side: portals 1-9/.test(out), out.trim().split('\n').slice(-3).join(' | '))

  out = await runUntil('pd square facing outward', /now faces outward|cannot turn/, 60000)
  sc = readScene('default')
  squareChecks('after facing outward', sc, 9, 2, 'outward')
  check('turned portals are still intact on the server', (await badBlocks(sc.portals)) === 0)
  await runUntil('pd square facing inward', /now faces inward/, 60000)
  sc = readScene('default')
  squareChecks('after facing inward again', sc, 9, 2, 'inward')

  let recs = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))
  out = await runUntil('pd square remove', /Square removed/, 120000)
  check('square remove restores the terrain', (await wrongRestored(recs)) === 0 && /restored/.test(out), `${recs.length} blocks, ` + out.replace(/^\[[^\]]*\] /, ''))
  check('square remove leaves no portals or square info', readScene('default').portals.length === 0 && !('size' in readScene('default').square))

  out = await runUntil('pd square create 8 portals-per-side spacing 3', /Square built|Not built|Nothing fits/, 120000)
  sc = readScene('default')
  check('portals-per-side builds 32 portals, size 60', /Square built: 32/.test(out) && sc.square.size === 60, out.replace(/^\[[^\]]*\] /, ''))
  squareChecks('8 per side, spacing 3', sc, 8, 3, 'inward')
  check('8 per side: blocks verified', (await badBlocks(sc.portals)) === 0)
  recs = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))
  await runUntil('pd square remove', /Square removed/, 120000)
  check('8 per side: removal restores everything', (await wrongRestored(recs)) === 0)

  out = await runUntil('pd square create 40 elevation 25 spacing 1', /Square built|Not built|Nothing fits/, 120000)
  sc = readScene('default')
  check('elevated square floats on one level', /Square built/.test(out) && sc.portals.length > 0 && sc.portals.every(p => p.floating) && new Set(sc.portals.map(p => p.y)).size === 1, out.replace(/^\[[^\]]*\] /, ''))
  check('elevated square: blocks verified', (await badBlocks(sc.portals)) === 0)
  recs = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))
  await runUntil('pd square remove', /Square removed/, 120000)
  check('elevated square: removal restores everything', (await wrongRestored(recs)) === 0)

  const lagFrom = srv.lines.length
  const tBig = Date.now()
  out = await runUntil('pd square create 200 spacing 1 partial', /Square built|Not built|Nothing fits/, 240000)
  log('large square took ' + (Date.now() - tBig) + ' ms')
  sc = readScene('default')
  check('large square (200 blocks) builds', /Square built/.test(out) && sc.portals.length >= 100, `${sc.portals.length} portals; ` + out.replace(/^\[[^\]]*\] /, ''))
  check('large square: server never reported falling behind', !srv.lines.slice(lagFrom).some(l => /Can't keep up|overloaded/i.test(l)))
  check('large square: no overlaps', noOverlap(sc.portals))
  check('large square: blocks verified', (await badBlocks(sc.portals)) === 0, sc.portals.length * 20 + ' blocks')
  recs = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))
  await runUntil('pd square remove', /Square removed/, 240000)
  const wrongLarge = await wrongRestored(recs)
  check('large square: removal restores everything', wrongLarge === 0, recs.length + ' blocks, ' + wrongLarge + ' differ')

  // 2. bots on four sides
  log('Bots')
  out = await runUntil('pd square create 30', /Square built|Not built|Nothing fits/, 120000)
  sc = readScene('default')
  const n = sc.square['per-side']
  check('square 30 builds (4 per side)', /Square built: 16/.test(out) && n === 4, out.replace(/^\[[^\]]*\] /, ''))
  const portalById = Object.fromEntries(sc.portals.map(p => [p.id, p]))
  const SIDE_FIRST = [1, 1 + n, 1 + 2 * n, 1 + 3 * n]
  const midX = sc.square['center-x']
  const midZ = sc.square['center-z']
  await run('forceload add 985 985 1045 1045', 800)
  await sleep(4000)
  await run('fill 985 199 985 1045 199 1045 stone')
  await run('fill 985 200 985 1045 205 1045 air')
  await run('pd group create NULL')
  for (const nm of NAMES) await connect(nm)
  for (const nm of NAMES) await run(`pd group add NULL ${nm}`, 150)
  for (let i = 0; i < 4; i++) await run(`pd group assign NULL ${SIDE_FIRST[i]} ${NAMES[i]}`, 200)
  out = await run('pd group assignments NULL')
  check('one player on the first portal of each side', NAMES.every((nm, i) => out.includes(`${SIDE_FIRST[i]}: ${nm}`)), out.split('\n').slice(1, 6).join(' | '))
  for (let i = 0; i < 4; i++) await run(`tp ${NAMES[i]} 1002.5 200 ${990 + i * 3}`, 60)
  await sleep(2000)

  async function releaseTest (label, releaseCmd, expectOffsets, direction) {
    await run('pd group reset NULL', 1200)
    await sleep(1500)
    const startPos = Object.fromEntries(NAMES.map(nm => [nm, bots.get(nm).entity.position.clone()]))
    await run('pd group prepare NULL', 1500)
    await sleep(2500)
    for (const nm of NAMES) bots.get(nm).forced.length = 0
    const traj = Object.fromEntries(NAMES.map(nm => [nm, []]))
    const sampler = setInterval(() => { for (const nm of NAMES) traj[nm].push(bots.get(nm).entity.position.clone()) }, 50)
    const tS = performance.now()
    const o = await run(releaseCmd, 100)
    await sleep(Math.max(...expectOffsets) * 1000 + 6500)
    clearInterval(sampler)
    const times = NAMES.map(nm => (Math.max(...bots.get(nm).forced.filter(x => x >= tS)) - tS) / 1000)
    const fits = times.every((t, i) => Math.abs(t - expectOffsets[i]) < 0.6)
    check(`${label}: release times ${expectOffsets.join('/')}s`, fits && !/Exception|went wrong/.test(o), times.map(t => t.toFixed(2)).join('/') + 's')
    let ok = 0
    const detail = []
    for (let i = 0; i < 4; i++) {
      const p = portalById[SIDE_FIRST[i]]
      const g = geom(p)
      const end = traj[NAMES[i]][traj[NAMES[i]].length - 1]
      const fwd = (end.x - g.cx) * g.stepX + (end.z - g.cz) * g.stepZ
      const towardMiddle = Math.hypot(g.cx - midX, g.cz - midZ) - Math.hypot(end.x - midX, end.z - midZ)
      detail.push(`${NAMES[i]}:${fwd.toFixed(1)}/${towardMiddle.toFixed(1)}`)
      if (fwd >= 3 && (direction === 'inward' ? towardMiddle > 2 : towardMiddle < -2)) ok++
    }
    check(`${label}: all four came out of their side's portal and walked ${direction} (>= 3 blocks)`, ok === 4, ok + '/4 ' + detail.join(' '))
    return times
  }

  await releaseTest('all-at-once (inward)', 'pd group release NULL all-at-once', [0, 0, 0, 0], 'inward')
  const firsts = NAMES.map(nm => bots.get(nm).forced[bots.get(nm).forced.length - 1])
  check('all four sides released within 200 ms of each other', Math.max(...firsts) - Math.min(...firsts) < 200, (Math.max(...firsts) - Math.min(...firsts)).toFixed(0) + ' ms')
  await releaseTest('clockwise 2s (inward)', 'pd group release NULL clockwise 2', [0, 2, 4, 6], 'inward')
  await releaseTest('opposite-sides 3s (inward)', 'pd group release NULL opposite-sides 3', [0, 3, 0, 3], 'inward')
  await releaseTest('side-by-side 2s (inward)', 'pd group release NULL side-by-side 2', [0, 2, 4, 6], 'inward')
  out = await run('pd group release NULL staggered 1 2', 300)
  check('the old staggered release still works with a square', /waves of 2/.test(out), out.trim().split('\n').pop())
  await run('pd group reset NULL', 1500)

  await runUntil('pd square facing outward', /now faces outward/, 60000)
  await sleep(1500)
  sc = readScene('default')
  for (const p of sc.portals) portalById[p.id] = p
  await releaseTest('all-at-once (outward)', 'pd group release NULL all-at-once', [0, 0, 0, 0], 'outward')
  await run('pd group reset NULL', 1500)
  await runUntil('pd square facing inward', /now faces inward/, 60000)
  sc = readScene('default')
  for (const p of sc.portals) portalById[p.id] = p

  // 3. TNT
  log('TNT')
  await run('tp @a 1002.5 200 1000', 60)
  await run('pd tnt preview on')
  for (const dir of ['inward', 'outward']) {
    if (dir === 'outward') {
      await runUntil('pd square facing outward', /now faces outward/, 60000)
      sc = readScene('default')
      for (const p of sc.portals) portalById[p.id] = p
    }
    for (let i = 0; i < 4; i++) {
      const p = portalById[SIDE_FIRST[i]]
      const g = geom(p)
      await run(`pd tnt launch ${p.id} 1`, 100)
      await sleep(400)
      const posOut = await run('data get entity @e[type=minecraft:tnt_minecart,limit=1] Pos', 400)
      const pm = posOut.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
      let good = false
      let detail = posOut.trim()
      if (pm) {
        const x = +pm[1]
        const z = +pm[3]
        const toMiddle = Math.hypot(g.cx - midX, g.cz - midZ) - Math.hypot(x - midX, z - midZ)
        good = dir === 'inward' ? toMiddle > 1 : toMiddle < -1
        detail = `${toMiddle.toFixed(2)} blocks ${toMiddle > 0 ? 'toward' : 'away from'} the middle`
      }
      check(`minecart from the ${['north', 'east', 'south', 'west'][i]} side flies ${dir}`, good, detail)
      await run('pd tnt clear', 300)
    }
  }
  await runUntil('pd square facing inward', /now faces inward/, 60000)
  out = await run('pd tnt launch side east')
  await sleep(1000)
  check('tnt launch side east: one minecart per east portal', (await count('@e[type=minecraft:tnt_minecart]')) === 4, out.trim().split('\n').pop())
  await run('pd tnt clear', 300)
  await run('pd tnt launch square')
  await sleep(1200)
  check('tnt launch square: one minecart through every portal at once', (await count('@e[type=minecraft:tnt_minecart]')) === 16)
  await run('pd tnt clear', 300)
  await run('pd tnt launch square 1 stagger 0.5')
  await sleep(1000)
  const s1 = await count('@e[type=minecraft:tnt_minecart]')
  await sleep(3000)
  const s2 = await count('@e[type=minecraft:tnt_minecart]')
  await sleep(5000)
  const s3 = await count('@e[type=minecraft:tnt_minecart]')
  check('staggered square launch spreads minecarts over time', s1 < s2 && s2 < s3 && s3 === 16, `${s1}, ${s2}, ${s3}`)
  await run('pd tnt clear', 300)
  await run('pd tnt launch 1,5,9,13 2')
  await sleep(1500)
  check('individual portal selection (4 portals x 2)', (await count('@e[type=minecraft:tnt_minecart]')) === 8)
  await run('pd tnt clear', 300)
  await run('pd tnt wave 3 2 1 square')
  await sleep(900)
  const w1 = await count('@e[type=minecraft:tnt_minecart]')
  await sleep(2000)
  const w2 = await count('@e[type=minecraft:tnt_minecart]')
  await sleep(2000)
  const w3 = await count('@e[type=minecraft:tnt_minecart]')
  check('tnt wave 3 2 over the square: 16, 32, 48', w1 === 16 && w2 === 32 && w3 === 48, `${w1}, ${w2}, ${w3}`)
  await run('pd tnt clear', 300)

  // 4. persistence
  log('Persistence')
  for (const b of bots.values()) { try { b.end() } catch {} }
  bots.clear()
  await sleep(2000)
  const code = await srv.stop()
  check('server stopped gracefully', code === 0, 'exit code ' + code)
  srv.lines = []
  srv.start()
  await srv.waitForLine(/Done \(/, 240000, 0, 'server restart')
  await sleep(6000)
  out = await run('pd square info', 800)
  check('square survives a restart', /4 portals per side/.test(out) && /facing inward/.test(out), out.trim().split('\n').slice(-3).join(' | '))
  sc = readScene('default')
  check('portal blocks intact after restart', (await badBlocks(sc.portals)) === 0)
  recs = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))
  const mark = { x: sc.portals[0].x, y: sc.portals[0].y, z: sc.portals[0].z }
  await run(`setblock ${mark.x} ${mark.y} ${mark.z} minecraft:diamond_block`, 300)
  out = await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 180000)
  check('clear removes the square and leaves a changed block alone', /left alone/.test(out), out.replace(/^\[[^\]]*\] /, ''))
  check('everything else was restored', (await wrongRestored(recs, [mark])) === 0, recs.length + ' blocks')
  check('square info is gone after clear', /no square/.test(await run('pd square info')))
}

main().catch(e => {
  console.error('[square-test] ERROR:', e.stack || e.message)
  checks.push({ name: 'harness ran to the end', ok: false, detail: e.message })
}).finally(async () => {
  for (const b of bots.values()) { try { b.end() } catch {} }
  await srv.stop().catch(() => {})
  fs.writeFileSync(path.join(REPORT, 'square-latest.json'), JSON.stringify({ when: new Date().toISOString(), checks }, null, 2))
  fs.writeFileSync(path.join(REPORT, 'square-console.log'), srv.lines.join('\n'))
  const failed = checks.filter(c => !c.ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
})
