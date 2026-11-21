'use strict'
/*
 * Takes live test on an isolated Paper server with a real bot: save, list, info, prepare (nobody moves), stage, start, reset,
 * delete, refusals, restart persistence. Nothing here is visual verification.
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
const log = (...a) => console.log('[takes-test]', ...a)
const checks = []
function check (name, ok, detail = '') {
  checks.push({ name, ok: !!ok, detail })
  console.log(`${ok ? '  PASS' : '  FAIL'}  ${name}${detail ? '  (' + String(detail).slice(0, 300) + ')' : ''}`)
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

const body = l => l.replace(/^\[[^\]]*\]: /, '')
async function run (cmd, settle = 600) {
  const from = srv.lines.length
  srv.command(cmd)
  await sleep(settle)
  return srv.lines.slice(from).map(body).join('\n')
}
async function runUntil (cmd, regex, timeout = 120000) {
  const from = srv.lines.length
  srv.command(cmd)
  return body(await srv.waitForLine(regex, timeout, from, String(regex)))
}
/** Runs a command and returns its output once a line matching {@code end} has appeared. */
async function runBlock (cmd, end, timeout = 30000) {
  const from = srv.lines.length
  srv.command(cmd)
  await srv.waitForLine(end, timeout, from, String(end))
  await sleep(250)
  return srv.lines.slice(from).map(body).join('\n')
}
const last = out => out.trim().split('\n').pop().slice(0, 240)
async function waitFor (fn, ms, step = 250) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await fn()) return true; await sleep(step) }
  return false
}
async function serverX (name) {
  const from = srv.lines.length
  srv.command('data get entity ' + name + ' Pos')
  try {
    const line = body(await srv.waitForLine(new RegExp(name + ' has the following entity data: '), 8000, from, 'pos'))
    const m = line.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
    return m ? +m[1] : null
  } catch (e) { return null }
}
/** The group's info, once its last line is out. */


const mineflayer = require('@wp2508/mineflayer')
const ALPHA = (x, y) => (x.toLowerCase() < y.toLowerCase() ? -1 : x.toLowerCase() > y.toLowerCase() ? 1 : 0)
const bots = new Map()
async function connect (name) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: name, auth: 'offline', version: '26.3', viewDistance: 'tiny', checkTimeoutInterval: 60000 })
  bot.on('error', e => console.error(`[${name}] error:`, e.message))
  bots.set(name, bot)
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(name + ' did not spawn')), 60000)
    bot.once('spawn', () => { clearTimeout(t); resolve() })
  })
  return bot
}
async function pos (name) {
  const from = srv.lines.length
  srv.command('data get entity ' + name + ' Pos')
  const line = body(await srv.waitForLine(new RegExp(name + ' has the following entity data: '), 8000, from, 'pos'))
  const m = line.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
  return m ? [+m[1], +m[2], +m[3]] : null
}
const near = (a, b, d = 1.5) => a && b && Math.abs(a[0] - b[0]) < d && Math.abs(a[2] - b[2]) < d

