'use strict'
/*
 * Named portals live test on an isolated Paper server: /pd create portal in all four directions, duplicate and invalid names,
 * removal by name and by exact anchor coordinates (and the refusals that must remove nothing), restoring, assignments and TNT
 * by name, alphabetical tab completion through a real client, restart persistence, generated names and scene files from
 * before names existed. Nothing here is visual verification.
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
const log = (...a) => console.log('[names-test]', ...a)
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


const mineflayer = require('@wp2508/mineflayer')

const ALPHA = (x, y) => (x.toLowerCase() < y.toLowerCase() ? -1 : x.toLowerCase() > y.toLowerCase() ? 1 : 0)
const isSorted = l => l.every((v, i) => i === 0 || ALPHA(l[i - 1], v) <= 0)
const anchorOf = p => ({ x: p.axis === 'X' ? p.x + 1 : p.x, y: p.y + 1, z: p.axis === 'Z' ? p.z + 1 : p.z })
const names = o => o.map(x => (x.match || x).toString())

async function tab (bot, text) {
  return names(await bot.tabComplete(text, true))
}

/** Records of the changes around one portal (its frame, interior and walkway) so restoring can be checked per portal. */
function around (recs, p) {
  const [x0, x1, z0, z1] = p.axis === 'X' ? [p.x, p.x + 3, p.z - 4, p.z + 4] : [p.x - 4, p.x + 4, p.z, p.z + 3]
  return recs.filter(r => r.x >= x0 && r.x <= x1 && r.z >= z0 && r.z <= z1 && r.y >= p.y - 1 && r.y <= p.y + 5)
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
  const Y = 130
  await runUntil(`forceload add ${CX - 90} ${CZ - 90} ${CX + 130} ${CZ + 90}`, /Marked \d+ chunks/, 600000)
  await sleep(4000)
  await run('pd set mode air')
  await run(`pd set center ${CX} ${Y} ${CZ} world`)

  // ---- named portals in all four directions
  log('named portals')
  const dirs = [['EntranceA', 'north', 0], ['EntranceB', 'east', 14], ['EntranceC', 'south', 28], ['EntranceD', 'west', 42]]
  for (const [name, dir, dx] of dirs) {
    const out = await runUntil(`pd create portal ${name} ${CX + dx} ${Y} ${CZ} ${dir} world`, /Portal \w+ built|Too close|would replace|[Uu]se \/pd|not a/, 30000)
    check(`create portal ${name} ${dir}`, new RegExp(`Portal ${name} built at ${CX + dx}, ${Y}, ${CZ} facing ${dir}`).test(out) && /floating/.test(out), out.replace(/^\[[^\]]*\] /, ''))
  }
  let out = await run(`pd create portal entrancea ${CX + 56} ${Y} ${CZ} north world`)
  check('a duplicate name is refused, ignoring case, with an explanation', /already exists at/.test(out) && /not case sensitive/.test(out), out.trim().split('\n').pop().slice(0, 160))
  out = await run(`pd create portal 7 ${CX + 56} ${Y} ${CZ} north world`)
  check('a name that is only digits is refused', /not a usable name/.test(out))
  out = await run(`pd create portal all ${CX + 56} ${Y} ${CZ} north world`)
  check('a command word is refused as a name', /command word/.test(out))
  out = await run(`pd create portal TooClose ${CX + 3} ${Y} ${CZ} north world`)
  check('overlapping an existing portal is refused and names it', /Too close to EntranceA/.test(out), out.trim().split('\n').pop().slice(0, 160))
  out = await run('pd create portal Short here')
  check('a missing direction gives usage', /[Oo]nly players|Which way/.test(out))
  await flush('SETTLE' + Date.now())

  let sc = readScene()
  check('exactly the four portals exist', sc.portals.length === 4, sc.portals.length + ' saved')
  const byName = Object.fromEntries(sc.portals.map(p => [p.name, p]))
  check('names and facings are saved', dirs.every(([n, d]) => byName[n] && byName[n].facing === d.toUpperCase()), JSON.stringify(sc.portals.map(p => [p.name, p.facing])))
  check('north/south portals run along x, east/west along z', byName.EntranceA.axis === 'X' && byName.EntranceC.axis === 'X' && byName.EntranceB.axis === 'Z' && byName.EntranceD.axis === 'Z')
  check('all four portals are complete: obsidian frame and active interior', (await badBlocks(sc.portals)) === 0)
  out = await run('pd list', 800)
  const rows = [...out.matchAll(/(Entrance[A-D])\s+(-?\d+) (-?\d+) (-?\d+)/g)]
  check('pd list shows each name with its anchor coordinates', rows.length === 4 && rows.every(r => { const a = anchorOf(byName[r[1]]); return a.x === +r[2] && a.y === +r[3] && a.z === +r[4] }), rows.map(r => r[0]).join(' | '))

  // ---- players, assignments and TNT use the names
  log('players and TNT')
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: 'PdTab', auth: 'offline', version: '26.3', checkTimeoutInterval: 60000 })
  bot.on('error', e => console.error('[PdTab] error:', e.message))
  bot.chats = []
  bot.on('messagestr', m => bot.chats.push(m))
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('bot did not spawn')), 40000)
    bot.once('spawn', () => { clearTimeout(t); resolve() })
  })
  await run('op PdTab', 500)
  await run('pd group create team')
  await run('pd group add team PdTab')
  out = await run('pd group assign team entranced PdTab')
  check('a player can be assigned to a portal by name, ignoring case', /PdTab will come out of portal EntranceD/.test(out), out.trim().split('\n').pop())
  out = await run('pd group assign team nosuch PdTab')
  check('an unknown portal name is explained', /no portal called nosuch/.test(out))
  out = await run('pd group assignments')
  check('assignments are listed by portal name', /EntranceD: PdTab/.test(out), out.trim().split('\n').slice(-3).join(' / '))
  await run('pd tnt mode safe')
  out = await run('pd tnt launch EntranceC', 1500)
  const alive = await run('execute if entity @e[type=minecraft:tnt_minecart]', 500)
  check('tnt launch accepts a portal name and launches one minecart', /passed/i.test(alive), out.trim().split('\n').pop().slice(0, 140))
  await run('pd tnt clear', 400)
  out = await run('pd tnt launch Nosuch', 500)
  check('tnt launch with an unknown name is explained', /not a portal name|None of those portals/.test(out), out.trim().split('\n').pop().slice(0, 140))

  // ---- tab completion through a real client, alphabetical
  log('tab completion')
  try {
    const main = await tab(bot, '/pd ')
    check('/pd suggests the commands in alphabetical order', JSON.stringify(main) === JSON.stringify(['clear', 'create', 'group', 'help', 'list', 'reload', 'remove', 'set', 'settings', 'stop', 'tnt']), main.join(' '))
    for (const [cmd, must] of [['/pd create ', 'portal'], ['/pd set ', 'generation'], ['/pd group ', 'assign'], ['/pd tnt ', 'launch'], ['/pd set generation ', 'order'], ['/pd remove ', 'EntranceA'], ['/pd group assign team ', 'EntranceB'], ['/pd tnt launch ', 'EntranceC']]) {
      const l = await tab(bot, cmd)
      check(`${cmd}suggestions are alphabetical${must ? ' and include ' + must : ''}`, l.length > 0 && isSorted(l) && (!must || l.includes(must)), l.slice(0, 14).join(' '))
    }
    const rm = await tab(bot, '/pd remove ')
    check('/pd remove suggests the existing portal names', ['EntranceA', 'EntranceB', 'EntranceC', 'EntranceD'].every(n => rm.includes(n)), rm.join(' '))
    const g = await tab(bot, '/pd g')
    check('/pd generate and /pd portal are no longer suggested', g.length === 1 && g[0] === 'group')
  } catch (e) {
    check('tab completion through the client', false, e.message)
  }
  // ---- paginated help
  log('help')
  out = await run('pd help', 800)
  const total = +((out.match(/Page 1\/(\d+)/) || [])[1])
  check('pd help shows page 1 of N', total >= 6, 'Page 1/' + total)
  const steps = ['/pd set', '/pd create', '/pd list', '/pd remove', '/pd clear'].map(x => out.indexOf(x))
  check('the first page teaches set, create, list, remove, clear in order', steps.every((v, i) => v >= 0 && (i === 0 || v > steps[i - 1])), steps.join(','))
  let longest = 0
  let allPages = true
  for (let i = 1; i <= total; i++) {
    const o = await run('pd help ' + i, 1000)
    longest = Math.max(longest, o.split('\n').filter(l => /INFO\]/.test(l)).length)
    allPages = allPages && new RegExp(`Page ${i}/${total}`).test(o)
  }
  check('every page 1 to N shows its page number', allPages)
  check('no page is longer than 18 lines', longest > 0 && longest <= 18, longest + ' lines')
  for (const topic of ['create', 'group', 'layouts', 'portals', 'reveal', 'scenes', 'set', 'tnt']) {
    const o = await run('pd help ' + topic, 800)
    check(`pd help ${topic} opens that topic`, /Page \d+\/\d+/.test(o) && o.includes('/pd '), (o.match(/PortalDirector\s+(.*)/) || [])[1])
  }
  out = await run('pd help create', 500)
  check('help create explains more, replacement and skip-blocked and nothing old', /create more/.test(out) && /create replacement/.test(out) && /create skip-blocked/.test(out) && !/create (add|replace|partial|air|ground|mixed)\b/.test(out))
  const createCmds = [...new Set([...out.matchAll(/\/pd create (more|replacement|skip-blocked)\b/g)].map(m => m[1]))]
  check('commands on a help page are alphabetical', isSorted(createCmds), createCmds.join(' '))
  check('a page number out of range is explained', /Pages go from 1 to/.test(await run('pd help 99')))
  check('an unknown topic is explained', /No help topic called nosuch/.test(await run('pd help nosuch')))
  bot.jsonMsgs = []
  bot.on('message', m => bot.jsonMsgs.push(JSON.stringify(m.json ?? m)))
  bot.chat('/pd help 2')
  await sleep(1800)
  const raw = bot.jsonMsgs.join('\n')
  check('page 2 has a Next control that runs /pd help 3', /run_command/.test(raw) && /\/pd help 3/.test(raw))
  check('page 2 has a Previous control that runs /pd help 1', /\/pd help 1/.test(raw))
  check('commands on the page insert examples into the chat box', /suggest_command/.test(raw) && /\/pd create 30/.test(raw))
  try {
    const opts = await tab(bot, '/pd create ')
    check('/pd create suggests only the new option names', ['more', 'replacement', 'skip-blocked'].every(o => opts.includes(o)) && !opts.some(o => ['add', 'replace', 'partial', 'air', 'ground', 'mixed'].includes(o)) && isSorted(opts), opts.join(' '))
    const after = await tab(bot, '/pd create 30 more ')
    check('options already typed are not suggested again, and more excludes replacement', after.includes('skip-blocked') && !after.includes('more') && !after.includes('replacement') && !after.includes('30'), after.join(' '))
  } catch (e) {
    check('create option tab completion', false, e.message)
  }
  for (const [word, want] of [['add', 'more'], ['replace', 'replacement'], ['partial', 'skip-blocked'], ['air', '/pd set mode air'], ['ground', '/pd set mode ground'], ['mixed', '/pd set mode mixed']]) {
    check(`pd create ${word} is refused and points to ${want}`, new RegExp(want.replace(/[/]/g, '\/')).test(await run('pd create ' + word)))
  }
  check('combining options that conflict is refused', /either 'more'.*or 'replacement'/.test(await run('pd create more replacement')))
  out = await run('pd generate')
  check('pd generate and pd portal are gone', /I don't know/.test(out) && /I don't know/.test(await run('pd portal create here north')))
  const hereAt = [CX + 60, CZ]
  await run(`tp PdTab ${hereAt[0]} ${Y + 2} ${hereAt[1]}`, 300)
  await sleep(300)
  bot.chats.length = 0
  bot.chat('/pd create portal HereNorth here north')
  await sleep(2500)
  const hereMsg = bot.chats.find(m => /Portal HereNorth built/.test(m))
  check('create portal here uses the player position', !!hereMsg && new RegExp(`built at ${hereAt[0]}, \\d+, ${hereAt[1]} facing north`).test(hereMsg), hereMsg || bot.chats.join(' | ').slice(0, 160))
  await run('pd remove HereNorth', 3000)

  // ---- removal by name, by coordinates, and the refusals that must never remove anything
  log('removal')
  const before = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))
  out = await run('pd remove NoSuchPortal')
  check('removing an unknown name explains and lists nothing removed', /no portal called NoSuchPortal/.test(out) && /pd list/.test(out), out.trim().split('\n').pop().slice(0, 150))
  out = await run('pd remove Entrance')
  check('a partial name is not enough, but suggests the real ones', /no portal called Entrance/.test(out) && /EntranceA/.test(out), out.trim().split('\n').pop().slice(0, 160))
  out = await run('pd remove 1 2 3')
  check('coordinates that match no portal are refused', /No portal has its anchor at 1 2 3/.test(out) && /Nothing was removed/.test(out), out.trim().split('\n').pop().slice(0, 150))
  const a = anchorOf(byName.EntranceA)
  for (const [dx, dy, dz] of [[1, 0, 0], [0, 1, 0], [0, 0, 1], [-1, 0, 0]]) {
    out = await run(`pd remove ${a.x + dx} ${a.y + dy} ${a.z + dz}`)
    check(`coordinates one block off the anchor (${dx},${dy},${dz}) do not remove EntranceA`, /No portal has its anchor/.test(out) && /closest is EntranceA/.test(out))
  }
  out = await run('pd remove abc def ghi')
  check('non-numeric coordinates give usage', /not a whole number/.test(out))
  check('nothing was removed by all those refusals', readScene().portals.length === 4)

  out = await runUntil('pd remove entrancea', /Portal \w+ removed|no portal called/, 60000)
  check('remove by name (case-insensitive) restores blocks', /Portal EntranceA removed: \d+ blocks restored/.test(out), out.replace(/^\[[^\]]*\] /, ''))
  sc = readScene()
  check('only that portal is gone from the saved scene', sc.portals.length === 3 && !sc.portals.some(p => p.name === 'EntranceA'))
  const recsA = around(before, byName.EntranceA)
  check('every changed block around EntranceA is back to the original', recsA.length > 0 && (await wrongRestored(recsA)) === 0, recsA.length + ' blocks')
  check('the other three portals are untouched', (await badBlocks(sc.portals)) === 0)
  check('the name is free again after removal', /Portal EntranceA built/.test(await runUntil(`pd create portal EntranceA ${CX} ${Y} ${CZ} north world`, /Portal \w+ built|Too close|already/, 30000)))
  await run('pd remove EntranceA', 3000)

  const b = anchorOf(byName.EntranceB)
  out = await runUntil(`pd remove ${b.x} ${b.y} ${b.z}`, /Portal \w+ removed|No portal/, 60000)
  check('remove by exact anchor coordinates removes EntranceB only', /Portal EntranceB removed/.test(out), out.replace(/^\[[^\]]*\] /, ''))
  sc = readScene()
  check('saved scene now has EntranceC and EntranceD', sc.portals.length === 2 && sc.portals.every(p => ['EntranceC', 'EntranceD'].includes(p.name)))
  check('EntranceB blocks are restored', (await wrongRestored(around(before, byName.EntranceB))) === 0)

  out = await runUntil('pd remove EntranceD', /Portal \w+ removed/, 60000)
  await sleep(500)
  check('removing a portal drops the player assignment for it', !/EntranceD/.test(await run('pd group assignments', 600)) && !/^assignments:/m.test(fs.readFileSync(path.join(PLUGIN_DIR, 'scenes', 'default.yml'), 'utf8')))
  bot.end()

  // ---- persistence across a restart
  log('restart')
  const code = await srv.stop()
  check('server stopped gracefully', code === 0, 'exit code ' + code)
  srv.lines = []
  srv.start()
  await srv.waitForLine(/Done \(/, 240000, 0, 'restart')
  await sleep(4000)
  out = await run('pd list', 800)
  check('after a restart the remaining named portal is listed', /EntranceC\s+-?\d+ -?\d+ -?\d+/.test(out) && !/Entrance[ABD]\b/.test(out), out.trim().split('\n').pop())
  check('and it is still complete', (await badBlocks(readScene().portals)) === 0)
  out = await runUntil('pd remove entrancec', /Portal \w+ removed/, 60000)
  check('it can be removed by name after the restart', /Portal EntranceC removed/.test(out))

  // ---- generated portals: names, single removal, partial delayed sequence, old scene files
  log('generated names')
  await run('pd clear', 500)
  await sleep(2000)
  await run('pd set layout grid')
  await run('pd set grid rows 2')
  await run('pd set grid columns 3')
  await run('pd set facing south')
  await runUntil('pd create', /Built \d+ of \d+ expected portals|cannot be built/, 120000)
  sc = readScene()
  check('generated portals get readable names', sc.portals.length === 6 && sc.portals.every(p => p.name === 'portal-' + p.id), sc.portals.map(p => p.name).join(' '))
  out = await run('pd list', 800)
  check('pd list shows the generated names and coordinates', (out.match(/portal-\d+\s+-?\d+ -?\d+ -?\d+/g) || []).length === 6)
  const recs6 = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))
  out = await runUntil('pd remove portal-3', /Portal \w[\w-]* removed/, 60000)
  check('a generated portal can be removed by its name', /Portal portal-3 removed/.test(out))
  const p4 = anchorOf(sc.portals.find(p => p.id === 4))
  out = await runUntil(`pd remove ${p4.x} ${p4.y} ${p4.z}`, /Portal \w[\w-]* removed|No portal/, 60000)
  check('and another by its coordinates', /Portal portal-4 removed/.test(out))
  sc = readScene()
  check('four generated portals remain complete', sc.portals.length === 4 && (await badBlocks(sc.portals)) === 0)
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)
  check('clear restores everything the formation changed', (await wrongRestored(recs6)) === 0, recs6.length + ' blocks')

  await run('pd set generation delayed')
  await run('pd set generation delay 0.4')
  srv.command('pd create')
  await sleep(1700)
  await run('pd stop', 400)
  await sleep(800)
  sc = readScene()
  check('a stopped delayed sequence keeps its portals tracked with names', sc.portals.length >= 2 && sc.portals.length < 6 && sc.portals.every(p => /^portal-\d+$/.test(p.name)), sc.portals.length + ' portals')
  const keep = sc.portals[sc.portals.length - 1]
  out = await runUntil(`pd remove ${keep.name}`, /Portal \w[\w-]* removed/, 60000)
  check('a portal from a partial sequence can be removed by name', new RegExp(`Portal ${keep.name} removed`).test(out))
  await run('pd set generation instant')

  // ---- create options: more, replacement, skip-blocked with counts; mode is only a setting
  log('create options')
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored|nothing to remove|no portals/i, 120000)
  await sleep(1500)
  for (const c of ['pd set layout random', 'pd set count 3', 'pd set radius 50', 'pd set mode air']) await run(c)
  const built = /Built \d+ of \d+|went wrong|Only|cannot/
  await runUntil('pd create 3', built, 120000)
  sc = readScene()
  const firstSet = sc.portals.map(p => p.x + ',' + p.y + ',' + p.z)
  check('pd create 3 builds three random portals', sc.portals.length === 3, sc.portals.length + ' portals')
  out = await run('pd create 3')
  check('creating again without an option explains more and replacement', /create more/.test(out) && /create replacement/.test(out) && /pd clear/.test(out), out.trim().split('\n').pop().slice(0, 140))
  check('and builds nothing', readScene().portals.length === 3)
  await runUntil('pd create 2 more', built, 120000)
  sc = readScene()
  check('more keeps the three portals and adds two', sc.portals.length === 5 && firstSet.every(k => sc.portals.some(p => p.x + ',' + p.y + ',' + p.z === k)), sc.portals.length + ' portals')
  check('the new portals have their own unique names', new Set(sc.portals.map(p => p.name.toLowerCase())).size === 5, sc.portals.map(p => p.name).join(' '))
  await runUntil('pd create 4 replacement', built, 180000)
  sc = readScene()
  check('replacement removes the old portals and builds four new ones', sc.portals.length === 4 && sc.portals.every(p => /^portal-\d+$/.test(p.name)), sc.portals.length + ' portals')
  check('all four are complete', (await badBlocks(sc.portals)) === 0)
  await runUntil('pd create 2 more skip-blocked', built, 120000)
  check('a count can be combined with more and skip-blocked', readScene().portals.length === 6)
  out = await run('pd settings')
  check('the placement mode was not changed by pd create', /mode\s+air/.test(out), (out.match(/mode\s+\S+/) || [])[0])

  // ---- scene files saved before names existed
  log('old scene file')
  await run('pd group create show2')
  const old = readScene()
  const left = old.portals.length
  const stopCode = await srv.stop()
  const ymlFile = path.join(PLUGIN_DIR, 'scenes', 'default.yml')
  fs.writeFileSync(ymlFile, fs.readFileSync(ymlFile, 'utf8').split(/\r?\n/).filter(l => !/^ {2}name: /.test(l)).join('\n'))
  check('the edited scene file really has no names', !/name:/.test(fs.readFileSync(ymlFile, 'utf8')) && stopCode === 0)
  srv.lines = []
  srv.start()
  await srv.waitForLine(/Done \(/, 240000, 0, 'restart')
  await sleep(4000)
  out = await run('pd list', 800)
  const migrated = (out.match(/portal-\d+\s+-?\d+ -?\d+ -?\d+/g) || []).length
  check('an old scene file without names still loads, with portal-<id> names', migrated === left && left > 0, `${migrated} of ${left}`)
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 120000)
}

main().catch(e => {
  console.error('[names-test] ERROR:', e.stack || e.message)
  checks.push({ name: 'harness ran to the end', ok: false, detail: e.message })
}).finally(async () => {
  await srv.stop().catch(() => {})
  fs.writeFileSync(path.join(REPORT, 'names-latest.json'), JSON.stringify({ when: new Date().toISOString(), checks }, null, 2))
  fs.writeFileSync(path.join(REPORT, 'names-console.log'), srv.lines.join('\n'))
  const failed = checks.filter(c => !c.ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
})
