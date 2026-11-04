'use strict'
/*
 * Layout live test: grid, wave and square formations on an isolated Paper server.
 * Checks the real portal coordinates from the saved data against an independent calculation, every block on the server,
 * independent horizontal and vertical spacing, all four facings for grid and wave (with a bot walking out of a portal and a
 * TNT minecart leaving it), square layers, protected builds, ground mode, the center settings, restart persistence
 * and safe clearing. Bots cannot see anything; nothing here is visual verification.
 */
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const mineflayer = require('@wp2508/mineflayer')
const { TestServer } = require('./lib/server')

const PORT = +(process.env.PD_PORT || 25591)
const HOST = '127.0.0.1'
const SERVER_DIR = path.resolve(process.env.PD_SERVER_DIR || path.join(__dirname, '..', '..', 'portal-director-test-server'))
const PLUGIN_DIR = path.join(SERVER_DIR, 'plugins', 'PortalDirector')
const REPORT = path.join(__dirname, 'reports')
const BOT = 'PdBot1'

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log('[layout-test]', ...a)
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
  if (PORT === 25590 || PORT === 25592) throw new Error('Refusing to touch the play servers')
  fs.rmSync(PLUGIN_DIR, { recursive: true, force: true })
  fs.rmSync(path.join(SERVER_DIR, 'world'), { recursive: true, force: true })
  fs.writeFileSync(path.join(SERVER_DIR, 'ops.json'), JSON.stringify([{ uuid: offlineUuid(BOT), name: BOT, level: 4, bypassesPlayerLimit: false }], null, 2))
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

