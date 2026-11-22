'use strict'
/*
 * Reproducible benchmark on the isolated Paper server: portal generation and clearing at several sizes, a restart with scene
 * recovery, a TNT Strike in safe mode and Sky Army with simulated bot players. It measures tick time with the vanilla
 * "tick query" command, heap use with jcmd, entity counts and how long each step takes.
 *
 *   node benchmark.js --label after --jar ../build/libs/PortalDirector-1.9.0.jar [--sizes 10,30,100,250] [--players 10,20,40] [--skip-sky] [--skip-tnt]
 *
 * Results go to reports/benchmark-<label>.json. Bots are a load simulation, not real players, and nothing here measures
 * how the scene looks.
 */
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { execFileSync } = require('node:child_process')
const { TestServer } = require('./lib/server')

const args = process.argv.slice(2)
const opt = (name, fallback) => {
  const i = args.indexOf('--' + name)
  return i >= 0 && args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : fallback
}
const flag = name => args.includes('--' + name)

const LABEL = opt('label', 'run')
const JAR = path.resolve(opt('jar', path.join(__dirname, '..', 'build', 'libs')))
const SIZES = opt('sizes', '10,30,100,250').split(',').map(Number)
const PLAYERS = opt('players', '10,20,40').split(',').map(Number)
const CYCLES = +opt('cycles', 3)
const PORT = +(process.env.PD_PORT || 25591)
const HOST = '127.0.0.1'
const SERVER_DIR = path.resolve(process.env.PD_SERVER_DIR || path.join(__dirname, '..', '..', 'portal-director-test-server'))
const PLUGIN_DIR = path.join(SERVER_DIR, 'plugins', 'PortalDirector')
const REPORT = path.join(__dirname, 'reports')

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log('[benchmark]', ...a)
const srv = new TestServer({ dir: SERVER_DIR, jar: 'paper-26.3-231.jar', memory: '3000M' })
const body = l => l.replace(/^\[[^\]]*\]: /, '')

function prepareServer () {
  const text = fs.readFileSync(path.join(SERVER_DIR, 'server.properties'), 'utf8')
  const get = k => (text.match(new RegExp('^' + k + '=(.*)$', 'm')) || [])[1]
  if (get('server-ip') !== HOST || get('server-port') !== String(PORT)) throw new Error('Refusing to run: the server must be 127.0.0.1:' + PORT)
  if (PORT === 25590 || PORT === 25592) throw new Error('Refusing to touch the play servers')
  fs.rmSync(PLUGIN_DIR, { recursive: true, force: true })
  for (const w of ['world', 'world_nether', 'world_the_end']) fs.rmSync(path.join(SERVER_DIR, w), { recursive: true, force: true })
  let jar = JAR
  if (fs.statSync(jar).isDirectory()) jar = path.join(jar, fs.readdirSync(jar).filter(f => /^PortalDirector-.*\.jar$/.test(f)).sort().pop())
  for (const f of fs.readdirSync(path.join(SERVER_DIR, 'plugins'))) if (/^PortalDirector-.*\.jar$/.test(f)) fs.rmSync(path.join(SERVER_DIR, 'plugins', f))
  fs.copyFileSync(jar, path.join(SERVER_DIR, 'plugins', path.basename(jar)))
  return path.basename(jar)
}

async function run (cmd, settle = 400) {
  const from = srv.lines.length
  srv.command(cmd)
  await sleep(settle)
  return srv.lines.slice(from).map(body).join('\n')
}
async function until (cmd, regex, timeout = 300000) {
  const from = srv.lines.length
  const t0 = performance.now()
  srv.command(cmd)
  const line = body(await srv.waitForLine(regex, timeout, from, String(regex)))
  return { line, ms: Math.round(performance.now() - t0) }
}

