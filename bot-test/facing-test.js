'use strict'
/*
 * Live test of random facing on an isolated Paper server: 10, 40 and 100 portals in the random layout, with the directions
 * read back from the saved scene (even spread, scattered, the frame following the facing), spacing and radius, complete
 * blocks, TNT minecarts leaving each portal the way it faces, a fixed facing staying fixed, and the facing reset when the
 * layout changes to random. Console only; nothing here is visual verification.
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
const log = (...a) => console.log('[facing-test]', ...a)
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

function readScene () {
  const lines = fs.readFileSync(path.join(PLUGIN_DIR, 'scenes', 'default.yml'), 'utf8').split(/\r?\n/)
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
const geom = p => ({ cx: p.axis === 'X' ? p.x + 2 : p.x + 0.5, cz: p.axis === 'Z' ? p.z + 2 : p.z + 0.5, stepX: { EAST: 1, WEST: -1 }[p.facing] || 0, stepZ: { SOUTH: 1, NORTH: -1 }[p.facing] || 0 })
const box = p => {
  const g = geom(p)
  const x0 = p.axis === 'X' ? p.x : p.x - (g.stepX < 0 ? 3 : 1)
  const x1 = p.axis === 'X' ? p.x + 3 : p.x + (g.stepX > 0 ? 3 : 1)
  const z0 = p.axis === 'Z' ? p.z : p.z - (g.stepZ < 0 ? 3 : 1)
  const z1 = p.axis === 'Z' ? p.z + 3 : p.z + (g.stepZ > 0 ? 3 : 1)
  return [x0, p.y, z0, x1, p.y + 4, z1]
}
const separated = (a, b, gh, gv) => {
  const A = box(a)
  const B = box(b)
  return A[3] + gh < B[0] || B[3] + gh < A[0] || A[5] + gh < B[2] || B[5] + gh < A[2] || A[4] + gv < B[1] || B[4] + gv < A[1]
}
function clearMarks () { srv.lines = srv.lines.filter(l => !/\[Server\] BAD/.test(l)) }
async function flush (tag) {
  const from = srv.lines.length
  srv.command('say ' + tag)
  await srv.waitForLine(new RegExp(tag), 240000, from)
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

const built = /Built \d+ of \d+|went wrong|cannot be built|Nothing fits/

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
  await run(`pd set center ${CX} ${Y} ${CZ} world`)
  await run('pd set layout random')
  await run('pd set radius 100')
  await run('pd tnt mode safe')

  const countsOf = ps => { const c = { NORTH: 0, SOUTH: 0, EAST: 0, WEST: 0 }; ps.forEach(p => { c[p.facing]++ }); return c }
  const scattered = ps => {
    let sameOrder = 0
    for (let i = 1; i < ps.length; i++) if (ps[i].facing === ps[i - 1].facing) sameOrder++
    let sameNear = 0
    for (const p of ps) {
      let best = Infinity
      let nf = null
      for (const q of ps) {
        if (q === p) continue
        const d = Math.hypot(geom(p).cx - geom(q).cx, geom(p).cz - geom(q).cz)
        if (d < best) { best = d; nf = q.facing }
      }
      if (nf === p.facing) sameNear++
    }
    return { sameOrder, sameNear }
  }

  for (const [n, mode] of [[10, 'mixed'], [40, 'mixed'], [100, 'air']]) {
    log(`${n} portals, mode ${mode}`)
    await run('pd set mode ' + mode)
    const out = await runUntil(n === 10 ? `pd create ${n}` : `pd create ${n} replacement`, built, 600000)
    await sleep(2500)
    const sc = readScene()
    const ps = sc.portals
    const c = countsOf(ps)
    const vals = Object.values(c)
    const spread = Math.max(...vals) - Math.min(...vals)
    check(`${n}: ${n === ps.length ? 'all' : 'most'} portals were built`, ps.length >= n * 0.9, `${ps.length} of ${n}, ${last(out)}`)
    check(`${n}: all four directions are used and the counts are within 1 of each other`, vals.every(v => v > 0) && spread <= (ps.length === n ? 1 : 2), JSON.stringify(c))
    const approx = ps.length / 4
    check(`${n}: roughly ${Math.round(approx)} face each direction`, vals.every(v => Math.abs(v - approx) <= 1.5), JSON.stringify(c))
    check(`${n}: the frame axis follows each portal's facing`, ps.every(p => p.axis === (p.facing === 'NORTH' || p.facing === 'SOUTH' ? 'X' : 'Z')))
    const sc2 = scattered(ps)
    check(`${n}: directions are scattered, not grouped`, sc2.sameOrder <= ps.length * 0.45 && (n < 40 || sc2.sameNear < ps.length * 0.5), `same as previous ${sc2.sameOrder}, same as nearest neighbour ${sc2.sameNear} of ${ps.length}`)
    let ok = true
    for (let i = 0; i < ps.length && ok; i++) for (let j = i + 1; j < ps.length; j++) if (!separated(ps[i], ps[j], 8, 5)) { ok = false; break }
    check(`${n}: spacing 8 across / 5 above is kept between every pair`, ok)
    const eff = Math.floor(100 * 1.05)
    check(`${n}: every frame is inside the radius (105 effective)`, ps.every(p => [0, 3].every(i => Math.abs((p.axis === 'X' ? p.x + i : p.x) - CX) <= eff && Math.abs((p.axis === 'Z' ? p.z + i : p.z) - CZ) <= eff)))
    check(`${n}: ${ps.length * 20} frame and portal blocks verified on the server`, (await badBlocks(ps)) === 0)
    if (n === 40) {
      // TNT leaves each portal the way it faces
      for (const f of ['NORTH', 'SOUTH', 'EAST', 'WEST']) {
        const p = ps.find(q => q.facing === f)
        const g = geom(p)
        // no player is near, so keep the chunks around the portal loaded and ticking
        await run(`forceload add ${Math.floor(g.cx) - 40} ${Math.floor(g.cz) - 40} ${Math.floor(g.cx) + 40} ${Math.floor(g.cz) + 40}`, 1500)
        await run(`pd tnt launch ${p.name}`, 450)
        const posOut = await run('data get entity @e[type=minecraft:tnt_minecart,limit=1] Pos', 400)
        const m = posOut.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
        const fwd = m ? (+m[1] - g.cx) * g.stepX + (+m[3] - g.cz) * g.stepZ : -99
        const side = m ? Math.abs((+m[1] - g.cx) * g.stepZ) + Math.abs((+m[3] - g.cz) * g.stepX) : 99
        check(`a TNT minecart leaves ${p.name} (facing ${f.toLowerCase()}) that way`, fwd > 0.8 && side < 1.2, `forward ${fwd.toFixed(2)}, sideways ${side.toFixed(2)}`)
        await run('pd tnt clear', 300)
        await run(`forceload remove ${Math.floor(g.cx) - 40} ${Math.floor(g.cz) - 40} ${Math.floor(g.cx) + 40} ${Math.floor(g.cz) + 40}`, 500)
      }
    }
  }

  log('fixed facing and layout changes')
  await run('pd set facing north')
  await runUntil('pd create 10 replacement', built, 300000)
  await sleep(2000)
  let ps = readScene().portals
  check('an explicit facing north keeps all 10 portals facing north', ps.length >= 9 && ps.every(p => p.facing === 'NORTH' && p.axis === 'X'), ps.length + ' portals')
  let out = await run('pd set layout random')
  check('choosing the layout random again does not undo an explicit facing', !/Facing is random again/.test(out))
  await run('pd set layout grid')
  out = await run('pd set layout random')
  check('switching to the random layout from another layout resets the facing to random and says so', /Facing is random again/.test(out), last(out))
  out = await run('pd settings', 800)
  check('settings show facing random', /facing\s+random/.test(out))
  await runUntil('pd create 40 replacement', built, 600000)
  await sleep(2500)
  ps = readScene().portals
  const c2 = countsOf(ps)
  check('and the next 40 are random again, evenly', Object.values(c2).every(v => v >= 8 && v <= 12), JSON.stringify(c2))
  out = await run('pd help layouts', 900)
  check('help layouts explains random facing', /Random layout \+ random facing/.test(out) && /about a quarter/.test(out))
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 300000)
}

main().catch(e => {
  console.error('[facing-test] ERROR:', e.stack || e.message)
  checks.push({ name: 'harness ran to the end', ok: false, detail: e.message })
}).finally(async () => {
  await srv.stop().catch(() => {})
  fs.writeFileSync(path.join(REPORT, 'facing-latest.json'), JSON.stringify({ when: new Date().toISOString(), checks }, null, 2))
  fs.writeFileSync(path.join(REPORT, 'facing-console.log'), srv.lines.join('\n'))
  const failed = checks.filter(c => !c.ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
})