function readScene (name = 'default') {
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
function separated (a, b, gapH, gapV) {
  const A = geom(a).box
  const B = geom(b).box
  return A.maxX + gapH < B.minX || B.maxX + gapH < A.minX || A.maxZ + gapH < B.minZ || B.maxZ + gapH < A.minZ ||
    A.maxY + gapV < B.minY || B.maxY + gapV < A.minY
}
function noIntersections (portals, gapH, gapV) {
  for (let i = 0; i < portals.length; i++) for (let j = i + 1; j < portals.length; j++) if (!separated(portals[i], portals[j], gapH, gapV)) return false
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
async function wrongRestored (recs) {
  clearMarks()
  for (const r of recs) srv.command(`execute unless block ${r.x} ${r.y} ${r.z} ${r.original} run say RBAD`)
  await flush('RCHECKDONE' + Date.now())
  return srv.lines.filter(l => /\[Server\] RBAD/.test(l)).length
}
async function surface (x, z) {
  await run(`execute positioned ${x} 0 ${z} positioned over motion_blocking_no_leaves run summon marker ~ ~ ~ {Tags:["pdh"]}`, 120)
  const out = await run('data get entity @e[type=minecraft:marker,tag=pdh,limit=1] Pos', 250)
  await run('kill @e[type=minecraft:marker,tag=pdh]', 80)
  const m = out.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
  return m ? Math.round(+m[2]) - 1 : null
}

// Independent expectations (the layout rules written down again, not copied from the plugin)
const WAVE = [0, 1, 2, 1, 0, -1, -2, -1]
const centerIndex = n => Math.floor((n - 1) / 2)
const offset = (i, n, pitch) => -Math.floor(((n - 1) * pitch) / 2) + i * pitch
function wallPortal (id, CX, CZ, along, floor, facing) {
  const alongX = facing === 'NORTH' || facing === 'SOUTH'
  const cx = alongX ? CX + along : CX
  const cz = alongX ? CZ : CZ + along
  const axis = alongX ? 'X' : 'Z'
  return { id, axis, facing, y: floor, x: axis === 'X' ? cx - 2 : cx, z: axis === 'Z' ? cz - 2 : cz }
}
function expectGrid (o) {
  const out = []
  let id = 1
  for (let r = 0; r < o.rows; r++) {
    const floor = o.cy - 1 + (r - centerIndex(o.rows)) * (5 + o.gapV)
    for (let c = 0; c < o.cols; c++) out.push(wallPortal(id++, o.CX, o.CZ, offset(c, o.cols, 4 + o.gapH), floor, o.facing))
  }
  return out
}
function expectWave (o) {
  const out = []
  let id = 1
  const pitch = 4 + o.gapH
  for (let r = 0; r < o.rows; r++) {
    const base = o.cy - 1 + (r - centerIndex(o.rows)) * (5 + o.gapV + 4)
    const shift = r % 2 === 0 ? -Math.trunc(pitch / 4) : Math.trunc(pitch / 4)
    for (let i = 0; i < o.perRow; i++) out.push(wallPortal(id++, o.CX, o.CZ, offset(i, o.perRow, pitch) + shift, base + WAVE[(i + 4 * (r % 2)) % 8], o.facing))
  }
  return out
}
function sameAsExpected (actual, expected) {
  if (actual.length !== expected.length) return `count ${actual.length} vs ${expected.length}`
  const a = [...actual].sort((p, q) => p.id - q.id)
  for (let i = 0; i < a.length; i++) {
    const e = expected[i]
    const p = a[i]
    if (p.id !== e.id || p.x !== e.x || p.y !== e.y || p.z !== e.z || p.axis !== e.axis || p.facing !== e.facing) {
      return `portal ${p.id}: got ${p.x},${p.y},${p.z} ${p.axis} ${p.facing}, expected ${e.x},${e.y},${e.z} ${e.axis} ${e.facing}`
    }
  }
  return null
}

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
  const CY = 130
  log(`center ${CX}, ${CY}, ${CZ}`)
  await runUntil(`forceload add ${CX - 110} ${CZ - 110} ${CX + 110} ${CZ + 110}`, /Marked \d+ chunks/, 600000)
  await sleep(4000)
  await run('forceload add 985 985 1045 1045', 600)
  await sleep(3000)
  await run('fill 985 199 985 1045 199 1045 stone')
  await run('fill 985 200 985 1045 205 1045 air')
  const bot = await connect(BOT)
  await sleep(1500)
  await run('pd tnt mode safe')
  await run('pd set mode air')
  await run(`pd set center ${CX} ${CY} ${CZ} world`)

  // command surface
  let out = await run('pd settings')
  check('settings shows the layout and how many portals it will make', /Layout: random/.test(out) && /count/.test(out), out.split('\n').find(l => /Layout:/.test(l)) || '')
  out = await run('pd set layout circle')
  check('circle is gone as a layout', /use one of: random, grid, square, wave/.test(out), out.trim().split('\n').pop())
  out = await run('pd square create 60')
  check('/pd square is gone', /I don't know/.test(out))
  out = await run('pd preset list')
  check('/pd preset stays gone', /I don't know/.test(out))
  out = await run('pd set rows 4')
  check('a bare rows setting says which layout it is for', /set grid rows 4/.test(out) && /wave rows/.test(out), out.trim().split('\n').pop().slice(0, 100))
  await run('pd set layout grid')
  await run('pd set grid rows 4')
  await run('pd set grid columns 5')
  out = await run('pd settings')
  check('settings for grid show rows, columns and the expected total', /Layout: grid: 4 rows x 5 columns = 20 portals/.test(out) && /grid rows\s+4/.test(out) && /grid columns\s+5/.test(out) && /spacing horizontal/.test(out) && /spacing vertical/.test(out) && /facing/.test(out))
  out = await run('pd create 30')
  check('generate with a number is refused for grid and points at rows and columns', /works out the number of portals itself/.test(out) && /grid rows/.test(out), out.trim().split('\n').pop().slice(0, 120))
  out = await run('pd help layouts')
  check('layout help explains rows, columns, layers and per-side', /grid rows 4/.test(out) && /square per-side 5/.test(out) && /wave per-row 5/.test(out) && /Rows and layers go up/.test(out))

  // grid in all four directions, with TNT for each
  await run('pd set spacing horizontal 8')
  await run('pd set spacing vertical 5')
  const results = []
  for (const kind of ['grid', 'wave']) {
    for (const facing of ['south', 'north', 'east', 'west']) {
      const FACING = facing.toUpperCase()
      await run(`pd set layout ${kind}`)
      await run(`pd set facing ${facing}`)
      out = await runUntil('pd create', /Built \d+ of \d+ expected portals|cannot be built|went wrong|Nothing fits/, 120000)
      const sc = readScene()
      const expected = kind === 'grid'
        ? expectGrid({ rows: 4, cols: 5, gapH: 8, gapV: 5, facing: FACING, CX, CZ, cy: CY })
        : expectWave({ rows: 4, perRow: 5, gapH: 8, gapV: 5, facing: FACING, CX, CZ, cy: CY })
      const diff = sameAsExpected(sc.portals, expected)
      check(`${kind} 4x5 facing ${facing}: exactly 20 portals at the exact expected coordinates`, !diff, diff || out.replace(/^\[[^\]]*\] /, ''))
      check(`${kind} ${facing}: no frames or walkways intersect`, noIntersections(sc.portals, 8, 5))
      check(`${kind} ${facing}: every portal faces ${facing} and is upright`, sc.portals.every(p => p.facing === FACING && p.axis === (facing === 'north' || facing === 'south' ? 'X' : 'Z')))
      const bad = await badBlocks(sc.portals)
      check(`${kind} ${facing}: 400 frame and portal blocks verified on the server`, bad === 0, bad + ' wrong')
      // a minecart comes out of the portal in the middle of the second row
      const mid = sc.portals.find(p => p.id === 8)
      const g = geom(mid)
      await run('pd tnt launch 8 1', 100)
      await sleep(450)
      const posOut = await run('data get entity @e[type=minecraft:tnt_minecart,limit=1] Pos', 400)
      const pm = posOut.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
      const fwd = pm ? (+pm[1] - g.cx) * g.stepX + (+pm[3] - g.cz) * g.stepZ : -99
      const sw = pm ? Math.abs((+pm[1] - g.cx) * g.stepZ) + Math.abs((+pm[3] - g.cz) * g.stepX) : 99
      check(`${kind} ${facing}: the TNT minecart leaves portal 8 ${facing}`, fwd > 0.8 && sw < 1, `forward ${fwd.toFixed(2)}, sideways ${sw.toFixed(2)}`)
      await run('pd tnt clear', 300)
      const recs = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))
      out = await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)
      check(`${kind} ${facing}: clear restores all ${recs.length} changed blocks`, (await wrongRestored(recs)) === 0)
      results.push(`${kind}-${facing}`)
    }
  }

  // random facing keeps the grid positions
  await run('pd set layout grid')
  await run('pd set facing random')
  await runUntil('pd create', /Built \d+ of \d+ expected portals|cannot be built/, 120000)
  {
    const sc = readScene()
    const faces = new Set(sc.portals.map(p => p.facing))
    const rows = new Set(sc.portals.map(p => p.y))
    check('grid with random facing: 20 portals, several directions, still 4 level rows', sc.portals.length === 20 && faces.size > 1 && rows.size === 4, `${faces.size} directions, ${rows.size} heights`)
    check('grid with random facing: no intersections and all valid', noIntersections(sc.portals, 8, 5) && (await badBlocks(sc.portals)) === 0)
  }
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)
  await run('pd set facing south')

  // spacing: horizontal and vertical are independent
  await run('pd set layout grid')
  await run('pd set spacing horizontal 3')
  await run('pd set spacing vertical 9')
  await runUntil('pd create', /Built \d+ of \d+ expected portals|cannot be built/, 120000)
  {
    const sc = readScene()
    const exp = expectGrid({ rows: 4, cols: 5, gapH: 3, gapV: 9, facing: 'SOUTH', CX, CZ, cy: CY })
    const diff = sameAsExpected(sc.portals, exp)
    const cols = [...new Set(sc.portals.map(p => p.x))].sort((a, b) => a - b)
    const rowsY = [...new Set(sc.portals.map(p => p.y))].sort((a, b) => a - b)
    check('horizontal 3 / vertical 9: columns 7 apart, rows 14 apart', !diff && cols[1] - cols[0] === 7 && rowsY[1] - rowsY[0] === 14, diff || `columns ${cols.join(',')} rows ${rowsY.join(',')}`)
    check('horizontal 3 / vertical 9: no intersections', noIntersections(sc.portals, 3, 9) && (await badBlocks(sc.portals)) === 0)
  }
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)
  await run('pd set spacing horizontal 12')
  await run('pd set spacing vertical 2')
  await runUntil('pd create', /Built \d+ of \d+ expected portals|cannot be built/, 120000)
  {
    const sc = readScene()
    const cols = [...new Set(sc.portals.map(p => p.x))].sort((a, b) => a - b)
    const rowsY = [...new Set(sc.portals.map(p => p.y))].sort((a, b) => a - b)
    check('horizontal 12 / vertical 2: columns 16 apart, rows 7 apart', cols[1] - cols[0] === 16 && rowsY[1] - rowsY[0] === 7, `columns ${cols.join(',')} rows ${rowsY.join(',')}`)
  }
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)
  await run('pd set spacing horizontal 8')
  await run('pd set spacing vertical 5')

  // wave pattern
  await run('pd set layout wave')
  await run('pd set wave rows 4')
  await run('pd set wave per-row 5')
  await runUntil('pd create', /Built \d+ of \d+ expected portals|cannot be built/, 120000)
  {
    const sc = readScene()
    const rowsOf = r => sc.portals.filter(p => p.id > r * 5 && p.id <= r * 5 + 5)
    const shapes = [0, 1, 2, 3].map(r => rowsOf(r).map(p => p.y - Math.min(...rowsOf(r).map(q => q.y))).join(','))
    check('wave 4x5: four rows of exactly five, each row rises and falls', [0, 1, 2, 3].every(r => rowsOf(r).length === 5 && new Set(rowsOf(r).map(p => p.y)).size >= 3), shapes.join(' | '))
    check('wave: neighbouring rows are staggered and half a wave apart', rowsOf(0)[0].x !== rowsOf(1)[0].x && shapes[0] !== shapes[1], `${rowsOf(0)[0].x} vs ${rowsOf(1)[0].x}`)
    check('wave is not a grid', new Set(sc.portals.map(p => p.y)).size > 8)
  }
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)

  // square with layers
  await run('pd set layout square')
  await run('pd set square per-side 5')
  await run('pd set square layers 3')
  await run('pd set facing inward')
  out = await run('pd settings')
  check('settings for square show per-side, layers and 60 portals', /square: 5 per side x 4 sides x 3 layers = 60 portals/.test(out))
  out = await runUntil('pd create', /Built \d+ of \d+ expected portals|cannot be built/, 180000)
  {
    const sc = readScene()
    const n = 5
    check('square 5 per side, 3 layers: exactly 60 portals', sc.portals.length === 60 && /Built 60 of 60/.test(out), out.replace(/^\[[^\]]*\] /, ''))
    const layer = k => sc.portals.filter(p => p.id > k * 20 && p.id <= k * 20 + 20)
    const floors = [0, 1, 2].map(k => new Set(layer(k).map(p => p.y)))
    check('square layers: each layer is on one level, below / at / above the center, 10 apart', floors.every(f => f.size === 1) && [...floors[1]][0] === CY - 1 && [...floors[0]][0] === CY - 11 && [...floors[2]][0] === CY + 9,
      floors.map(f => [...f][0]).join(','))
    check('square layers are identical (same columns, rows and directions)', [1, 2].every(k => layer(k).every((p, i) => p.x === layer(0)[i].x && p.z === layer(0)[i].z && p.axis === layer(0)[i].axis && p.facing === layer(0)[i].facing)))
    const side = (k, s) => layer(k).slice(s * n, s * n + n)
    const extent = (k, s) => { const a = side(k, s).map(p => (s % 2 === 0 ? p.x : p.z)); return Math.max(...a) + 3 - Math.min(...a) + 1 }
    check('square: all four sides equally long with 5 portals each', [0, 1, 2, 3].every(s => extent(0, s) === extent(0, 0) && side(0, s).length === 5), [0, 1, 2, 3].map(s => extent(0, s)).join(','))
    check('square: sides turned the right way (north/south along X, east/west along Z)', [0, 1, 2, 3].every(s => side(0, s).every(p => p.axis === (s % 2 === 0 ? 'X' : 'Z'))))
    const midX = sc.square['center-x']
    const midZ = sc.square['center-z']
    check('square: every portal faces the middle', sc.portals.every(p => { const g = geom(p); return g.stepX * (midX - g.cx) + g.stepZ * (midZ - g.cz) > 0 }))
    check('square: no corner overlaps or duplicates', noIntersections(sc.portals, 8, 5) && new Set(layer(0).map(p => p.x + ',' + p.z)).size === 20)
    check('square: the middle is open', sc.portals.every(p => { const b = geom(p).box; return !(midX >= b.minX && midX <= b.maxX && midZ >= b.minZ && midZ <= b.maxZ) }))
    check('square 60 portals: all blocks verified on the server', (await badBlocks(sc.portals)) === 0, '1200 blocks')
    // TNT through the layers
    out = await run('pd tnt launch side east', 800)
    await sleep(1200)
    check('tnt launch side east reaches the east portals of all three layers', (await count('@e[type=minecraft:tnt_minecart]')) === 15, out.trim().split('\n').pop().slice(0, 100))
    await run('pd tnt clear', 300)
    out = await run('pd create')
    check('generate on an existing square explains replace and clear', /already a square|already 60 portals/.test(out) && /replace/.test(out) && /\/pd clear/.test(out), out.trim().split('\n').pop().slice(0, 120))
  }
  // outward
  const firstSquare = []
  {
    firstSquare.push(...readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks')))
    await run('pd set facing outward')
    await runUntil('pd create replacement', /Built \d+ of \d+ expected portals|cannot be built/, 240000)
    const sc = readScene()
    const midX = sc.square['center-x']
    const midZ = sc.square['center-z']
    check('square replaced with outward facing: 60 portals all facing away from the middle', sc.portals.length === 60 && sc.portals.every(p => { const g = geom(p); return g.stepX * (midX - g.cx) + g.stepZ * (midZ - g.cz) < 0 }))
    out = await run('tp @a 1000 200 1000', 60)
  }
  // restart persistence
  {
    for (const b of bots.values()) { try { b.end() } catch {} }
    bots.clear()
    await sleep(1500)
    const code = await srv.stop()
    check('server stopped gracefully', code === 0, 'exit code ' + code)
    srv.lines = []
    srv.start()
    await srv.waitForLine(/Done \(/, 240000, 0, 'restart')
    await sleep(8000)
    out = await run('pd list', 800)
    check('after a restart the 60 portals are listed with names and coordinates', /60 portals/.test(out) && (out.match(/portal-\d+\s+-?\d+ -?\d+ -?\d+/g) || []).length >= 25, out.split('\n')[1] || '')
    await run('pd tnt mode safe')
    await run('pd tnt launch side north', 500)
    await sleep(1500)
    check('after a restart TNT can still launch through the square sides', (await count('@e[type=minecraft:tnt_minecart]')) === 15)
    await run('pd tnt clear', 300)
    const sc = readScene()
    check('after a restart the portal blocks are all still there', (await badBlocks(sc.portals)) === 0)
    const recs = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))
    out = await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 240000)
    check('clear after a restart restores all ' + recs.length + ' changed blocks', (await wrongRestored(recs)) === 0)
    check('and the terrain is exactly what it was before the first square (' + firstSquare.length + ' blocks of it), after replace, restart and clear', (await wrongRestored(firstSquare)) === 0)
  }
  // protected builds
  {
    await run('pd set layout grid')
    await run('pd set facing south')
    await run(`setblock ${CX} ${CY} ${CZ} minecraft:stone_bricks`, 300)
    out = await runUntil('pd create', /Built \d+ of \d+ expected portals|cannot be built|Nothing fits/, 120000)
    check('a protected build in the way stops the whole formation and says why', /cannot be built here, so nothing was built/.test(out) || /cannot be built/.test(out), out.replace(/^\[[^\]]*\] /, '').slice(0, 160))
    const keep = await run(`execute if block ${CX} ${CY} ${CZ} minecraft:stone_bricks`, 400)
    check('the protected block was not overwritten', /passed/i.test(keep))
    check('nothing else was changed (no portal exists)', (await run('pd list', 500)).includes('no portals'))
    await run(`setblock ${CX} ${CY} ${CZ} minecraft:air`, 300)
    out = await runUntil('pd create', /Built \d+ of \d+ expected portals|cannot be built/, 120000)
    check('without the build in the way the same command works', /Built 20 of 20/.test(out), out.replace(/^\[[^\]]*\] /, ''))
    await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)
  }
  // ground mode: the lowest row stands on the terrain (partial, because the plains have ponds and trees)
  {
    await run('pd set mode ground')
    await run('pd set grid rows 2')
    await run('pd set grid columns 3')
    await run(`pd set center ${CX + 60} ${CY} ${CZ + 60} world`)
    out = await runUntil('pd create skip-blocked', /Built \d+ of \d+ expected portals|cannot be built|went wrong|Nothing fits/, 120000)
    const sc = readScene()
    check('ground mode with partial builds the portals that fit and says what was blocked', sc.portals.length >= 3, `${sc.portals.length} of 6; ` + out.replace(/^\[[^\]]*\] /, ''))
    if (sc.portals.length >= 3) {
      const lowFloor = Math.min(...sc.portals.map(p => p.y))
      const bottom = sc.portals.filter(p => p.y === lowFloor)
      let onGround = 0
      for (const p of bottom) {
        const g = geom(p)
        const surf = await surface(Math.floor(g.cx + g.stepX * 3), Math.floor(g.cz + g.stepZ * 3))
        if (surf !== null && Math.abs(p.y - surf) <= 4) onGround++
      }
      check('ground mode: every bottom-row portal stands on the terrain', onGround === bottom.length && bottom.length >= 1, `${onGround}/${bottom.length}`)
      const upper = sc.portals.filter(p => p.y !== lowFloor)
      check('ground mode: the second row is exactly 10 higher', upper.every(p => p.y === lowFloor + 10), upper.map(p => p.y - lowFloor).join(','))
      check('ground mode: all built portals are valid', (await badBlocks(sc.portals)) === 0)
    }
    const recs = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))
    await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000).catch(() => {})
    check('ground mode: clear restores the terrain', (await wrongRestored(recs)) === 0, recs.length + ' blocks')
    await run('pd set mode air')
  }
}

async function wrongRestoredAfterReplace (recs) {
  clearMarks()
  for (const r of recs.filter(x => !/obsidian|nether_portal/.test(x.original))) srv.command(`execute if block ${r.x} ${r.y} ${r.z} ${r.placed} run say RBAD`)
  await flush('RCHECKDONE' + Date.now())
  return srv.lines.filter(l => /\[Server\] RBAD/.test(l)).length
}

main().catch(e => {
  console.error('[layout-test] ERROR:', e.stack || e.message)
  checks.push({ name: 'harness ran to the end', ok: false, detail: e.message })
}).finally(async () => {
  for (const b of bots.values()) { try { b.end() } catch {} }
  await srv.stop().catch(() => {})
  fs.writeFileSync(path.join(REPORT, 'layout-latest.json'), JSON.stringify({ when: new Date().toISOString(), checks }, null, 2))
  fs.writeFileSync(path.join(REPORT, 'layout-console.log'), srv.lines.join('\n'))
  const failed = checks.filter(c => !c.ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
})