/** Forces a region to load in blocks of 15 x 15 chunks, because the vanilla command takes at most 256 chunks at a time. */
async function forceload (x1, z1, x2, z2) {
  const start = performance.now()
  let chunks = 0

  for (let x = Math.floor(x1 / 16) * 16; x <= x2; x += 240) {
    for (let z = Math.floor(z1 / 16) * 16; z <= z2; z += 240) {
      const r = await until(`forceload add ${x} ${z} ${x + 239} ${z + 239}`, /Marked \d+ chunks|Too many chunks/, 900000)

      if (/Too many/.test(r.line)) throw new Error(r.line)

      chunks += +r.line.match(/Marked (\d+)/)[1]
    }
  }

  return { chunks, ms: Math.round(performance.now() - start) }
}

/** Average and 99th percentile tick time, in milliseconds, from the vanilla "tick query" command. */
async function tickQuery () {
  const from = srv.lines.length
  srv.command('tick query')
  await sleep(350)
  const text = srv.lines.slice(from).map(body).join('\n')
  const avg = text.match(/Average time per tick: ([\d.]+)ms/)
  const p99 = text.match(/P99: ([\d.]+)ms/)
  const p50 = text.match(/P50: ([\d.]+)ms/)
  return avg ? { avg: +avg[1], p50: p50 ? +p50[1] : null, p99: p99 ? +p99[1] : null } : null
}
/** Samples tick time every half second while a promise runs. */
async function whileSampling (promise) {
  const samples = []
  let done = false
  const result = promise.then(r => { done = true; return r }, e => { done = true; throw e })
  while (!done) {
    const s = await tickQuery()
    if (s) samples.push(s)
    await sleep(150)
  }
  const value = await result
  const worstP99 = samples.reduce((m, s) => Math.max(m, s.p99 || 0), 0)
  const worstAvg = samples.reduce((m, s) => Math.max(m, s.avg), 0)
  const mean = samples.length ? samples.reduce((a, s) => a + s.avg, 0) / samples.length : null
  return { value, ticks: { samples: samples.length, meanAvgMs: mean && +mean.toFixed(2), worstAvgMs: worstAvg, worstP99Ms: worstP99 } }
}
function heapMb () {
  try {
    const jdks = path.join(os.homedir(), '.gradle', 'jdks')
    const jdk = fs.readdirSync(jdks).find(d => d.startsWith('eclipse_adoptium-25'))
    const out = execFileSync(path.join(jdks, jdk, 'bin', 'jcmd.exe'), [String(srv.proc.pid), 'GC.heap_info'], { encoding: 'utf8', timeout: 20000 })
    const m = out.match(/used (\d+)K/)
    return m ? Math.round(+m[1] / 1024) : null
  } catch (e) { return null }
}
async function entityCount () {
  const out = await run('execute if entity @e', 500)
  const m = out.match(/Count: (\d+)|count: (\d+)/)
  return m ? +(m[1] || m[2]) : 0
}
async function portalCount () {
  const out = await run('pd list', 700)
  const m = out.match(/(\d+) portals/)
  return m ? +m[1] : 0
}
async function warnings (from) {
  const lines = srv.lines.slice(from)
  return { warn: lines.filter(l => /WARN/.test(l) && /PortalDirector/.test(l)).length, error: lines.filter(l => /ERROR|Exception/.test(l) && /PortalDirector|portaldirector/i.test(l)).length }
}
const mean = a => a.length ? +(a.reduce((x, y) => x + y, 0) / a.length).toFixed(1) : null

