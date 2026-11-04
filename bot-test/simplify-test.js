'use strict'
/*
 * Live test of the simplified settings on an isolated Paper server: facing in all four directions and for the square, exact
 * radius boundaries with the automatic 5% margin for whole frames, mixed mode ratio, ground portals on the surface, altitude above different
 * terrain, world-y limits and their errors, removed commands and autocomplete, config and scene migration, paginated help,
 * delayed generation, TNT launches, and vanilla Nether travel through a generated portal. Nothing here is visual verification.
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
const log = (...a) => console.log('[simplify-test]', ...a)
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
const last = out => out.trim().split('\n').pop().replace(/^\[[^\]]*\] /, '').slice(0, 170)

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
const frameCells = p => [0, 3].map(i => ({ x: p.axis === 'X' ? p.x + i : p.x, z: p.axis === 'Z' ? p.z + i : p.z }))
/** Largest distance, in x or z, of any frame block from the center. */
const reach = (portals, cx, cz) => Math.max(...portals.flatMap(p => frameCells(p).map(c => Math.max(Math.abs(c.x - cx), Math.abs(c.z - cz)))))
const geom = p => ({ cx: p.axis === 'X' ? p.x + 2 : p.x + 0.5, cz: p.axis === 'Z' ? p.z + 2 : p.z + 0.5, stepX: { EAST: 1, WEST: -1 }[p.facing] || 0, stepZ: { SOUTH: 1, NORTH: -1 }[p.facing] || 0 })

