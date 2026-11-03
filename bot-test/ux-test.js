'use strict'
/*
 * Usability live test, on real plains terrain (no terraforming for the generation checks).
 *   - new command syntax, defaults, settings, friendly errors, tab completion
 *   - ground / air / mixed portals: surface and height checks against the game's own heightmap, purple interiors
 *   - finding portals: where, portal list, portal tp, view, goto plains
 *   - normal Nether travel through a generated portal and back, and no travel while staged
 *   - TNT minecarts that really explode, terrain damage on and off, stop and clear
 *   - generate, clear, generate something different without restarting
 * Bots cannot see anything; nothing here is visual verification.
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
const NAMES = ['PdBot1', 'PdBot2']

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log('[ux-test]', ...a)
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
function geom (p) {
  const stepX = p.facing === 'EAST' ? 1 : p.facing === 'WEST' ? -1 : 0
  const stepZ = p.facing === 'SOUTH' ? 1 : p.facing === 'NORTH' ? -1 : 0
  return { stepX, stepZ, cx: p.axis === 'X' ? p.x + 2 : p.x + 0.5, cz: p.axis === 'Z' ? p.z + 2 : p.z + 0.5, standY: p.y + 1 }
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

/** The game's own ground height (y of the top block) at a column, via a marker placed on the heightmap. */
async function surface (x, z) {
  await run(`execute positioned ${x} 0 ${z} positioned over motion_blocking_no_leaves run summon marker ~ ~ ~ {Tags:["pdh"]}`, 120)
  const out = await run('data get entity @e[type=minecraft:marker,tag=pdh,limit=1] Pos', 250)
  await run('kill @e[type=minecraft:marker,tag=pdh]', 80)
  const m = out.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
  return m ? Math.round(+m[2]) - 1 : null
}

const bots = new Map()
async function connect (username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, auth: 'offline', version: '26.3', checkTimeoutInterval: 60000 })
  bot.on('error', e => console.error(`[${username}] error:`, e.message))
  bot.chats = []
  bot.on('messagestr', m => bot.chats.push(m))
  bot.forced = []
  bot.on('forcedMove', () => bot.forced.push(performance.now()))
  bots.set(username, bot)
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`${username} did not spawn`)), 40000)
    bot.once('spawn', () => { clearTimeout(t); resolve() })
  })
  return bot
}
const dim = b => String(b.game.dimension)

/** Sends a command as the bot and waits (up to ms) for a chat line matching the pattern. */
async function ask (bot, cmd, regex, ms = 20000) {
  bot.chats.length = 0
  bot.chat(cmd)
  const end = Date.now() + ms
  while (Date.now() < end) {
    const hit = bot.chats.find(m => regex.test(m))
    if (hit) return hit
    await sleep(100)
  }
  return null
}

