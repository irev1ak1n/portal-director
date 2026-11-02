'use strict'
/*
 * PortalDirector live test.
 *
 * Starts the isolated localhost-only Paper server (..\portal-director-server), generates a 30 portal scene,
 * connects 30 Mineflayer bots, and checks generation, auto joining, one player per portal, synchronized and
 * staggered entrances, reconnects, cancellation, TNT waves, persistence across a restart and safe removal.
 * Bots cannot see anything; see README for what this does not prove.
 */
const fs = require('node:fs')
const path = require('node:path')
const mineflayer = require('@wp2508/mineflayer')
const { TestServer } = require('./lib/server')

const PORT = 25590
const HOST = '127.0.0.1'
const SERVER_DIR = path.resolve(process.env.PD_SERVER_DIR || path.join(__dirname, '..', '..', 'portal-director-server'))
const PLUGIN_DIR = path.join(SERVER_DIR, 'plugins', 'PortalDirector')
const BOTS = 30
const REPORT = path.join(__dirname, 'reports')

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log('[pd-test]', ...a)
const checks = []
function check (name, ok, detail = '') {
  checks.push({ name, ok: !!ok, detail })
  console.log(`${ok ? '  PASS' : '  FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`)
}

function requireIsolatedServer () {
  const text = fs.readFileSync(path.join(SERVER_DIR, 'server.properties'), 'utf8')
  const get = k => (text.match(new RegExp('^' + k + '=(.*)$', 'm')) || [])[1]
  if (get('server-ip') !== HOST) throw new Error('Refusing to run: server-ip must be 127.0.0.1')
  if (get('server-port') !== String(PORT)) throw new Error('Refusing to run: server-port must be ' + PORT)
}

const srv = new TestServer({ dir: SERVER_DIR, jar: 'paper-26.3-231.jar' })

async function run (cmd, settle = 700) {
  const from = srv.lines.length
  srv.command(cmd)
  await sleep(settle)
  return srv.lines.slice(from)
}

async function runUntil (cmd, regex, timeout = 120000) {
  const from = srv.lines.length
  srv.command(cmd)
  const hit = await srv.waitForLine(regex, timeout, from, String(regex))
  return { hit, lines: srv.lines.slice(from) }
}

async function entityCount (selector) {
  const out = await run(`execute if entity ${selector}`, 500)
  const m = out.join('\n').match(/count: (\d+)/)
  return m ? parseInt(m[1], 10) : 0
}

function readScene (name) {
  const file = path.join(PLUGIN_DIR, 'scenes', name + '.yml')
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/)
  const scene = { portals: [], assignments: {} }
  let section = ''
  let group = ''
  let cur = null
  for (const l of lines) {
    if (/^portals:/.test(l)) { section = 'portals'; continue }
    if (/^assignments:/.test(l)) { section = 'assignments'; continue }
    if (/^world:/.test(l)) scene.world = l.split(':')[1].trim()
    if (section === 'portals') {
      let m = l.match(/^- id: (\d+)/)
      if (m) { cur = { id: +m[1] }; scene.portals.push(cur); continue }
      m = l.match(/^ {2}(\w+): (.+)$/)
      if (m && cur) cur[m[1]] = m[2] === 'true' ? true : m[2] === 'false' ? false : /^-?\d+$/.test(m[2]) ? +m[2] : m[2]
    } else if (section === 'assignments') {
      let m = l.match(/^ {2}(\S+):$/)
      if (m) { group = m[1]; scene.assignments[group] = {}; continue }
      m = l.match(/^ {4}'?(\d+)'?: (\S+)/)
      if (m && group) scene.assignments[group][+m[1]] = m[2]
    }
  }
  return scene
}

function geometry (p) {
  const stepX = p.facing === 'EAST' ? 1 : p.facing === 'WEST' ? -1 : 0
  const stepZ = p.facing === 'SOUTH' ? 1 : p.facing === 'NORTH' ? -1 : 0
  const cx = p.axis === 'X' ? p.x + 2 : p.x + 0.5
  const cz = p.axis === 'Z' ? p.z + 2 : p.z + 0.5
  const w = 4
  const box = {
    minX: p.axis === 'X' ? p.x : p.x - (stepX < 0 ? 3 : 1),
    maxX: p.axis === 'X' ? p.x + w - 1 : p.x + (stepX > 0 ? 3 : 1),
    minZ: p.axis === 'Z' ? p.z : p.z - (stepZ < 0 ? 3 : 1),
    maxZ: p.axis === 'Z' ? p.z + w - 1 : p.z + (stepZ > 0 ? 3 : 1),
    minY: p.y,
    maxY: p.y + 4
  }
  return { stepX, stepZ, cx, cz, standY: p.y + 1, box }
}