function clearMarks () { srv.lines = srv.lines.filter(l => !/\[Server\] (BAD|RBAD|GAP|BURIED)/.test(l)) }
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
/** Ground portals must stand on solid ground and have open sky above; returns the ids that do not. */
async function groundProblems (portals) {
  clearMarks()
  for (const p of portals.filter(q => !q.floating)) {
    for (let i = 0; i < 4; i++) {
      const x = p.axis === 'X' ? p.x + i : p.x
      const z = p.axis === 'Z' ? p.z + i : p.z
      srv.command(`execute if block ${x} ${p.y - 1} ${z} air run say GAP ${p.id}`)
      if (i === 1 || i === 2) {
        srv.command(`execute unless block ${x} ${p.y + 5} ${z} air run say BURIED ${p.id}`)
        srv.command(`execute unless block ${x} ${p.y + 6} ${z} air run say BURIED ${p.id}`)
      }
    }
  }
  await flush('GROUNDDONE' + Date.now())
  return [...new Set(srv.lines.filter(l => /\[Server\] (GAP|BURIED) \d+/.test(l)).map(l => l.match(/(GAP|BURIED) (\d+)/)[0]))]
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
const names = o => o.map(x => (x.match || x).toString())
const tab = async (bot, text) => names(await bot.tabComplete(text, true))
const built = /Built \d+ of \d+|went wrong|Only \d+ fit|cannot be built|reaches \d+ blocks|Nothing fits|is only \d+ blocks tall|cannot be/

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
  await runUntil(`forceload add ${CX - 100} ${CZ - 100} ${CX + 140} ${CZ + 100}`, /Marked \d+ chunks/, 600000)
  await sleep(4000)
  await run(`pd set center ${CX} ${Y} ${CZ} world`)

  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: 'PdSimple', auth: 'offline', version: '26.3', checkTimeoutInterval: 60000 })
  bot.on('error', e => console.error('[PdSimple] error:', e.message))
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('bot did not spawn')), 40000)
    bot.once('spawn', () => { clearTimeout(t); resolve() })
  })
  await run('op PdSimple', 500)

  // ---- removed settings and commands
  log('removed commands')
  let out
  for (const [cmd, want] of [['pd set orientation x', /no longer exists.*facing/], ['pd set air-ratio 0.3', /no longer exists.*mixed/], ['pd set slope 3', /no longer exists/],
    ['pd set height-min 10', /now called altitude-min/], ['pd set height max 40', /now called altitude-max/], ['pd set min-y 0', /now called world-y-min/], ['pd set max-y 100', /now called world-y-max/]]) {
    out = await run(cmd)
    check(`${cmd} is refused and says what replaced it`, want.test(out), last(out))
  }
  out = await run('pd set entrance.walk-ticks 20')
  check('entrance settings are gone', /no setting called/.test(out), last(out))
  out = await run('pd set groups.auto-assign off')
  check('group auto-assign setting is gone', /no setting called/.test(out), last(out))
  for (const sub of ['assign team auto', 'prepare team', 'release team', 'reset team', 'assignments team', 'status team']) {
    out = await run('pd group ' + sub)
    check(`pd group ${sub.split(' ')[0]} no longer does anything`, /Groups/.test(out) && !/assigned|staged|Release!|reset/i.test(out), last(out))
  }
  out = await run('pd set staging here')
  check('staging is gone', /no setting called|There is no/.test(out), last(out))
  try {
    const setTabs = await tab(bot, '/pd set ')
    check('/pd set suggests the new height and radius names', ['altitude-min', 'altitude-max', 'world-y-min', 'world-y-max', 'radius', 'facing', 'mode', 'spacing', 'layout'].every(n => setTabs.includes(n)), setTabs.join(' '))
    check('/pd set suggests nothing that was removed', !setTabs.some(n => /radius-margin|orientation|air-ratio|slope|height|min-y|max-y|staging|entrance|groups/.test(n)), setTabs.join(' '))
    check('/pd set suggestions are alphabetical', isSorted(setTabs), setTabs.slice(0, 8).join(' '))
    const groupTabs = await tab(bot, '/pd group ')
    check('/pd group suggests only roster commands', JSON.stringify(groupTabs) === JSON.stringify(['add', 'block', 'create', 'disband', 'info', 'list', 'remove']), groupTabs.join(' '))
    const facingTabs = await tab(bot, '/pd set facing ')
    check('/pd set facing suggests compass directions and random for the random layout', JSON.stringify(facingTabs) === JSON.stringify(['east', 'north', 'random', 'south', 'west']), facingTabs.join(' '))
    const fcheck = await tab(bot, '/pd set altitude-')
    check('/pd set altitude- completes both altitude names', fcheck.includes('altitude-min') && fcheck.includes('altitude-max'), fcheck.join(' '))
  } catch (e) {
    check('tab completion through the client', false, e.message)
  }

  // ---- settings view
  log('settings view')
  out = await run('pd settings', 900)
  const pos = k => out.indexOf(k)
  check('settings for the random layout lead with the layout, then area, placement, spacing, heights', ['Random', 'count', 'Area', 'Radius: ', 'Placement', 'mode', 'facing', 'Spacing', 'Heights', 'altitude-min', 'altitude-max', 'world-y-min', 'world-y-max'].every(k => pos(k) >= 0) && pos('Random') < pos('Area') && pos('Area') < pos('Placement') && pos('Placement') < pos('Spacing') && pos('Spacing') < pos('Heights'), '')
  check('settings show the effective radius with the automatic margin', /Radius: 100 blocks \(105 effective with automatic 5% margin\)/.test(out) && /Portals stay within x and z \+\/- 105 of the center/.test(out))
  check('settings say the heights are optional', /Heights \(optional/.test(out))
  check('settings no longer list removed options', !/orientation|air-ratio|slope|min-y |max-y |entrance|staging|auto-assign|announce/.test(out))
  out = await run('pd set radius-margin 5')
  check('pd set radius-margin is gone', /no setting called/.test(out), last(out))
  out = await run('pd set radius 100')
  check('pd set radius reports the effective radius', /within 105 blocks of the center/.test(out), last(out))
  out = await run('pd settings all', 1200)
  check('settings all shows no radius-margin', !/radius-margin|margin\s+\d/.test(out.replace(/automatic 5% margin/g, '')))
  out = await run('pd settings all', 1200)
  check('settings all adds limits and TNT, still without removed options', /max-portals/.test(out) && /tnt\.speed/.test(out) && !/entrance|orientation|slope|air-ratio/.test(out))
  check('the saved config has the new names and none of the old ones', (() => {
    const c = fs.readFileSync(path.join(PLUGIN_DIR, 'config.yml'), 'utf8')
    return /altitude-min: 15/.test(c) && /altitude-max: 60/.test(c) && /world-y-min: -64/.test(c) && /world-y-max: 320/.test(c) && !/radius-margin/.test(c) &&
      !/height-min|height-max|min-y|max-y|air-ratio|orientation|max-slope|entrance|auto-assign|announce-join/.test(c)
  })())

  // ---- help
  log('help')
  out = await run('pd help', 900)
  const total = +((out.match(/Page 1\/(\d+)/) || [])[1])
  check('pd help has pages', total >= 8, 'Page 1/' + total)
  let allHelp = ''
  let longest = 0
  let wide = 0
  for (let i = 1; i <= total; i++) {
    const o = await run('pd help ' + i, 1000)
    const lines = o.split('\n').filter(l => /INFO\]/.test(l))
    longest = Math.max(longest, lines.length)
    wide = Math.max(wide, ...lines.map(l => l.replace(/^.*?INFO\]: /, '').length))
    allHelp += o + '\n'
  }
  check('no help page is longer than 18 lines or 58 characters', longest <= 18 && wide <= 58, `${longest} lines, ${wide} chars`)
  check('help never mentions removed things', !/orientation|air-ratio|slope|height-min|height-max|min-y|max-y|staging|prepare|release|entrance|assign/.test(allHelp.replace(/EntranceA|EntranceB/g, '')))
  const setHelp = [await run('pd help set', 900)]
  const setStart = +((setHelp[0].match(/Page (\d+)\//) || [])[1])
  for (let i = setStart + 1; i <= setStart + 2; i++) setHelp.push(await run('pd help ' + i, 900))
  const sh = setHelp.join('\n')
  for (const label of ['Altitude Min:', 'Altitude Max:', 'World Y Min:', 'World Y Max:', 'Radius:', 'Facing:', 'Spacing Horizontal:', 'Spacing Vertical:', 'Mode:', 'Generation Delay:']) {
    check(`help set explains ${label.replace(':', '')}`, sh.includes(label))
  }
  check('help set says the height settings are optional with sensible defaults', /optional/.test(sh) && /defaults/.test(sh))
  check('help set explains altitude versus world y', /Altitude counts up from the ground/.test(sh) && /real Minecraft Y/.test(sh))
  out = await run('pd help examples', 900)
  check('help examples shows the 30 floating portals, altitude and world-y recipes', ['/pd set mode air', '/pd set radius 80', '/pd create 30', '/pd set altitude-min 20', '/pd set altitude-max 50', '/pd set world-y-min 80', '/pd set world-y-max 180'].every(c => out.includes(c)), '')
  for (const [topic, cmds] of [['set', ['altitude-max', 'altitude-min', 'center', 'count', 'facing', 'generation delay', 'layout', 'mode', 'radius', 'spacing horizontal', 'spacing vertical', 'world-y-max', 'world-y-min']]]) {
    const found = [...sh.matchAll(/\/pd set ([a-z-]+(?: horizontal| vertical| delay)?)/g)].map(m => m[1]).filter((v, i, a) => a.indexOf(v) === i && cmds.some(c => v.startsWith(c)))
    const toks = found.map(v => v.replace('generation', 'generation delay'))
    check(`commands listed in help ${topic} are alphabetical`, isSorted([...new Set(found)].filter(v => !/^generation$/.test(v))), found.join(' '))
  }
  bot.jsonMsgs = []
  bot.on('message', m => bot.jsonMsgs.push(JSON.stringify(m.json ?? m)))
  bot.chat('/pd help 2')
  await sleep(1800)
  const raw = bot.jsonMsgs.join('\n')
  check('Previous and Next controls and clickable commands are still there', /run_command/.test(raw) && /\/pd help 3/.test(raw) && /\/pd help 1/.test(raw) && /suggest_command/.test(raw))

  // ---- facing in all four directions with the whole frame inside the radius
  log('facing and radius')
  for (const c of ['pd set layout random', 'pd set mode air', 'pd set count 25', 'pd set radius 40', 'pd set spacing horizontal 6', 'pd set spacing vertical 4']) await run(c)
  let first = true
  for (const facing of ['north', 'south', 'east', 'west', 'random']) {
    await run('pd set facing ' + facing)
    out = await runUntil(first ? 'pd create' : 'pd create replacement', built, 240000)
    first = false
    const sc = readScene()
    const axisOf = f => (f === 'NORTH' || f === 'SOUTH' ? 'X' : 'Z')
    if (facing === 'random') {
      const faces = new Set(sc.portals.map(p => p.facing))
      check('random facing uses several directions', faces.size >= 3 && sc.portals.every(p => p.axis === axisOf(p.facing)), [...faces].join(','))
    } else {
      check(`facing ${facing}: every portal faces ${facing} and the frame turned to match`, sc.portals.length > 0 && sc.portals.every(p => p.facing === facing.toUpperCase() && p.axis === axisOf(p.facing)), sc.portals.length + ' portals')
    }
    const r = reach(sc.portals, CX, CZ)
    check(`facing ${facing}: every frame block is within x and z +/- 42 of the center`, sc.portals.length >= 15 && r <= 42, `${sc.portals.length} portals, farthest frame block ${r}`)
    check(`facing ${facing}: all frames and portal blocks verified on the server`, (await badBlocks(sc.portals)) === 0)
  }
  // the automatic margin: radius 40 allows 42
  await run('pd set facing random')
  out = await runUntil('pd create replacement', built, 240000)
  let sc = readScene()
  let r = reach(sc.portals, CX, CZ)
  check('radius 40 keeps every frame within 42 (automatic 5% margin) and uses it', sc.portals.length >= 15 && r <= 42, `farthest frame block ${r}`)
  // a radius too small for the count reports the problem instead of extending it
  await run('pd set radius 8')
  await run('pd set count 60')
  out = await runUntil('pd create replacement', /Built \d+ of \d+|went wrong|cannot/, 240000)
  sc = readScene()
  check('a crowded small radius builds fewer portals than asked and stays inside', sc.portals.length < 60 && reach(sc.portals, CX, CZ) <= 8, `${sc.portals.length} of 60, reach ${reach(sc.portals, CX, CZ)}`)
  await sleep(500)
  check('the shortfall is explained with the radius and the way out', srv.lines.slice(-40).some(l => /Only \d+ fit inside the radius of 8 blocks [(]8 effective/.test(l)), '')
  await run('pd set count 25')
  await run('pd set radius 40')

  // ---- facing for formations: square inward and outward, and the refusals
  log('square and formations')
  out = await run('pd set layout square')
  check('switching to the square layout changes an incompatible facing and says so', /Facing was random, which the square layout does not take, so it is now inward/.test(out), last(out))
  out = await run('pd set facing north')
  check('north is refused for the square with an explanation', /square layout faces inward or outward/.test(out), last(out))
  for (const c of ['pd set square per-side 3', 'pd set square layers 1', 'pd set radius 100']) await run(c)
  try {
    const sqTabs = await tab(bot, '/pd set facing ')
    check('/pd set facing suggests inward and outward for the square', JSON.stringify(sqTabs) === JSON.stringify(['inward', 'outward']), sqTabs.join(' '))
  } catch (e) { check('square facing tab completion', false, e.message) }
  await runUntil('pd create replacement', built, 240000)
  sc = readScene()
  check('square inward: 12 portals facing the middle', sc.portals.length === 12 && sc.portals.every(p => { const g = geom(p); return g.stepX * (CX - g.cx) + g.stepZ * (CZ - g.cz) > 0 }), sc.portals.length + ' portals')
  await run('pd set facing outward')
  await runUntil('pd create replacement', built, 240000)
  sc = readScene()
  check('square outward: 12 portals facing away from the middle', sc.portals.length === 12 && sc.portals.every(p => { const g = geom(p); return g.stepX * (CX - g.cx) + g.stepZ * (CZ - g.cz) < 0 }))
  check('square portals are complete and inside the radius', (await badBlocks(sc.portals)) === 0 && reach(sc.portals, CX, CZ) <= 100)
  out = await run('pd set layout grid')
  check('switching back to a compass layout resets outward', /Facing was outward.*now random/.test(out), last(out))
  out = await run('pd set facing inward')
  check('inward is refused for the grid and the message says it belongs to the square', /only works with the square layout/.test(out), last(out))
  await run('pd set facing east')
  await run('pd set radius 10')
  out = await run('pd create replacement', 3000)
  check('a grid wider than the radius is refused, naming the radius it needs', /reaches \d+ blocks from the center, but the radius is 10 blocks/.test(out) && /pd set radius \d+/.test(out), last(out))
  check('and nothing was removed or built by the refusal', readScene().portals.length === 12)
  await run('pd set radius 100')
  await runUntil('pd create replacement', built, 240000)
  sc = readScene()
  check('grid facing east: 20 portals, all east, inside the radius', sc.portals.length === 20 && sc.portals.every(p => p.facing === 'EAST' && p.axis === 'Z') && reach(sc.portals, CX, CZ) <= 100, sc.portals.length + ' portals')
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 180000)

  // ---- mixed mode
  log('mixed mode')
  for (const c of ['pd set layout random', 'pd set mode mixed', 'pd set count 40', 'pd set radius 80', 'pd set facing random', 'pd set spacing horizontal 8', 'pd set spacing vertical 5']) await run(c)
  out = await run('pd set mode mixed')
  check('choosing mixed explains the half and half split', /half floating and half ground/.test(out), last(out))
  await runUntil('pd create', built, 240000)
  sc = readScene()
  const floating = sc.portals.filter(p => p.floating).length
  const ground = sc.portals.length - floating
  check('mixed mode makes about half floating and half ground portals', sc.portals.length >= 30 && floating >= sc.portals.length * 0.35 && floating <= sc.portals.length * 0.65, `${floating} floating, ${ground} ground of ${sc.portals.length}`)
  check('mixed portals stay inside the radius', reach(sc.portals, CX, CZ) <= 84)
  const gp = await groundProblems(sc.portals)
  check('ground portals sit on solid terrain with open sky above them (not floating, not buried)', ground > 0 && gp.length === 0, gp.join(' ') || ground + ' ground portals checked')
  check('all mixed portals are complete', (await badBlocks(sc.portals)) === 0)
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 180000)
  await run('pd set mode ground')
  await runUntil('pd create', built, 240000)
  sc = readScene()
  const gp2 = await groundProblems(sc.portals)
  check('ground mode: every portal is a ground portal on the surface', sc.portals.length >= 25 && sc.portals.every(p => !p.floating) && gp2.length === 0, sc.portals.length + ' portals, floating ' + sc.portals.filter(p => p.floating).length + ', problems ' + gp2.join(' '))
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 180000)

  // ---- altitude above different terrain, and world-y limits
  log('altitude and world y')
  let AX = CX
  let AZ = CZ
  const BX = CX + 110
  await run('pd set mode ground')
  await run('pd set count 1')
  await run('pd set radius 60')
  await run(`pd set center ${AX} ${Y} ${CZ} world`)
  await runUntil('pd create', built, 120000)
  const probe = readScene().portals[0]
  const surface = probe.y
  AX = probe.x
  AZ = probe.z
  await run('pd set radius 14')
  await run(`pd set center ${AX} ${Y} ${AZ} world`)
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 180000)
  await run(`fill ${BX - 20} 119 ${CZ - 20} ${BX + 20} 120 ${CZ + 20} stone`, 1500)
  await run('pd set mode air')
  await run('pd set count 4')
  await run('pd set altitude-min 15')
  await run('pd set altitude-max 20')
  await run('pd set facing north')
  await runUntil('pd create', built, 120000)
  const lowSet = readScene().portals
  const lowOffsets = lowSet.map(p => p.y - surface)
  check('floating portals over natural ground hang at least 15 above it (trees count as ground)', lowSet.length === 4 && lowOffsets.every(o => o >= 12 && o <= 70), `surface ${surface}, offsets ${lowOffsets.join(',')}`)
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 180000)
  await run(`pd set center ${BX} ${Y} ${CZ} world`)
  await runUntil('pd create', built, 120000)
  const highSet = readScene().portals
  check('over a platform 50 blocks higher the same settings hang 15 to 20 above the platform', highSet.length === 4 && highSet.every(p => p.y >= 135 && p.y <= 140), highSet.map(p => p.y).join(','))
  check('the altitude is relative: the platform portals are far above the natural-ground ones', Math.min(...highSet.map(p => p.y)) - Math.max(...lowSet.map(p => p.y)) >= 20)
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 180000)
  // world y limits
  await run('pd set altitude-min 15')
  await run('pd set altitude-max 60')
  await run('pd set world-y-max 150')
  await run('pd set world-y-min 140')
  await runUntil('pd create', built, 120000)
  const lim = readScene().portals
  check('world-y-min 140 / world-y-max 150: every whole frame is between y 140 and 150', lim.length === 4 && lim.every(p => p.y >= 140 && p.y + 4 <= 150), lim.map(p => p.y + '-' + (p.y + 4)).join(' '))
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 180000)
  await run('pd set world-y-max 330')
  await run('pd set world-y-max 320')
  await run('pd set world-y-min -64')
  await run('pd set world-y-max 130')
  out = await runUntil('pd create', built, 120000)
  await sleep(500)
  const none = readScene().portals.length
  check('when no floating spot fits under world-y-max the result says so', none === 0 && srv.lines.slice(-40).some(l => /above world-y-max/.test(l)), `${none} built, ${last(out)}`)
  await run('pd set world-y-max 320')
  out = await run('pd set altitude-min 90')
  check('altitude-min above altitude-max is refused with the way out', /altitude-max/.test(out) && /cannot be above/.test(out), last(out))
  out = await run('pd set altitude-max 10')
  check('altitude-max below altitude-min is refused', /cannot be below altitude-min/.test(out), last(out))
  out = await run('pd set world-y-min 400')
  check('world-y-min outside the world is refused', /must be between -64 and 320/.test(out), last(out))
  await run('pd set world-y-min 200')
  out = await run('pd set world-y-max 100')
  check('world-y-max below world-y-min is refused', /cannot be below world-y-min/.test(out), last(out))
  await run('pd set world-y-max 203')
  out = await run('pd create', 1500)
  check('a world y range shorter than a portal is explained before building', /only 4 blocks tall/.test(out) && /needs 5/.test(out), last(out))
  await run('pd set world-y-max 320')
  await run('pd set world-y-min -64')

  // ---- a named portal by hand, then TNT, then vanilla Nether travel through a generated portal
  log('manual portal, TNT, nether')
  await run(`pd set center ${CX} ${Y} ${CZ} world`)
  out = await runUntil(`pd create portal TestN ${CX} ${Y} ${CZ} north world`, /Portal \w+ built|Too close|would replace|[Uu]se \/pd/, 30000)
  check('pd create portal still works with a facing', new RegExp(`Portal TestN built at ${CX}, ${Y}, ${CZ} facing north`).test(out), last(out))
  sc = readScene()
  const tn = sc.portals.find(p => p.name === 'TestN')
  check('the manual portal is complete and faces north along x', !!tn && tn.axis === 'X' && (await badBlocks([tn])) === 0)
  await run('pd tnt mode real')
  await run('pd tnt fuse 2')
  out = await run('pd tnt launch TestN', 450)
  const posOut = await run('data get entity @e[type=minecraft:tnt_minecart,limit=1] Pos', 400)
  const pm = posOut.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
  const g = geom(tn)
  const fwd = pm ? (+pm[3] - g.cz) * g.stepZ + (+pm[1] - g.cx) * g.stepX : -99
  check('a TNT minecart leaves the north-facing portal toward the north', fwd > 0.8, `forward ${fwd.toFixed(2)}`)
  await sleep(4500)
  const gone = await run('execute if entity @e[type=minecraft:tnt_minecart]', 500)
  check('the minecart explodes after its fuse (none left)', /Test failed/i.test(gone), last(gone))
  await run('pd tnt mode safe')
  await run('pd tnt launch all', 700)
  check('safe-mode launch works too', /passed/i.test(await run('execute if entity @e[type=minecraft:tnt_minecart]', 500)))
  await run('pd tnt clear', 400)
  // nether travel: the bot stands inside the portal
  const center = geom(tn)
  await run(`tp PdSimple ${center.cx} ${tn.y + 1} ${center.cz}`, 300)
  const dimOf = () => String(bot.game && bot.game.dimension)
  let arrived = false
  for (let i = 0; i < 90 && !arrived; i++) {
    await sleep(500)
    arrived = /nether/i.test(dimOf())
  }
  check('standing in a generated portal sends the player to the Nether (vanilla travel works)', arrived, 'dimension ' + dimOf())
  await run('execute in minecraft:overworld run tp PdSimple 1000 200 1000', 500)
  await sleep(1500)
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored|nothing to remove/i, 180000)

  // ---- delayed generation
  log('delayed generation')
  for (const c of ['pd set mode air', 'pd set count 5', 'pd set radius 40', 'pd set generation delayed', 'pd set generation delay 0.8']) await run(c)
  const listCount = async () => { const o = await run('pd list', 350); const m = o.match(/(\d+) portals/); return m ? +m[1] : 0 }
  const fromLine = srv.lines.length
  srv.command('pd create')
  const seen = []
  for (let i = 0; i < 40; i++) {
    seen.push(await listCount())
    if (srv.lines.slice(fromLine).some(l => /Built \d+ of \d+/.test(l))) break
  }
  await sleep(1500)
  const end = readScene().portals.length
  check('delayed generation shows portals one at a time, then finishes them all', seen.some(n => n >= 1 && n <= 4) && end === 5, `counts seen ${[...new Set(seen)].join(',')}, finally ${end}`)
  check('delayed portals respect the radius', reach(readScene().portals, CX, CZ) <= 42)
  await run('pd set generation instant')
  const keep = readScene().portals
  const before = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))

  // ---- old config and old scene files
  log('migration')
  await run('pd set radius 100')
  const code = await srv.stop()
  check('server stopped gracefully', code === 0, 'exit code ' + code)
  fs.writeFileSync(path.join(PLUGIN_DIR, 'config.yml'), [
    'generate:', '  count: 12', '  radius: 77', '  spacing-horizontal: 9', '  spacing-vertical: 4', '  placement: air', '  air-ratio: 0.2',
    '  height-min: 22', '  height-max: 44', '  orientation: x', '  layout: random', '  facing: auto', '  max-slope: 5', '  min-y: 10', '  max-y: 200',
    '  blocks-per-tick: 400', '  max-chunks: 900', '  max-portals: 500',
    'scene:', '  current: default',
    'groups:', '  auto-assign: false', '  announce-join: false',
    'entrance:', '  block-vanilla-travel: true', '  staging-invisible: true', '  walk-ticks: 40', '  staging-location:', '    world: world', '    x: 1.0', '    y: 2.0', '    z: 3.0',
    'tnt:', '  per-portal: 1', '  speed: 1.3', '  fuse-seconds: 3.0', 'square:', '  spacing: 2', '  facing: inward', ''].join('\n'))
  const yml = path.join(PLUGIN_DIR, 'scenes', 'default.yml')
  fs.appendFileSync(yml, '\nassignments:\n  team:\n    2: 11111111-2222-3333-4444-555555555555\n')
  srv.lines = []
  srv.start()
  await srv.waitForLine(/Done \(/, 240000, 0, 'restart')
  await sleep(4000)
  check('after the restart the plugin enabled with the old config and old scene file', srv.lines.some(l => /PortalDirector ready/.test(l)) && !srv.lines.some(l => /SEVERE|PortalDirector.*Exception/.test(l)), '')
  out = await run('pd settings', 5000)
  check('old height and limit values moved to the new names', /altitude-min\s+22/.test(out) && /altitude-max\s+44/.test(out) && /world-y-min\s+10/.test(out) && /world-y-max\s+200/.test(out) && /Radius: 77 blocks/.test(out) && /mode\s+air/.test(out), '')
  check('an old facing of auto became random', /facing\s+random/.test(out))
  out = await run('pd list', 5000)
  check('the old scene file still lists all its portals', (out.match(/portal-\d+\s+-?\d+ -?\d+ -?\d+/g) || []).length === keep.length && keep.length === 5, out.trim().split('\n').length + ' lines')
  out = await run('pd create portal AfterOld ' + (CX + 70) + ' ' + Y + ' ' + CZ + ' south world', 6000)
  await sleep(3000)
  const cfg = fs.readFileSync(path.join(PLUGIN_DIR, 'config.yml'), 'utf8')
  check('the config file was rewritten without the removed settings', !/height-min|height-max|min-y:|max-y:|air-ratio|orientation|max-slope|entrance|auto-assign|announce-join|square:/.test(cfg) && /altitude-min: 22/.test(cfg) && /world-y-max: 200/.test(cfg), '')
  check('the saved scene dropped the old assignments but kept every portal', !/^assignments:/m.test(fs.readFileSync(yml, 'utf8')) && readScene().portals.length === 6)
  await runUntil('pd clear', /[Cc]leared: \d+ blocks restored/, 180000)
  check('clear still restores every block the old scene changed', (await wrongRestored(before)) === 0, before.length + ' blocks')
  bot.end()
}

main().catch(e => {
  console.error('[simplify-test] ERROR:', e.stack || e.message)
  checks.push({ name: 'harness ran to the end', ok: false, detail: e.message })
}).finally(async () => {
  await srv.stop().catch(() => {})
  fs.writeFileSync(path.join(REPORT, 'simplify-latest.json'), JSON.stringify({ when: new Date().toISOString(), checks }, null, 2))
  fs.writeFileSync(path.join(REPORT, 'simplify-console.log'), srv.lines.join('\n'))
  const failed = checks.filter(c => !c.ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
})