async function startServer () {
  srv.start()
  await srv.waitForLine(/Done \(/, 300000, 0, 'server start')
}

async function main () {
  prepareServer()
  fs.mkdirSync(REPORT, { recursive: true })
  await startServer()
  check('plugin enabled', srv.lines.some(l => /PortalDirector ready/.test(l)))
  let out = await runBlock('pd diagnostics', /Operations cut off/)
  check('the database is on', /Database: ok/.test(out), last(out))

  await run('op Cam', 150)
  const cam = await connect('Cam')
  await sleep(2500)
  await run('pd group create TG', 400)
  await run('pd group assign-to-group Cam TG', 400)
  await run('pd sequence sky-army set radius 30', 400)
  out = await run('pd take list', 500)
  check('no takes at the start', /None yet/.test(out), last(out))

  const home = await pos('Cam')
  cam.chat('/pd take save t1 sequence sky-army positions')
  await sleep(1500)
  out = await runBlock('pd take info t1', /Run \d+|saved positions/)
  check('the take was saved with its sequence', /sequence sky-army/.test(out) && /group TG/.test(out), out)
  check('with one assignment, one position and a target', /1 assignments, 1 saved positions/.test(out) && !/Target none/.test(out), out)
  out = await run('pd take list', 500)
  check('it is listed', /\bt1\b/.test(out), last(out))

  out = await run('pd take arena save t1', 600)
  check('without WorldEdit the arena commands explain what is missing and nothing breaks', /needs WorldEdit or FastAsyncWorldEdit/.test(out), last(out))
  out = await runBlock('pd diagnostics', /Operations cut off/)
  check('diagnostics says arena restore is off', /Arena restore: no WorldEdit/.test(out), last(out))
  out = await run('pd take start t1', 600)
  check('start is refused before prepare', /Prepare it first/.test(out), last(out))
  out = await run('pd take save bad!name', 500)
  check('a bad name is refused', /letters, numbers/.test(out), last(out))
  out = await run('pd take info nothing', 500)
  check('an unknown take is explained', /There is no take called/.test(out), last(out))

  await run('tp Cam ' + (home[0] + 60) + ' ' + home[1] + ' ' + (home[2] + 60), 1500)
  const away = await pos('Cam')
  out = await run('pd take prepare t1', 800)
  check('prepare says it is ready', /is prepared/.test(out), last(out))
  check('prepare moved nobody', near(await pos('Cam'), away), JSON.stringify([away, await pos('Cam')]))
  out = await run('pd take stage t1', 800)
  check('stage moves the player to the saved spot', /Moved 1 players/.test(out) && near(await pos('Cam'), home), JSON.stringify([home, await pos('Cam')]))

  out = await runBlock('pd take start t1', /Sky Army ready|cannot|not|No /i, 120000)
  check('start runs the sequence', /Sky Army ready|Sky Army/i.test(out), last(out))
  out = await run('pd take prepare t1', 600)
  check('prepare is refused while the take runs', /is running/.test(out), last(out))
  out = await run('pd take delete t1', 600)
  check('delete is refused while the take runs', /prepared or running/.test(out), last(out))

  out = await runBlock('pd take reset t1', /Cleared|nothing was built|nothing was cleared/, 60000)
  check('reset stops it and clears what it built', /is reset/.test(out), last(out))
  out = await run('pd sequence status', 600)
  check('nothing is running afterwards', /Nothing is running/.test(out), last(out))
  out = await run('pd take reset t1', 500)
  check('a second reset has nothing to do', /nothing of it to reset/.test(out), last(out))

  out = await run('pd take delete t1', 600)
  check('delete works once the take is reset', /Deleted take t1/.test(out), last(out))
  out = await run('pd take list', 500)
  check('and the list is empty again', /None yet/.test(out), last(out))

  cam.chat('/pd take save t2 sequence sky-army')
  await sleep(1500)
  for (const b of bots.values()) { try { b.end() } catch (e) { /* ignore */ } }
  await sleep(1000)
  await srv.stop().catch(() => {})
  srv.lines.length = 0
  await startServer()
  out = await run('pd take list', 600)
  check('a saved take is still there after a restart', /\bt2\b/.test(out), last(out))
  out = await runBlock('pd diagnostics', /Operations cut off/)
  check('and nothing was reported as cut off', /Operations cut off at the last start: 0/.test(out), last(out))

  const errors = srv.lines.filter(l => /(ERROR|Exception)/.test(l) && /PortalDirector|portaldirector/i.test(l))
  check('no plugin errors in the console', errors.length === 0, errors.slice(0, 2).join(' | '))
  fs.writeFileSync(path.join(REPORT, 'takes-latest.json'), JSON.stringify({ checks }, null, 2))
  fs.writeFileSync(path.join(REPORT, 'takes-console.log'), srv.lines.join('\n'))
  await srv.stop().catch(() => {})
  const failed = checks.filter(c => !c.ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
}

main().catch(async e => {
  console.error(e)
  try { fs.writeFileSync(path.join(REPORT, 'takes-console.log'), srv.lines.join('\n')) } catch (x) { /* ignore */ }
  for (const b of bots.values()) { try { b.end() } catch (x) { /* ignore */ } }
  await srv.stop().catch(() => {})
  process.exit(2)
})
