'use strict'
/*
 * Live test on an isolated Paper server: single named portals and their Nether partners, same Nether coordinates after
 * delete and rebuild (also across a restart and in a different build order), removal taking the Nether portal down, /pd list
 * showing the link, the TNT limit counting every launch, and A to Z ordering of the command listings.
 * Console only, no bots. Nothing here is visual verification.
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
const log = (...a) => console.log('[nether-link-test]', ...a)
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
  for (const w of ['world', 'world_nether', 'world_the_end']) fs.rmSync(path.join(SERVER_DIR, w), { recursive: true, force: true })
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
const last = out => out.trim().split('\n').pop().replace(/^\[[^\]]*\] /, '').slice(0, 220)
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
const netherScene = () => {
  const f = path.join(PLUGIN_DIR, 'nether', 'links.yml')
  return fs.existsSync(f) ? readScene(f) : { portals: [] }
}
const anchorOf = p => (p.axis === 'X' ? [p.x + 1, p.y + 1, p.z] : [p.x, p.y + 1, p.z + 1])
const key = p => anchorOf(p).join(' ')
async function netherPortalPresent (p) {
  clearMarks()
  for (let i = 0; i < 4; i++) {
    for (let j = 0; j < 5; j++) {
      const x = p.axis === 'X' ? p.x + i : p.x
      const z = p.axis === 'Z' ? p.z + i : p.z
      const frame = i === 0 || i === 3 || j === 0 || j === 4
      const want = frame ? 'minecraft:obsidian' : `minecraft:nether_portal[axis=${p.axis.toLowerCase()}]`
      srv.command(`execute in minecraft:the_nether unless block ${x} ${p.y + j} ${z} ${want} run say BAD`)
    }
  }
  await flush('NCHECK' + Date.now())
  return srv.lines.filter(l => /\[Server\] BAD/.test(l)).length === 0
}
async function netherInteriorGone (p) {
  clearMarks()
  for (let i = 1; i < 3; i++) {
    for (let j = 1; j < 4; j++) {
      const x = p.axis === 'X' ? p.x + i : p.x
      const z = p.axis === 'Z' ? p.z + i : p.z
      srv.command(`execute in minecraft:the_nether if block ${x} ${p.y + j} ${z} minecraft:nether_portal run say BAD`)
    }
  }
  await flush('NGONE' + Date.now())
  return srv.lines.filter(l => /\[Server\] BAD/.test(l)).length === 0
}

const LINKED = /is linked to the Nether portal at (-?\d+), (-?\d+), (-?\d+)/
const NOLINK = /was built, but its Nether portal was not/
/** Builds a single portal and returns the Nether anchor it reports, or null. */
async function create (name, x, y, z, face = 'north') {
  const out = await runUntil(`pd create portal ${name} ${x} ${y} ${z} ${face} world`, new RegExp(`${name} is linked to the Nether portal at|${name} was built, but|Too close|would replace|[Uu]se /pd`), 60000)
  const m = out.match(LINKED)
  return m ? [+m[1], +m[2], +m[3]] : null
}
async function remove (name) {
  const out = await runUntil(`pd remove ${name}`, new RegExp(`Portal ${name} removed`), 60000)
  const m = out.match(/Its Nether portal at (-?\d+), (-?\d+), (-?\d+) is being removed too/)
  return m ? [+m[1], +m[2], +m[3]] : null
}
const same = (a, b) => !!a && !!b && a.join(' ') === b.join(' ')
const sortedBy = (arr, f = x => x) => arr.every((v, i) => i === 0 || f(arr[i - 1]).toLowerCase() <= f(v).toLowerCase())

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

  // ---- creation links to a Nether portal at once
  log('single portals and their Nether partners')
  const nA = await create('EntranceA', CX, Y, CZ)
  check('creating a portal reports the Nether portal it is linked to', !!nA, String(nA))
  const nB = await create('EntranceB', CX + 14, Y, CZ)
  check('a second portal 14 blocks away gets its own Nether portal', !!nB && !same(nA, nB), `${nA} ${nB}`)
  let scene = netherScene()
  check('both Nether portals are saved and exist in the Nether', scene.portals.length === 2 && (await Promise.all(scene.portals.map(netherPortalPresent))).every(Boolean), String(scene.portals.length))
  check('the reported coordinates are the saved ones', scene.portals.some(p => key(p) === nA.join(' ')) && scene.portals.some(p => key(p) === nB.join(' ')))
  let out = await run('pd list')
  check('/pd list shows the Nether coordinates of each portal', out.includes(`Nether ${nA.join(' ')}`) && out.includes(`Nether ${nB.join(' ')}`), last(out))
  check('the memory file records both spots', fs.existsSync(path.join(PLUGIN_DIR, 'nether', 'memory.yml')) && (fs.readFileSync(path.join(PLUGIN_DIR, 'nether', 'memory.yml'), 'utf8').match(/\|/g) || []).length >= 2)

  // ---- delete takes the Nether portal down, rebuilding puts it back at the same coordinates
  log('delete and rebuild')
  const gone = await remove('EntranceA')
  check('removing a portal says its Nether portal is removed too, with coordinates', same(gone, nA), String(gone))
  const goneA = scene.portals.find(p => key(p) === nA.join(' '))
  check('the Nether portal is taken down and restored', await waitFor(() => netherScene().portals.length === 1, 30000) && await netherInteriorGone(goneA))
  const nA2 = await create('EntranceA', CX, Y, CZ)
  check('the same Overworld coordinates give the same Nether coordinates again', same(nA, nA2), `${nA} -> ${nA2}`)
  scene = netherScene()
  check('and that Nether portal is really built', scene.portals.length === 2 && await netherPortalPresent(scene.portals.find(p => key(p) === nA2.join(' '))))

  // ---- remove both, build in the opposite order: still the same two places
  log('opposite order')
  await remove('EntranceB')
  await remove('EntranceA')
  check('both Nether portals are gone', await waitFor(() => netherScene().portals.length === 0, 40000))
  const nB3 = await create('EntranceB', CX + 14, Y, CZ)
  const nA3 = await create('EntranceA', CX, Y, CZ)
  check('rebuilding in the opposite order still gives each portal its old Nether coordinates', same(nA, nA3) && same(nB, nB3), `${nA3} ${nB3}`)

  // ---- different coordinates are different Nether coordinates
  const nC = await create('EntranceC', CX + 28, Y, CZ)
  check('another spot has another Nether portal', !!nC && !same(nC, nA) && !same(nC, nB), String(nC))

  // ---- survives a restart
  log('restart')
  await srv.stop().catch(() => {})
  const restartFrom = srv.lines.length
  srv.start()
  await srv.waitForLine(/Done \(/, 300000, restartFrom, 'server restart')
  await sleep(8000) // the forceloaded chunks are remembered by the server
  await remove('EntranceB')
  await waitFor(() => netherScene().portals.length === 2, 30000)
  const nB4 = await create('EntranceB', CX + 14, Y, CZ)
  check('after a restart, delete and rebuild still gives the same Nether coordinates', same(nB, nB4), `${nB} -> ${nB4}`)

  // ---- the TNT limit counts every launch
  log('tnt limit')
  await run('pd create portal EntranceD ' + (CX + 42) + ' ' + Y + ' ' + CZ + ' north world', 6000)
  await run('pd tnt mode safe')
  await run('pd tnt limit 5')
  out = await run('pd tnt limit')
  check('/pd tnt limit says launched in total', /launched in total/.test(out) && /0 so far/.test(out), last(out))
  out = await run('pd tnt launch all 2', 1500)
  check('launching 8 with a limit of 5 warns that 3 will be skipped', /last 3 will be skipped/.test(out), last(out))
  await sleep(2500)
  out = await run('pd tnt status')
  check('status shows 5 of 5 launched', /Launched: 5 of at most 5/.test(out), last(out))
  out = await run('pd tnt launch all', 1500)
  check('a further launch is refused with the reason', /not launched|limit of 5/.test(out) || /skipped/.test(out), last(out))
  out = await run('pd tnt clear')
  check('clear removes them and starts the count again', /removed 5 TNT minecart/.test(out), last(out))
  out = await run('pd tnt status')
  check('the count is back to 0', /Launched: 0 of at most 5/.test(out), last(out))
  await run('pd tnt mode real')
  await run('pd tnt fuse 1')
  await run('pd tnt limit 4')
  await run('pd tnt launch all', 1500)
  await sleep(4500)
  out = await run('execute if entity @e[type=minecraft:tnt_minecart]', 400)
  check('after they exploded none are alive', /Test failed/.test(out), last(out))
  out = await run('pd tnt launch all', 1500)
  check('exploded minecarts still count: nothing more is launched', /not launched|limit of 4/.test(out), last(out))
  out = await run('pd tnt status')
  check('status still says 4 of 4', /Launched: 4 of at most 4/.test(out), last(out))
  await run('pd tnt clear')

  // ---- A to Z listings
  log('ordering')
  out = await run('pd tnt help', 600)
  const body = l => l.replace(/^\[[^\]]*\]: /, '')
  const sub = out.split('\n').map(body).map(l => (l.match(/^\/pd tnt ([\w-]+)/) || [])[1]).filter(Boolean)
  check('/pd tnt lists its commands A to Z', sub.length >= 9 && sortedBy(sub), sub.join(' '))
  const full = out.split('\n').map(body).filter(l => /^\/pd tnt /.test(l)).map(l => l.split(/ {2}/)[0])
  check('including the launch variants', sortedBy(full), full.join(' | '))
  out = await run('pd group', 600)
  const g = out.split('\n').map(body).map(l => (l.match(/^\/pd group ([\w-]+)/) || [])[1]).filter(Boolean)
  check('/pd group lists its commands A to Z', g.length >= 4 && sortedBy(g), g.join(' '))
  out = await run('pd settings all', 800)
  const titles = out.split('\n').map(body).filter(l => /^ {2}(Area|Delayed generation|Grid|Heights|Limits|Other layouts|Placement|Random|Spacing|Timing|TNT)\b/.test(l)).map(l => l.trim())
  check('/pd settings all lists its sections A to Z', titles.length >= 8 && sortedBy(titles), titles.join(' | '))
  const rows = []
  let inTnt = false
  for (const l of out.split('\n').map(body)) {
    if (/^ {2}TNT/.test(l)) { inTnt = true; continue }
    if (inTnt && /^ {4}tnt\./.test(l)) rows.push(l.trim().split(/\s+/)[0])
  }
  check('and the TNT settings A to Z', rows.length >= 8 && sortedBy(rows), rows.join(' '))

  await run('pd clear', 3000)
  await waitFor(() => netherScene().portals.length === 0, 60000)
  check('clearing everything takes every Nether portal down', netherScene().portals.length === 0)

  const errors = srv.lines.filter(l => /ERROR|Exception/.test(l) && /PortalDirector|portaldirector/i.test(l))
  check('no plugin errors in the console', errors.length === 0, errors.slice(0, 2).join(' | ').slice(0, 300))
  fs.writeFileSync(path.join(REPORT, 'nether-link-latest.json'), JSON.stringify({ checks }, null, 2))
  fs.writeFileSync(path.join(REPORT, 'nether-link-console.log'), srv.lines.join('\n'))
  await srv.stop().catch(() => {})
  const failed = checks.filter(c => !c.ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
}

main().catch(async e => {
  console.error(e)
  fs.writeFileSync(path.join(REPORT, 'nether-link-console.log'), srv.lines.join('\n'))
  await srv.stop().catch(() => {})
  process.exit(2)
})