function separated (a, b, gap) {
  const A = geometry(a).box
  const B = geometry(b).box
  return A.maxX + gap < B.minX || B.maxX + gap < A.minX || A.maxZ + gap < B.minZ || B.maxZ + gap < A.minZ ||
    A.maxY + gap < B.minY || B.maxY + gap < A.minY
}

/** Counts portal blocks that are not what they should be, asking the server itself. */
async function badPortalBlocks (portals) {
  let sent = 0
  for (const p of portals) {
    const axis = p.axis.toLowerCase()
    for (let i = 0; i < 4; i++) {
      for (let j = 0; j < 5; j++) {
        const x = p.axis === 'X' ? p.x + i : p.x
        const z = p.axis === 'Z' ? p.z + i : p.z
        const y = p.y + j
        const frame = i === 0 || i === 3 || j === 0 || j === 4
        const want = frame ? 'minecraft:obsidian' : `minecraft:nether_portal[axis=${axis}]`
        srv.command(`execute unless block ${x} ${y} ${z} ${want} run say BAD ${p.id} ${x} ${y} ${z}`)
        sent++
      }
    }
  }
  const from = srv.lines.length
  srv.command('say CHECKDONE')
  await srv.waitForLine(/CHECKDONE/, 60000, from)
  await sleep(300)
  return srv.lines.filter(l => /\[Server\] BAD \d+/.test(l)).length
}

function clearBad () {
  srv.lines = srv.lines.filter(l => !/\[Server\] (BAD|RBAD)/.test(l))
}

// bots

const bots = new Map()

async function connect (username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, auth: 'offline', version: '26.3', checkTimeoutInterval: 60000 })
  bot.on('error', e => console.error(`[${username}] error:`, e.message))
  bot.on('kicked', r => console.error(`[${username}] kicked:`, JSON.stringify(r)))
  bot.gameInfo = []
  bot.forced = []
  bot.on('message', (m, pos) => { if (pos === 'game_info') bot.gameInfo.push(m.toString()) })
  bot.on('forcedMove', () => bot.forced.push(performance.now()))
  bots.set(username, bot)
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`${username} did not spawn`)), 40000)
    bot.once('spawn', () => { clearTimeout(t); resolve() })
  })
  return bot
}

const dist2 = (a, b) => Math.hypot(a.x - b.x, a.z - b.z)

async function walkAcross (names, direction) {
  const jobs = names.map(async n => {
    const bot = bots.get(n)
    await bot.lookAt(bot.entity.position.offset(10 * direction, 0, 0), true)
    bot.setControlState('forward', true)
    await sleep(1800)
    bot.setControlState('forward', false)
  })
  await Promise.all(jobs)
}

// main

