'use strict'
/*
 * Cinematic reveal live test on an isolated Paper server (no bots needed, everything is driven from the console).
 * Watches /pd list while portals are being created, to measure when each portal really appears and in which order,
 * and checks stop, busy protection, restart in the middle of a sequence, errors for orders that do not fit, and that
 * clearing restores everything. Nothing here is visual verification.
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
const log = (...a) => console.log('[reveal-test]', ...a)
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

function readScene (name = 'default') {
  const lines = fs.readFileSync(path.join(PLUGIN_DIR, 'scenes', name + '.yml'), 'utf8').split(/\r?\n/)
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
const cxOf = p => (p.axis === 'X' ? p.x + 2 : p.x + 0.5)
const czOf = p => (p.axis === 'Z' ? p.z + 2 : p.z + 0.5)

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

/**
 * Starts /pd generate and watches /pd list until `expected` portals exist (or the time runs out).
 * Returns when each id was first seen, in milliseconds after the command, in the order they appeared.
 */
async function watchGenerate (expected, timeout = 120000, cmd = 'pd generate') {
  const seen = new Map()
  const t0 = performance.now()
  srv.command(cmd)
  const end = t0 + timeout
  while (performance.now() < end && seen.size < expected) {
    const sent = performance.now()
    const from = srv.lines.length
    srv.command('pd list')
    await sleep(110)
    for (const line of srv.lines.slice(from)) {
      const m = line.match(/#(\d+)\s+-?\d+ -?\d+ -?\d+/)
      if (m && !seen.has(+m[1])) seen.set(+m[1], sent - t0)
    }
  }
  return [...seen.entries()].map(([id, t]) => ({ id, t })).sort((a, b) => a.t - b.t || a.id - b.id)
}

function gaps (seq) {
  const out = []
  for (let i = 1; i < seq.length; i++) out.push((seq[i].t - seq[i - 1].t) / 1000)
  return out
}
const mean = a => a.reduce((x, y) => x + y, 0) / Math.max(1, a.length)

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
  await runUntil(`forceload add ${CX - 110} ${CZ - 110} ${CX + 110} ${CZ + 110}`, /Marked \d+ chunks/, 600000)
  await sleep(4000)
  await run('pd set mode air')
  await run(`pd set center ${CX} ${CY} ${CZ} world`)
  await run('pd set layout grid')
  await run('pd set grid rows 4')
  await run('pd set grid columns 5')
  await run('pd set facing south')

  // defaults and settings
  let out = await run('pd settings')
  check('defaults: instant generation, delayed settings hidden', /generation\s+instant/.test(out) && !/generation delay\s+\d/.test(out))
  out = await run('pd set generation delayed')
  check('pd set generation delayed explains the pace', /generation is now delayed/.test(out) && /one by one/.test(out), out.trim().split('\n').pop().slice(0, 120))
  out = await run('pd set generation delay 0.5')
  check('pd set generation delay 0.5', /generation delay is now 0.5/.test(out), out.trim().split('\n').pop())
  out = await run('pd set generation delay 0')
  check('a delay that is too small is refused kindly', /generation delay: must be between 0.05 and 60$/m.test(out), out.trim().split('\n').pop())
  out = await run('pd set generation order sideways')
  check('an unknown order lists the real ones', /use one of: .*left-to-right.*clockwise/.test(out), out.trim().split('\n').pop().slice(0, 140))
  out = await run('pd set generation order clockwise')
  check('an order that does not fit the grid is explained when set', /no clockwise/.test(out) && /left-to-right/.test(out), out.trim().split('\n').pop().slice(0, 140))
  out = await run('pd generate')
  check('and generate refuses it with a suggestion, building nothing', /no clockwise/.test(out) && /generation order left-to-right/.test(out) && !/Planning/.test(out))
  out = await run('pd settings')
  check('settings show delay, order and effects once delayed', /generation delay\s+0.5/.test(out) && /generation order\s+clockwise/.test(out) && /generation effects\s+off/.test(out))
  await run('pd set generation order left-to-right')

  // instant still works
  await run('pd set generation instant')
  let seq = await watchGenerate(20)
  check('instant: all 20 portals exist within two seconds', seq.length === 20 && seq[19].t < 2500, `${seq.length} portals, last after ${seq.length ? (seq[seq.length - 1].t / 1000).toFixed(1) : '-'} s`)
  check('instant: nothing else is left over', (await badBlocks(readScene().portals)) === 0)
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)

  // 4x5 grid, 0.5 s, left to right
  await run('pd set generation delayed')
  await run('pd set generation delay 0.5')
  await run('pd set generation order left-to-right')
  let recs
  seq = await watchGenerate(20)
  let sc = readScene()
  {
    const byId = Object.fromEntries(sc.portals.map(p => [p.id, p]))
    const expected = sc.portals.map(p => ({ id: p.id, col: cxOf(p), row: Math.floor((p.id - 1) / 5) }))
      .sort((a, b) => a.col - b.col || a.row - b.row).map(p => p.id)
    const got = seq.map(s => s.id)
    check('grid 4x5, 0.5 s, left-to-right: 20 portals one by one', seq.length === 20 && sc.portals.length === 20, `${seq.length} seen`)
    check('grid left-to-right: the real order is column by column, bottom to top in each column', JSON.stringify(got) === JSON.stringify(expected), got.join(','))
    const g = gaps(seq)
    check('grid 0.5 s: portals appear half a second apart (measured)', g.length === 19 && Math.abs(mean(g) - 0.5) < 0.08 && g.every(x => x > 0.3 && x < 0.8), `mean ${mean(g).toFixed(2)} s, min ${Math.min(...g).toFixed(2)}, max ${Math.max(...g).toFixed(2)}`)
    check('grid: ids 1-20 and coordinates match the layout', sc.portals.length === 20 && new Set(sc.portals.map(p => p.id)).size === 20)
    check('grid: every frame and purple interior is complete on the server', (await badBlocks(sc.portals)) === 0, '400 blocks')
    check('the whole sequence took about 10 s (19 gaps x 0.5)', Math.abs(seq[19].t - seq[0].t - 9500) < 900, `${((seq[19].t - seq[0].t) / 1000).toFixed(1)} s`)
    recs = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))
    void byId
  }
  out = await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)
  check('clear restores every changed block after a delayed build', (await wrongRestored(recs)) === 0, recs.length + ' blocks')

  // bottom-to-top and top-to-bottom, faster
  for (const [order, dir] of [['bottom-to-top', 1], ['top-to-bottom', -1]]) {
    await run('pd set generation delay 0.2')
    await run('pd set generation order ' + order)
    seq = await watchGenerate(20)
    sc = readScene()
    const rowOf = id => Math.floor((id - 1) / 5)
    const rows = seq.map(s => rowOf(s.id))
    const ok = rows.every((r, i) => i === 0 || (dir === 1 ? r >= rows[i - 1] : r <= rows[i - 1]))
    const g = gaps(seq)
    check(`grid ${order} at 0.2 s: rows revealed in order`, seq.length === 20 && ok, rows.join(''))
    check(`grid ${order}: 0.2 s spacing measured`, Math.abs(mean(g) - 0.2) < 0.08, `mean ${mean(g).toFixed(2)} s`)
    await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)
  }

  // multi-layer square, clockwise then counterclockwise
  await run('pd set layout square')
  await run('pd set square per-side 2')
  await run('pd set square layers 3')
  await run('pd set facing inward')
  await run('pd set generation delay 0.5')
  await run('pd set generation order clockwise')
  seq = await watchGenerate(24)
  sc = readScene()
  {
    const got = seq.map(s => s.id)
    check('square 2 per side x 3 layers, clockwise: 24 portals', seq.length === 24 && sc.portals.length === 24)
    check('square clockwise: layer by layer, clockwise around each layer (ids in ring order)', JSON.stringify(got) === JSON.stringify([...Array(24).keys()].map(i => i + 1)), got.join(','))
    const layerY = id => sc.portals.find(p => p.id === id).y
    check('square: the first 8 appear on the lowest layer, then the middle, then the top', [1, 8].every(id => layerY(id) === layerY(1)) && layerY(9) > layerY(1) && layerY(17) > layerY(9), `${layerY(1)}, ${layerY(9)}, ${layerY(17)}`)
    // geometry: going around a layer really goes clockwise seen from above (north up, east right)
    const first = sc.portals.filter(p => p.id <= 8)
    const mx = first.reduce((a, p) => a + cxOf(p), 0) / 8
    const mz = first.reduce((a, p) => a + czOf(p), 0) / 8
    const ang = id => { const p = sc.portals.find(q => q.id === id); let a = Math.atan2(cxOf(p) - mx, -(czOf(p) - mz)); if (a < 0) a += 2 * Math.PI; return a }
    const seqAng = seq.slice(0, 8).map(s => ang(s.id))
    let turns = 0
    for (let i = 1; i < seqAng.length; i++) if (seqAng[i] > seqAng[i - 1]) turns++
    check('square clockwise: the angle around the middle keeps increasing (geometry, not just ids)', turns >= 6, seqAng.map(a => (a * 180 / Math.PI).toFixed(0)).join(','))
    const g = gaps(seq)
    check('square: 0.5 s spacing measured', Math.abs(mean(g) - 0.5) < 0.08 && g.every(x => x > 0.3 && x < 0.8), `mean ${mean(g).toFixed(2)} s`)
    check('square: all 24 portals complete on the server', (await badBlocks(sc.portals)) === 0, '480 blocks')
  }
  recs = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)
  check('square clear restores everything', (await wrongRestored(recs)) === 0, recs.length + ' blocks')
  await run('pd set generation delay 0.2')
  await run('pd set generation order counterclockwise')
  seq = await watchGenerate(24)
  {
    const got = seq.map(s => s.id)
    const expected = [...[8, 7, 6, 5, 4, 3, 2, 1], ...[16, 15, 14, 13, 12, 11, 10, 9], ...[24, 23, 22, 21, 20, 19, 18, 17]]
    check('square counterclockwise: each layer reversed, layers still bottom to top', JSON.stringify(got) === JSON.stringify(expected), got.join(','))
  }
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)
  out = await run('pd set generation order left-to-right')
  check('left-to-right is explained as not fitting a square', /square goes around/.test(out) && /clockwise/.test(out), out.trim().split('\n').pop().slice(0, 130))

  // random layout
  await run('pd set layout random')
  await run('pd set count 12')
  await run('pd set radius 60')
  await run('pd set generation delay 0.5')
  await run('pd set generation order random')
  seq = await watchGenerate(12, 120000)
  sc = readScene()
  {
    const g = gaps(seq)
    check('random layout, delayed 0.5 s: 12 portals one by one', seq.length === sc.portals.length && seq.length >= 10, `${seq.length} portals`)
    check('random: 0.5 s spacing measured', Math.abs(mean(g) - 0.5) < 0.1, `mean ${mean(g).toFixed(2)} s`)
    check('random: shuffled, not in id order', JSON.stringify(seq.map(s => s.id)) !== JSON.stringify([...seq.map(s => s.id)].sort((a, b) => a - b)))
    check('random: every portal complete', (await badBlocks(sc.portals)) === 0)
  }
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)
  await run('pd set generation order bottom-to-top')
  seq = await watchGenerate(12, 120000)
  sc = readScene()
  {
    const ys = seq.map(s => sc.portals.find(p => p.id === s.id).y)
    check('random layout sorted spatially: bottom-to-top reveals the lowest first', ys.every((y, i) => i === 0 || y >= ys[i - 1]), ys.join(','))
  }
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)

  // effects
  await run('pd set generation effects on')
  await run('pd set generation delay 0.2')
  const logFrom = srv.lines.length
  seq = await watchGenerate(12, 120000)
  check('effects on: the sequence still completes and nothing errors', seq.length >= 10 && !srv.lines.slice(logFrom).some(l => /Exception|\bERROR\b/.test(l)), `${seq.length} portals`)
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)
  await run('pd set generation effects off')

  // stop and busy
  await run('pd set layout grid')
  await run('pd set generation delay 1')
  await run('pd set generation order bottom-to-top')
  srv.command('pd generate')
  await sleep(5200)
  out = await run('pd generate', 400)
  check('while a sequence runs, generate is refused with the progress', /still working/.test(out) && /\d+\/20 portals created/.test(out), out.trim().split('\n').pop().slice(0, 140))
  out = await run('pd clear', 400)
  check('and clear is refused too', /still working/.test(out))
  out = await run('pd stop', 1500)
  check('pd stop cancels safely and keeps what exists', /Stopping/.test(out), out.trim().split('\n').pop())
  await sleep(1500)
  sc = readScene()
  const built = sc.portals.length
  check('after stop the created portals are tracked (some, not all, in id order from the bottom)', built >= 3 && built < 20 && sc.portals.every(p => p.id <= built), `${built} portals`)
  check('stopped sequence: the portals that exist are complete', (await badBlocks(sc.portals)) === 0)
  out = await run('pd list', 600)
  check('pd list shows exactly the created portals', (out.match(/#\d+\s+-?\d+ -?\d+ -?\d+/g) || []).length === built)
  recs = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))
  out = await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)
  check('clear after stop restores everything', (await wrongRestored(recs)) === 0, recs.length + ' blocks')
  out = await run('pd generate', 300)
  await run('pd stop', 500)
  await sleep(800)

  // restart in the middle of a sequence
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored|nothing to remove/, 120000).catch(() => {})
  await sleep(1500)
  srv.command('pd generate')
  await sleep(6500)
  const code = await srv.stop()
  check('server stopped gracefully in the middle of a sequence', code === 0, 'exit code ' + code)
  srv.lines = []
  srv.start()
  await srv.waitForLine(/Done \(/, 240000, 0, 'restart')
  await sleep(6000)
  sc = readScene()
  const listFrom = srv.lines.length
  srv.command('pd list')
  await srv.waitForLine(/--- \d+ portals/, 60000, listFrom, 'pd list after restart')
  await sleep(500)
  out = srv.lines.slice(listFrom).join(String.fromCharCode(10))
  const listed = (out.match(/#\d+\s+-?\d+ -?\d+ -?\d+/g) || []).length
  check('after a restart the partial sequence is still tracked', sc.portals.length >= 3 && sc.portals.length < 20 && listed === sc.portals.length, `${sc.portals.length} saved, ${listed} listed`)
  check('the partial portals are complete', (await badBlocks(sc.portals)) === 0)
  recs = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))
  out = await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)
  check('clearing the partial sequence after a restart restores everything', (await wrongRestored(recs)) === 0, recs.length + ' blocks')
}

main().catch(e => {
  console.error('[reveal-test] ERROR:', e.stack || e.message)
  checks.push({ name: 'harness ran to the end', ok: false, detail: e.message })
}).finally(async () => {
  await srv.stop().catch(() => {})
  fs.writeFileSync(path.join(REPORT, 'reveal-latest.json'), JSON.stringify({ when: new Date().toISOString(), checks }, null, 2))
  fs.writeFileSync(path.join(REPORT, 'reveal-console.log'), srv.lines.join('\n'))
  const failed = checks.filter(c => !c.ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
})