async function main () {
  const jarName = prepareServer()
  fs.mkdirSync(REPORT, { recursive: true })
  const result = {
    label: LABEL,
    jar: jarName,
    when: new Date().toISOString(),
    machine: { cpus: os.cpus().length, cpuModel: os.cpus()[0].model, totalMemGb: +(os.totalmem() / 2 ** 30).toFixed(1), node: process.version },
    server: 'Paper 26.3 build 231, -Xmx3000M, view-distance and simulation-distance 2, 127.0.0.1 only',
    notes: ['tick time comes from the vanilla "tick query" command (a window of the last 100 ticks), so the worst value is the worst 99th percentile seen, not the single slowest tick',
      'bots are simulated players, not real clients'],
    generation: [],
    restart: null,
    tnt: null,
    sky: []
  }
  const t0 = performance.now()
  srv.start()
  await srv.waitForLine(/Done \(/, 300000, 0, 'server start')
  result.coldStartMs = Math.round(performance.now() - t0)
  const found = await until('execute in minecraft:overworld run locate biome minecraft:plains', /\[(-?\d+), (?:~|-?\d+), (-?\d+)\]/, 180000)
  const loc = found.line.match(/\[(-?\d+), (?:~|-?\d+), (-?\d+)\]/)
  const X0 = +loc[1] + 40
  const Z0 = +loc[2] + 40
  const R = Math.max(...SIZES) >= 250 ? 150 : 100
  // generate the land once so the benchmark measures the plugin, not world generation
  const warm = await forceload(X0 - R - 30, Z0 - R - 30, X0 + R + 30, Z0 + R + 30)
  result.worldGenerationMs = warm.ms
  result.chunksGenerated = warm.chunks
  await sleep(4000)

  await run(`pd set center ${X0} 120 ${Z0} world`)
  await run('pd set layout random')
  await run('pd set mode air')
  await run('pd set altitude-min 10')
  await run('pd set altitude-max 50')
  await run('pd set max-portals 1000')
  await run('pd set max-chunks 2000')

  const logStart = srv.lines.length
  const idle = await tickQuery()
  result.idleTick = idle
  result.heapAtStartMb = heapMb()

  for (const n of SIZES) {
    const radius = n <= 10 ? 40 : n <= 30 ? 70 : n <= 100 ? 110 : 150
    await run(`pd set radius ${radius}`)
    const cycles = []
    for (let c = 0; c < CYCLES; c++) {
      const gen = await whileSampling(until(`pd create ${n}`, /Built \d+ of \d+|went wrong|Stopped/, 600000))
      await sleep(1200)
      const built = +((gen.value.line.match(/Built (\d+) of/) || [])[1] || 0)
      const entities = await entityCount()
      const heap = heapMb()
      const clr = await whileSampling(until('pd clear', /Cleared:|Stopped\./, 600000))
      await sleep(1500)
      cycles.push({
        cycle: c + 1,
        requested: n,
        built,
        generateMs: gen.value.ms,
        generateTick: gen.ticks,
        clearMs: clr.value.ms,
        clearTick: clr.ticks,
        heapAfterGenerateMb: heap,
        entities
      })
      log(`size ${n} cycle ${c + 1}: built ${built} in ${gen.value.ms} ms (worst p99 ${gen.ticks.worstP99Ms} ms), cleared in ${clr.value.ms} ms`)
    }
    result.generation.push({
      size: n,
      radius,
      cycles,
      meanGenerateMs: mean(cycles.map(x => x.generateMs)),
      meanClearMs: mean(cycles.map(x => x.clearMs)),
      worstGenerateP99Ms: Math.max(...cycles.map(x => x.generateTick.worstP99Ms)),
      worstClearP99Ms: Math.max(...cycles.map(x => x.clearTick.worstP99Ms))
    })
  }

  // restart with the largest scene in place
  const big = Math.max(...SIZES)
  await run(`pd set radius ${big <= 100 ? 110 : 150}`)
  await until(`pd create ${big}`, /Built \d+ of \d+/, 600000)
  await sleep(2000)
  const before = await portalCount()
  const stopT = performance.now()
  await srv.stop()
  const stopMs = Math.round(performance.now() - stopT)
  const mark = srv.lines.length
  const startT = performance.now()
  srv.start()
  await srv.waitForLine(/Done \(/, 300000, mark, 'restart')
  const ready = srv.lines.slice(mark).find(l => /PortalDirector ready/.test(l))
  const startMs = Math.round(performance.now() - startT)
  await sleep(3000)
  const after = await portalCount()
  result.restart = { portalsBefore: before, portalsAfter: after, shutdownMs: stopMs, startToDoneMs: startMs, pluginReadyLine: !!ready }
  const clr = await until('pd clear', /Cleared:|Stopped\./, 600000)
  result.restart.clearAfterRestartMs = clr.ms
  await sleep(1500)

  // TNT Strike in safe mode
  if (!flag('skip-tnt')) {
    await run('pd tnt clear')
    await run('pd tnt limit 2000')
    await run('pd tnt mode safe')
    await run('pd sequence tnt-strike set portals 20')
    await run('pd sequence tnt-strike set radius 60')
    const strike = await whileSampling(until(`pd sequence tnt-strike run ${X0} 130 ${Z0} world`, /TNT Strike (done|could not|failed)/, 300000))
    const carts = await run('execute if entity @e[type=minecraft:tnt_minecart]', 500)
    const cleanup = await until('pd tnt clear', /removed \d+ TNT/, 60000)
    await run('pd clear', 3000)
    result.tnt = { durationMs: strike.value.ms, message: strike.value.line, tick: strike.ticks, minecartsAlive: (carts.match(/(\d+)/) || [])[1] || null, cleanupMs: cleanup.ms }
    log('tnt strike', JSON.stringify(result.tnt))
  }

  // Sky Army with simulated players
  if (!flag('skip-sky')) {
    const mineflayer = require('@wp2508/mineflayer')
    const bots = []
    await run('pd group create BENCH')
    await run('pd sequence sky-army set group BENCH')
    await run('pd sequence sky-army set radius 100')
    for (const count of PLAYERS) {
      while (bots.length < count) {
        const name = 'Bm' + String(bots.length + 1).padStart(3, '0')
        const bot = mineflayer.createBot({ host: HOST, port: PORT, username: name, auth: 'offline', version: '26.3', viewDistance: 'tiny', checkTimeoutInterval: 90000 })
        bot.on('error', () => {})
        await new Promise((resolve, reject) => {
          const t = setTimeout(() => reject(new Error(name + ' did not spawn')), 90000)
          bot.once('spawn', () => { clearTimeout(t); resolve() })
        })
        bots.push(bot)
        srv.command('op ' + name)
        srv.command(`pd group assign-to-group ${name} BENCH`)
        await sleep(120)
      }
      await sleep(1500)
      const mark2 = srv.lines.length
      const sky = await whileSampling(until(`pd sequence sky-army run ${X0} 130 ${Z0} world`, /Sky Army (ready|is only partly|could not|failed)/, 600000))
      const heap = heapMb()
      await sleep(1500)
      const stopRun = await until('pd sequence stop', /staged players are back|No sequence/, 120000)
      const clr2 = await until('pd clear', /Cleared:|Stopped\./, 600000)
      const w = await warnings(mark2)
      result.sky.push({ players: count, readyMs: sky.value.ms, message: sky.value.line, tick: sky.ticks, heapAfterMb: heap, stopMs: stopRun.ms, clearMs: clr2.ms, warnings: w.warn, errors: w.error })
      log(`sky army ${count}: ready in ${sky.value.ms} ms, worst p99 ${sky.ticks.worstP99Ms} ms`)
      await sleep(2000)
    }
    for (const b of bots) { try { b.end() } catch (e) { /* ignore */ } }
    await sleep(1500)
  }

  const w = await warnings(logStart)
  result.totalWarnings = w.warn
  result.totalErrors = w.error
  result.finalHeapMb = heapMb()
  fs.writeFileSync(path.join(REPORT, `benchmark-${LABEL}.json`), JSON.stringify(result, null, 2))
  await srv.stop().catch(() => {})
  console.log(JSON.stringify(result, null, 2))
  process.exit(0)
}

main().catch(async e => {
  console.error(e)
  await srv.stop().catch(() => {})
  process.exit(2)
})