async function main () {
  requireIsolatedServer()
  fs.mkdirSync(REPORT, { recursive: true })
  const names = Array.from({ length: BOTS }, (_, i) => `Bot${String(i + 1).padStart(2, '0')}`)

  log('Starting the isolated test server on 127.0.0.1:' + PORT)
  srv.start()
  await srv.waitForLine(/Done \(/, 180000, 0, 'server start')
  check('plugin enabled', srv.lines.some(l => /PortalDirector ready/.test(l)))
  const found = await runUntil('execute in minecraft:overworld run locate biome minecraft:plains', /\[(-?\d+), (?:~|-?d+), (-?\d+)\]/, 90000)
  const loc = found.hit.match(/\[(-?\d+), (?:~|-?d+), (-?\d+)\]/)
  const CX = +loc[1] + 40
  const CZ = +loc[2] + 40
  log(`Scene center ${CX}, ${CZ} (plains)`)
  await run(`forceload add ${CX - 144} ${CZ - 144} ${CX + 144} ${CZ}`, 800)
  await run(`forceload add ${CX - 144} ${CZ + 1} ${CX + 144} ${CZ + 144}`, 800)
  await sleep(15000)
  await run(`pd center ${CX} ${CZ} world`)
  await run('pd settings all')

  // limits and errors
  let out = (await run('pd radius 900')).join('\n')
  check('invalid radius is rejected with a helpful message', /between/.test(out), out.split('\n').pop())
  await run('pd radius 400')
  out = (await run('pd generate 10 mixed')).join('\n')
  check('too large an area is refused (chunk limit)', /chunks, more than the limit/.test(out))
  await run('pd radius 100')

  // generation
  log('Generating 30 portals')
  const t0 = Date.now()
  const gen = await runUntil('pd generate 30 mixed', /Done: \d+ of 30 portals placed|Generation stopped/, 180000)
  log('generation took ' + (Date.now() - t0) + ' ms')
  check('generate 30 mixed finished', /Done: 30 of 30/.test(gen.hit), gen.hit)
  const scene = readScene('default')
  const portals = scene.portals
  check('30 portals saved', portals.length === 30)
  check('ids are unique and start at 1', new Set(portals.map(p => p.id)).size === 30 && Math.min(...portals.map(p => p.id)) === 1 && Math.max(...portals.map(p => p.id)) === 30)
  check('both orientations present', portals.some(p => p.axis === 'X') && portals.some(p => p.axis === 'Z'))
  check('ground and floating present', portals.some(p => p.floating) && portals.some(p => !p.floating),
    `${portals.filter(p => p.floating).length} floating`)
  let overlaps = 0
  for (let i = 0; i < portals.length; i++) for (let j = i + 1; j < portals.length; j++) if (!separated(portals[i], portals[j], 8)) overlaps++
  check('no overlaps and spacing >= 8 blocks', overlaps === 0, overlaps + ' bad pairs')
  const heights = new Set(portals.filter(p => p.floating).map(p => p.y))
  check('floating portals vary in height', heights.size > 3, [...heights].join(','))
  const bad = await badPortalBlocks(portals)
  check('every frame block is obsidian and every interior is an active portal (queried on the server)', bad === 0, bad + ' wrong blocks')
  check('no TNT minecart exists after generation', (await entityCount('@e[type=minecraft:tnt_minecart]')) === 0)

  // groups
  await run('pd group create NULL')
  out = (await run('pd group block set emerald_block')).join('\n')
  check('group block set', /emerald_block now join NULL/.test(out), out.trim().split('\n').pop())
  await run('forceload add 985 985 1045 1045', 800)
  await sleep(5000)
  await run('fill 985 199 985 1045 199 1045 stone')
  await run('fill 985 200 985 1045 205 1045 air')
  await run('fill 1005 199 985 1005 199 1045 emerald_block')
  log('Connecting ' + BOTS + ' bots')
  for (const n of names) { await connect(n); await sleep(150) }
  await sleep(1500)
  for (let i = 0; i < names.length; i++) await run(`tp ${names[i]} 1002.5 200 ${990 + i * 1.5}`, 40)
  await sleep(2500)
  await walkAcross(names, 1)
  await sleep(1000)
  out = (await run('pd group info NULL', 1200)).join('\n')
  const members = [...out.matchAll(/(\d+)\. (Bot\d+) .*?-> portal (\d+)/g)].map(m => ({ n: +m[1], name: m[2], portal: +m[3] }))
  check('all 30 bots joined by stepping on the block', members.length === 30, members.length + ' members')
  check('each member got the portal matching their join order', members.every(m => m.portal === m.n))
  await walkAcross(names, -1)
  await walkAcross(names, 1)
  out = (await run('pd group info NULL', 1200)).join('\n')
  const again = [...out.matchAll(/(\d+)\. (Bot\d+)/g)].length
  check('stepping on the block again does not duplicate members', again === 30, again + ' members')
  check('each player got exactly one join message and no chat spam',
    names.every(n => bots.get(n).gameInfo.length === 1), names.map(n => bots.get(n).gameInfo.length).join(','))
  const assigned = {}
  for (const m of members) assigned[m.name] = m.portal
  out = (await run('pd group assign NULL auto')).join('\n')
  check('assign auto: 30 players, 30 portals', /30 players assigned to 30 portals/.test(out), out.trim().split('\n').pop())

  // prepare
  const portalById = Object.fromEntries(portals.map(p => [p.id, p]))
  const before = Object.fromEntries(names.map(n => [n, bots.get(n).entity.position.clone()]))
  out = (await run('pd group prepare NULL', 2500)).join('\n')
  await sleep(3000)
  check('prepare staged 30 players', /30 players are in place/.test(out), out.trim().split('\n').pop())
  let atPortal = 0
  for (const n of names) {
    const g = geometry(portalById[assigned[n]])
    const pos = bots.get(n).entity.position
    if (dist2(pos, { x: g.cx, z: g.cz }) < 1.2 && Math.abs(pos.y - g.standY) < 1.5) atPortal++
  }
  check('every player stands in their own portal after prepare', atPortal === 30, atPortal + '/30')
  const staged = names.map(n => bots.get(n).entity.position.clone())
  await sleep(1500)
  check('staged players are frozen', names.every((n, i) => bots.get(n).entity.position.distanceTo(staged[i]) < 0.3))

  // reconnect during prepare
  const victim = 'Bot05'
  bots.get(victim).end()
  await sleep(2500)
  await connect(victim)
  await sleep(2500)
  {
    const g = geometry(portalById[assigned[victim]])
    const pos = bots.get(victim).entity.position
    check('a player who reconnects while prepared is put back in their portal', dist2(pos, { x: g.cx, z: g.cz }) < 1.2, pos.toString())
  }

  // synchronized release
  for (const n of names) bots.get(n).forced.length = 0
  const traj = Object.fromEntries(names.map(n => [n, []]))
  const sampler = setInterval(() => { for (const n of names) traj[n].push({ t: performance.now(), p: bots.get(n).entity.position.clone() }) }, 50)
  const tCmd = performance.now()
  out = (await run('pd group release NULL', 100)).join('\n')
  await sleep(9000)
  clearInterval(sampler)
  check('release message', /Release! 30 players/.test(out), out.trim().split('\n').pop())
  const firsts = names.map(n => bots.get(n).forced.find(t => t >= tCmd)).filter(Boolean)
  const spread = Math.max(...firsts) - Math.min(...firsts)
  check('all 30 clients received their teleport within 250 ms of each other', firsts.length === 30 && spread < 250, `spread ${spread.toFixed(0)} ms, ${firsts.length} teleports`)
  let walked = 0
  let fine = 0
  for (const n of names) {
    const p = portalById[assigned[n]]
    const g = geometry(p)
    const t = traj[n]
    const start = t.find(s => s.t >= tCmd + 100 && dist2(s.p, { x: g.cx, z: g.cz }) < 1.5)
    const peak = Math.max(...t.map(s => (s.p.x - g.cx) * g.stepX + (s.p.z - g.cz) * g.stepZ))
    if (start && peak >= 3) walked++
    const lateral = Math.max(...t.slice(0, 40).map(s => Math.abs((s.p.x - g.cx) * g.stepZ) + Math.abs((s.p.z - g.cz) * g.stepX)))
    if (lateral < 1.5) fine++
  }
  check('every player walked out of the portal in its facing direction (>= 3 blocks)', walked === 30, walked + '/30')
  check('walking stays on the portal axis (no sideways drift)', fine === 30, fine + '/30')
  check('nobody took fall damage (floating portals included)', names.every(n => bots.get(n).health === 20), names.filter(n => bots.get(n).health < 20).join(','))
  check('nobody was sent to the Nether by vanilla portals', names.every(n => bots.get(n).game.dimension === 'overworld'), bots.get('Bot01').game.dimension)
  const floatingWalkers = names.filter(n => portalById[assigned[n]].floating).length
  log(floatingWalkers + ' of the players came out of floating portals')

  // reset
  out = (await run('pd group reset NULL', 2500)).join('\n')
  await sleep(2500)
  let back = 0
  for (const n of names) if (dist2(bots.get(n).entity.position, before[n]) < 3) back++
  check('reset sends everyone back to where they were', back >= 29, back + '/30 ' + out.trim().split('\n').pop())

  // staggered release and cancellation
  await run('pd group prepare NULL', 2000)
  await sleep(2500)
  for (const n of names) bots.get(n).forced.length = 0
  const tS = performance.now()
  out = (await run('pd group release NULL staggered 2 10', 100)).join('\n')
  await sleep(7500)
  check('staggered message', /3 waves of 10, one every 2s/.test(out), out.trim().split('\n').pop())
  const waveTimes = names.map(n => (bots.get(n).forced.find(t => t >= tS) - tS) / 1000)
  const w1 = waveTimes.slice(0, 10)
  const w2 = waveTimes.slice(10, 20)
  const w3 = waveTimes.slice(20)
  const avg = a => a.reduce((x, y) => x + y, 0) / a.length
  check('three waves two seconds apart', Math.abs(avg(w2) - avg(w1) - 2) < 0.6 && Math.abs(avg(w3) - avg(w2) - 2) < 0.6,
    `waves at ${avg(w1).toFixed(2)}s, ${avg(w2).toFixed(2)}s, ${avg(w3).toFixed(2)}s`)
  await run('pd group reset NULL', 1500)
  await sleep(2000)

  // cancel in the middle of a staggered release
  await run('pd group prepare NULL', 2000)
  await sleep(2500)
  for (const n of names) bots.get(n).forced.length = 0
  const tC = performance.now()
  await run('pd group release NULL staggered 3 10', 100)
  await sleep(1500)
  await run('pd group reset NULL', 500)
  await sleep(6000)
  const late = names.slice(10).filter(n => bots.get(n).forced.some(t => t > tC + 2800 && t < tC + 4500))
  check('reset cancels waves that have not happened yet', late.length === 0, late.length + ' players still released after reset')

  // extra portals stay empty
  await run('pd group remove NULL Bot30')
  out = (await run('pd group assign NULL auto')).join('\n')
  check('extra portals stay empty', /29 players assigned to 30 portals.*1 portals stay empty/.test(out), out.trim().split('\n').pop())
  await run('pd group assign NULL 30 Bot29')
  out = (await run('pd group assignments NULL')).join('\n')
  check('manual assignment is possible', /30: Bot29/.test(out), out.split('\n').find(l => /30:/.test(l)) || '')
  await run('pd group assign NULL auto')

  // tnt
  log('TNT tests')
  await run('tp @a 1002.5 200 1000', 60)
  await run('pd tnt preview on')
  await run('pd tnt launch all')
  await sleep(1500)
  check('tnt launch all: 30 minecarts', (await entityCount('@e[type=minecraft:tnt_minecart]')) === 30)
  await sleep(14000)
  check('preview mode: minecarts did not explode', (await entityCount('@e[type=minecraft:tnt_minecart]')) === 30)
  out = (await run('pd tnt clear')).join('\n')
  check('tnt clear removes them', /Removed 30/.test(out) && (await entityCount('@e[type=minecraft:tnt_minecart]')) === 0, out.trim().split('\n').pop())
  check('portals untouched after preview', (await badPortalBlocks(portals)) === 0)
  clearBad()

  await run('pd tnt launch 1 1')
  await sleep(700)
  const posOut = (await run('data get entity @e[type=minecraft:tnt_minecart,limit=1] Pos', 500)).join('\n')
  const pm = posOut.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
  if (pm) {
    const g = geometry(portalById[1])
    const along = (+pm[1] - g.cx) * g.stepX + (+pm[3] - g.cz) * g.stepZ
    const lateral = Math.abs((+pm[1] - g.cx) * g.stepZ) + Math.abs((+pm[3] - g.cz) * g.stepX)
    check('minecart leaves the portal in its facing direction', along > 1 && lateral < 1, `along ${along.toFixed(2)}, sideways ${lateral.toFixed(2)}`)
  } else {
    check('minecart leaves the portal in its facing direction', false, posOut.trim())
  }
  await run('pd tnt clear')

  await run('pd tnt wave 3 2')
  await sleep(1000)
  const c1 = await entityCount('@e[type=minecraft:tnt_minecart]')
  await sleep(2000)
  const c2 = await entityCount('@e[type=minecraft:tnt_minecart]')
  await sleep(2200)
  const c3 = await entityCount('@e[type=minecraft:tnt_minecart]')
  check('tnt wave 3 2: 30, 60, 90 minecarts, two seconds apart', c1 === 30 && c2 === 60 && c3 === 90, `${c1}, ${c2}, ${c3}`)
  await run('pd tnt clear')

  await run('pd tnt wave 5 3')
  await sleep(1000)
  out = (await run('pd tnt stop')).join('\n')
  await sleep(7000)
  const cStop = await entityCount('@e[type=minecraft:tnt_minecart]')
  check('tnt stop ends a running sequence', /Stopped 1/.test(out) && cStop === 30, `${cStop} minecarts`)
  await run('pd tnt clear')

  await run('pd set tnt.max-active 40')
  await run('pd tnt launch all 3')
  await sleep(2500)
  const capped = await entityCount('@e[type=minecraft:tnt_minecart]')
  check('entity limit caps minecarts', capped <= 40 && capped > 0, capped + ' minecarts')
  await run('pd tnt clear')
  await run('pd set tnt.max-active 300')

  await run('pd tnt preview off')
  await run('pd set tnt.fuse-ticks 30')
  await run('pd tnt launch all 2')
  await sleep(9000)
  check('real explosions leave every portal intact (terrain protection)', (await badPortalBlocks(portals)) === 0)
  clearBad()
  await run('pd set tnt.protect-terrain off')
  await run('pd tnt launch all')
  await sleep(9000)
  check('portals survive even with terrain damage on (portal protection)', (await badPortalBlocks(portals)) === 0)
  clearBad()
  await run('pd tnt clear')
  await run('pd set tnt.protect-terrain on')
  await run('pd set tnt.fuse-ticks -1')

  // persistence
  out = (await run('pd scene save invasion')).join('\n')
  check('scene save renames the working scene', /saved as invasion/.test(out), out.trim().split('\n').pop())
  for (const b of bots.values()) b.end()
  bots.clear()
  await sleep(2000)
  const code = await srv.stop()
  check('server stopped gracefully', code === 0, 'exit code ' + code)
  log('Restarting the server')
  srv.lines = []
  srv.start()
  await srv.waitForLine(/Done \(/, 180000, 0, 'server restart')
  await sleep(3000)
  out = (await run('pd scene info', 800)).join('\n')
  check('scene is back after a restart', /Scene invasion \(current\)/.test(out) && /Portals: 30/.test(out), '')
  out = (await run('pd group info NULL', 800)).join('\n')
  check('group, members and join block are back after a restart', /29 players|Bot01/.test(out) && /emerald_block/.test(out))
  const persisted = readScene('invasion')
  check('assignments are saved with the scene', Object.keys(persisted.assignments.null || {}).length === 30)
  check('portals are still in the world after a restart', (await badPortalBlocks(portals)) === 0)
  clearBad()

  // safe removal
  const blocksFile = path.join(PLUGIN_DIR, 'scenes', 'invasion.blocks')
  const recs = readBlocks(blocksFile)
  log(recs.length + ' changed blocks are tracked')
  const p1 = portals[0]
  const mx = p1.axis === 'X' ? p1.x : p1.x
  const mz = p1.z
  await run(`setblock ${mx} ${p1.y} ${mz} minecraft:diamond_block`)
  out = (await runUntil('pd clear', /cleared: \d+ blocks restored/, 120000)).hit
  check('scene clear reports restored and kept blocks', /left alone/.test(out), out)
  for (const r of recs) {
    if (r.x === mx && r.y === p1.y && r.z === mz) continue
    srv.command(`execute unless block ${r.x} ${r.y} ${r.z} ${r.original} run say RBAD ${r.x} ${r.y} ${r.z}`)
  }
  const from = srv.lines.length
  srv.command('say RCHECKDONE')
  await srv.waitForLine(/RCHECKDONE/, 120000, from)
  await sleep(300)
  const rbad = srv.lines.filter(l => /\[Server\] RBAD/.test(l)).length
  check('every changed block went back to what it was', rbad === 0, `${rbad} of ${recs.length} differ`)
  const keep = (await run(`execute if block ${mx} ${p1.y} ${mz} minecraft:diamond_block`, 500)).join('\n')
  check('a block somebody changed afterwards is not overwritten', /Test passed/.test(keep), keep.trim().split('\n').pop())
  out = (await run('pd scene info', 600)).join('\n')
  check('scene is empty after clear', /Portals: 0/.test(out))

  // regenerate on same scene
  const regen = await runUntil('pd generate 12 ground', /Done: \d+ of 12/, 120000)
  check('the cleared scene can be generated again', /Done: 12 of 12/.test(regen.hit), regen.hit)
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