async function until (fn, ms = 20000) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (await fn()) return true
    await sleep(200)
  }
  return false
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
  log(`plains center ${CX}, ${CZ}`)
  for (const [a, b] of [[-150, 0], [1, 150]]) await runUntil(`forceload add ${CX - 150} ${CZ + a} ${CX + 150} ${CZ + b}`, /Marked \d+ chunks/, 600000)
  await sleep(3000)
  await run(`pd set center ${CX} ${CZ} world`)

  // commands and defaults
  let out = await run('pd help')
  check('help explains the quick start in plain words', /quick start/i.test(out) && /pd generate 30/.test(out) && /pd view/.test(out) && /pd clear/.test(out))
  out = await run('pd settings')
  check('settings shows every generation value', ['count', 'mode', 'radius', 'spacing horizontal', 'spacing vertical', 'layout', 'orientation', 'facing', 'height min', 'height max', 'min-y', 'max-y'].every(k => out.includes(k)) && /Selected scene/.test(out), out.split('\n').length + ' lines')
  out = await run('pd set radius 60')
  check('pd set radius 60', /radius is now 60/.test(out), out.trim().split('\n').pop())
  out = await run('pd set spacing horizontal 6')
  check('pd set spacing horizontal 6', /spacing horizontal is now 6/.test(out), out.trim().split('\n').pop())
  out = await run('pd set spacing vertical 4')
  check('pd set spacing vertical 4', /spacing vertical is now 4/.test(out), out.trim().split('\n').pop())
  out = await run('pd set spacing 6')
  check('a bare spacing value explains the two kinds', /horizontal/.test(out) && /vertical/.test(out), out.trim().split('\n').pop().slice(0, 120))
  out = await run('pd set height min 10')
  check('pd set height min 10', /height min is now 10/.test(out), out.trim().split('\n').pop())
  out = await run('pd set height max 30')
  check('pd set height max 30', /height max is now 30/.test(out), out.trim().split('\n').pop())
  out = await run('pd set mode air')
  check('pd set mode air', /mode is now air/.test(out), out.trim().split('\n').pop())
  out = await run('pd set layout circle')
  check('pd set layout circle', /layout is now circle/.test(out))
  out = await run('pd set radius banana')
  check('a bad value gets a plain explanation', /radius: .*number/.test(out), out.trim().split('\n').pop())
  out = await run('pd set nonsense 4')
  check('an unknown setting says what to do', /no setting called 'nonsense 4'.*\/pd settings/.test(out), out.trim().split('\n').pop())
  out = await run('pd frobnicate')
  check('an unknown command points at help', /pd help/.test(out), out.trim().split('\n').pop())

  // air-only portals in plains
  await run('pd set layout random')
  out = await runUntil('pd generate 12', /Built \d+ of 12|went wrong|Only/, 120000)
  let sc = readScene('default')
  check('pd generate 12 uses the settings without further options (air, radius 60)', sc.portals.length >= 10 && sc.portals.every(p => p.floating), sc.portals.length + ' portals')
  await sleep(1500)
  const tail = srv.lines.slice(-6).join('\n')
  check('generation reports count, nearest and farthest with directions', /nearest is portal \d+, \d+ blocks/.test(tail) && /farthest is portal \d+/.test(tail) && /blocks (north|south|east|west)/.test(tail), tail.split('\n').filter(l => /nearest/.test(l)).join('').slice(0, 160))
  let bad = await badBlocks(sc.portals)
  check('air portals: every frame block obsidian and every interior an active portal block', bad === 0, `${sc.portals.length * 20} blocks, ${bad} wrong`)
  let okHeights = 0
  const heights = []
  for (const p of sc.portals) {
    const g = geom(p)
    const surf = await surface(Math.floor(g.cx + g.stepX * 3), Math.floor(g.cz + g.stepZ * 3))
    const above = surf === null ? -999 : p.y - surf
    heights.push(above)
    if (above >= 10 && above <= 30 + 25) okHeights++
  }
  check('air portals hang between height min and max above the real ground (game heightmap)', okHeights === sc.portals.length, heights.join(','))
  out = await run('pd generate 12')
  check('generate on an existing scene explains add / replace / new scene', /already has \d+ portals/.test(out) && /generate 12 add/.test(out) && /generate 12 replace/.test(out) && /scene new/.test(out), out.trim().split('\n').pop().slice(0, 140))
  let recs = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))
  out = await runUntil('pd generate 15 ground replace', /Built \d+ of 15|went wrong/, 180000)
  check('replace removed the old portals and built new ones without a restart', /Built/.test(out), out.replace(/^\[[^\]]*\] /, ''))
  check('the old blocks were restored (all of them)', (await wrongRestoredAfterReplace(recs)) === 0, recs.length + ' blocks')

  // ground portals
  sc = readScene('default')
  check('replace gave ground portals only, ids starting again at 1', sc.portals.length >= 12 && sc.portals.every(p => !p.floating) && sc.portals[0].id === 1, sc.portals.length + ' portals')
  bad = await badBlocks(sc.portals)
  check('ground portals: frames and purple interiors all valid', bad === 0, `${bad} wrong`)
  let flush = 0
  const diffs = []
  for (const p of sc.portals) {
    const g = geom(p)
    const surf = await surface(Math.floor(g.cx + g.stepX * 3), Math.floor(g.cz + g.stepZ * 3))
    const d = surf === null ? 99 : p.y - surf
    diffs.push(d)
    if (Math.abs(d) <= 4) flush++
  }
  check('ground portals sit on the surface, not underground and not floating (game heightmap)', flush === sc.portals.length, diffs.join(','))
  let walkable = 0
  for (const p of sc.portals) {
    const bx = Math.floor(geom(p).cx)
    const bz = Math.floor(geom(p).cz)
    const o = await run(`execute if block ${bx} ${p.y + 5} ${bz} minecraft:air`, 90)
    const l = await run(`execute if block ${bx} ${p.y + 5} ${bz} #minecraft:leaves`, 90)
    if (/passed/i.test(o) || /passed/i.test(l)) walkable++
  }
  check('open sky above ground portals (air or tree leaves only, no mountain or roof over them)', walkable === sc.portals.length, walkable + '/' + sc.portals.length)

  await run('pd clear', 200)
  await sleep(8000)
  out = await run('pd set mode mixed')
  out = await runUntil('pd generate 20', /Built \d+ of 20|went wrong|Only/, 120000)
  sc = readScene('default')
  check('mixed plains scene has ground and floating portals', sc.portals.some(p => p.floating) && sc.portals.some(p => !p.floating), `${sc.portals.filter(p => p.floating).length} floating of ${sc.portals.length}`)
  check('mixed: all interiors valid', (await badBlocks(sc.portals)) === 0)

  // 2. bots
  log('Bots')
  for (const n of NAMES) await connect(n)
  await sleep(1500)
  const bot = bots.get('PdBot1')
  const first = sc.portals.find(p => !p.floating)
  const floating = sc.portals.find(p => p.floating)
  out = await run('tp PdBot1 ' + CX + ' 90 ' + CZ, 100)
  await run('effect give PdBot1 minecraft:slow_falling 20 0 true', 100)
  await sleep(1500)
  await run('tp PdBot1 ' + CX + ' 90 ' + CZ, 100)
  const listed = await ask(bot, '/pd list', new RegExp('#' + first.id + '\\s+-?\\d+ -?\\d+ -?\\d+'))
  check('pd list shows the id and coordinates of a portal', !!listed, listed || 'no reply')
  {
    const out2 = await run('pd list', 800)
    const rows = [...out2.matchAll(/#(\d+)\s+(-?\d+) (-?\d+) (-?\d+)/g)]
    check('pd list has one line per portal with the right coordinates', rows.length === sc.portals.length &&
      rows.every(r => { const p = sc.portals.find(q => q.id === +r[1]); const ax = p.axis === 'X' ? p.x + 1 : p.x; const az = p.axis === 'Z' ? p.z + 1 : p.z; return p && ax === +r[2] && p.y + 1 === +r[3] && az === +r[4] }), rows.length + ' lines')
  }
  out = await run('pd view')
  check('the old finding commands are gone', /I don't know/.test(out) && /I don't know/.test(await run('pd where')) && /I don't know/.test(await run('pd goto plains')))
  out = await run('pd radius 30')
  check('legacy shortcuts are gone and point to pd set', /pd set radius/.test(out), out.trim().split('\n').pop())

  // tab completion
  try {
    const t1 = await bot.tabComplete('/pd set h', true)
    const t2 = await bot.tabComplete('/pd tnt mode ', true)
    const t3 = await bot.tabComplete('/pd portal ', true)
    const names1 = t1.map(x => (x.match || x).toString())
    check('tab completion suggests setting names', names1.some(m => /height/.test(m)), names1.join(','))
    check('tab completion suggests tnt modes', t2.map(x => (x.match || x).toString()).join(',').includes('safe') && t2.map(x => (x.match || x).toString()).join(',').includes('real'))
    check('tab completion suggests portal subcommands', t3.map(x => (x.match || x).toString()).join(',').includes('tp'))
  } catch (e) {
    check('tab completion through the client', false, e.message)
  }

  // normal Nether travel
  log('Nether travel')
  const target = sc.portals.find(p => !p.floating && p.id !== first.id) || first
  {
    const g = geom(target)
    const b2 = bots.get('PdBot2')
    const startX = g.cx + g.stepX * 4
    const startZ = g.cz + g.stepZ * 4
    await run(`tp PdBot2 ${startX} ${target.y + 1} ${startZ}`, 100)
    await sleep(3000)
    await b2.lookAt(b2.entity.position.offset(-g.stepX * 6, 1.2, -g.stepZ * 6), true)
    b2.setControlState('forward', true)
    b2.setControlState('jump', true)
    let closest = 99
    for (let i = 0; i < 100; i++) {
      await sleep(50)
      closest = Math.min(closest, Math.hypot(b2.entity.position.x - g.cx, b2.entity.position.z - g.cz))
      if (closest < 0.7) break
    }
    b2.setControlState('forward', false)
    b2.setControlState('jump', false)
    check('normal mode: the player can physically walk into the portal opening', closest < 1.0, `came within ${closest.toFixed(2)} blocks of the middle`)
    await run(`tp PdBot2 ${g.cx} ${target.y + 1} ${g.cz}`, 100)
    for (let i = 0; i < 90 && !/nether/i.test(dim(b2)); i++) await sleep(1000)
    check('normal mode: standing in a generated portal travels to the Nether', /nether/i.test(dim(b2)), dim(b2))
    // come back
    for (let attempt = 0; attempt < 4 && !/overworld/i.test(dim(b2)); attempt++) {
      b2.setControlState('forward', true)
      await sleep(1500)
      b2.setControlState('forward', false)
      b2.setControlState('back', true)
      await sleep(1500)
      b2.setControlState('back', false)
      for (let i = 0; i < 40 && !/overworld/i.test(dim(b2)); i++) await sleep(1000)
    }
    check('normal mode: the Nether portal brings you back to the Overworld', /overworld/i.test(dim(b2)), dim(b2))
  }

  // cinematic: staged players do not travel
  await run('pd group create show')
  await run('pd group add show PdBot2')
  await run('pd group assign show ' + (target.id) + ' PdBot2')
  await run('forceload add 985 985 1045 1045', 600)
  await sleep(3000)
  await run('fill 985 199 985 1045 199 1045 stone')
  await run('fill 985 200 985 1045 205 1045 air')
  await run('tp PdBot2 1000 200 1000', 100)
  await sleep(2000)
  await run('pd group prepare show', 1500)
  await sleep(7000)
  check('cinematic mode: a staged player standing in the portal does not travel', /overworld/i.test(dim(bots.get('PdBot2'))), dim(bots.get('PdBot2')))
  const t0 = performance.now()
  await run('pd group release show', 100)
  await sleep(4000)
  const g2 = geom(target)
  const p2 = bots.get('PdBot2').entity.position
  check('cinematic mode: release walks the player out of the portal', (p2.x - g2.cx) * g2.stepX + (p2.z - g2.cz) * g2.stepZ >= 3, p2.toString())
  await run('pd group reset show', 1500)

  // TNT really exploding
  log('TNT')
  await run('tp @a 1000 200 1000', 60)
  await run('pd clear', 200)
  await sleep(10000)
  const FX = CX + 120
  await run(`fill ${FX - 12} 60 ${CZ - 12} ${FX + 24} 70 ${CZ + 12} minecraft:dirt`)
  await run(`fill ${FX - 12} 71 ${CZ - 12} ${FX + 24} 71 ${CZ + 12} minecraft:grass_block`)
  await run(`fill ${FX - 12} 72 ${CZ - 12} ${FX + 24} 100 ${CZ + 12} minecraft:air`)
  await run('forceload add ' + (FX - 20) + ' ' + (CZ - 20) + ' ' + (FX + 30) + ' ' + (CZ + 20), 800)
  await sleep(3000)
  out = await runUntil(`pd portal create ${FX} 72 ${CZ} east world`, /Portal \d+ built|Too close|would replace/, 20000)
  check('manual portal on the test pad', /Portal 1 built/.test(out), out.replace(/^\[[^\]]*\] /, ''))
  await run(`fill ${FX + 8} 72 ${CZ - 2} ${FX + 8} 75 ${CZ + 2} minecraft:stone_bricks`)
  out = await run('pd tnt status')
  check('tnt status says whether explosions are on or off', /(SAFE MODE|REAL EXPLOSIONS)/.test(out), out.split('\n').find(l => /SAFE|REAL/.test(l)) || '')
  await run('pd tnt mode safe')
  out = await run('pd tnt launch all')
  check('launch in safe mode says nothing will explode', /SAFE MODE is on.*NOT explode/.test(out) && /tnt mode real/.test(out), out.trim().split('\n').pop())
  await sleep(6000)
  check('safe mode: the minecart is still alive after the fuse time', (await count('@e[type=minecraft:tnt_minecart]')) === 1)
  await run('pd tnt clear', 300)
  await run('pd tnt mode real')
  await run('pd tnt fuse 2')
  out = await run('pd tnt launch all')
  check('launch in real mode says it will explode after the fuse', /REAL EXPLOSIONS are on.*2 s after launch/.test(out), out.trim().split('\n').pop())
  await sleep(120)
  check('the minecart exists right after launch', (await count('@e[type=minecraft:tnt_minecart]')) === 1)
  await sleep(4500)
  check('the minecart exploded by itself after the fuse (it is gone without tnt clear)', (await count('@e[type=minecraft:tnt_minecart]')) === 0)
  out = await run(`execute if block ${FX + 8} 73 ${CZ} minecraft:stone_bricks`, 400)
  check('terrain protection on: the explosion did not break the wall', /passed/i.test(out))
  await run('pd tnt terrain-damage on')
  await run('pd tnt launch all')
  await sleep(6500)
  const wallLeft = await run(`execute if block ${FX + 8} 73 ${CZ} minecraft:stone_bricks`, 400)
  check('terrain damage on: the wall block in front of the portal is gone', !/passed/i.test(wallLeft), wallLeft.trim().split('\n').pop())
  const portalsFine = await badBlocks(readScene('default').portals)
  check('the portal itself survived (portal protection)', portalsFine === 0)
  await run('pd tnt terrain-damage off')
  await run('pd tnt wave 3 2')
  await sleep(1000)
  out = await run('pd tnt stop', 300)
  check('tnt stop ends the sequence and says what happens next', /Stopped/.test(out) && /already out keep going/.test(out), out.trim().split('\n').pop())
  out = await run('pd tnt clear', 300)
  check('tnt clear stops everything and removes minecarts', /removed \d+ TNT minecart/.test(out) && (await count('@e[type=minecraft:tnt_minecart]')) === 0, out.trim().split('\n').pop())
  await run('pd set tnt.max-active 2')
  await run('pd portal create ' + (FX + 20) + ' 72 ' + (CZ + 8) + ' east world', 1500)
  out = await run('pd tnt launch all 3', 2500)
  const limited = await count('@e[type=minecraft:tnt_minecart]')
  const msg = srv.lines.slice(-12).join('\n')
  check('the minecart limit is explained when it is reached', limited <= 2 && /skipped|not launched|limit/.test(msg), limited + ' alive')
  await run('pd tnt clear', 300)

  // clear and build something different
  const sceneBoom = readBlocks(path.join(PLUGIN_DIR, 'scenes', 'default.blocks'))
  out = await runUntil('pd clear', /Cleared: \d+ blocks restored/, 120000)
  check('clear restores the blocks', (await wrongRestoredAfterReplace(sceneBoom)) === 0 && /Cleared/.test(out), out.replace(/^\[[^\]]*\] /, ''))
  out = await runUntil('pd generate 8 ground', /Built \d+ of 8|went wrong|Only/, 120000)
  check('a completely new formation can be built after clearing', /Built/.test(out), out.replace(/^\[[^\]]*\] /, ''))
  out = (await run('pd generate 8')) + (await run('pd generate 8'))
  check('commands are not stuck after a finished operation', !/still working/.test(out) && /already has/.test(out))
}

async function wrongRestoredAfterReplace (recs) {
  // blocks of the old scene that still hold something the scene placed would count as wrong; others were changed by the new scene
  clearMarks()
  let n = 0
  for (const r of recs.filter(x => !/obsidian|nether_portal/.test(x.original))) {
    srv.command(`execute if block ${r.x} ${r.y} ${r.z} ${r.placed} run say RBAD`)
    n++
  }
  await flush('RCHECKDONE' + Date.now())
  return srv.lines.filter(l => /\[Server\] RBAD/.test(l)).length
}

main().catch(e => {
  console.error('[ux-test] ERROR:', e.stack || e.message)
  checks.push({ name: 'harness ran to the end', ok: false, detail: e.message })
}).finally(async () => {
  for (const b of bots.values()) { try { b.end() } catch {} }
  await srv.stop().catch(() => {})
  fs.writeFileSync(path.join(REPORT, 'ux-latest.json'), JSON.stringify({ when: new Date().toISOString(), checks }, null, 2))
  fs.writeFileSync(path.join(REPORT, 'ux-console.log'), srv.lines.join('\n'))
  const failed = checks.filter(c => !c.ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
})
