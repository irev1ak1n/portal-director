'use strict'
/*
 * Preview live test on an isolated Paper server with two real bots: the admin sees fake portal blocks, the other player sees none,
 * nothing is built, hide and leaving put the real blocks back, refusals. Nothing here is visual verification.
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
const log = (...a) => console.log('[preview-test]', ...a)
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

const OBSIDIAN = 'obsidian'
const seen = (bot, name, dist = 40) => bot.findBlocks({ matching: b => b && b.name === name, maxDistance: dist, count: 500 }).length

async function main () {
  prepareServer()
  fs.mkdirSync(REPORT, { recursive: true })
  await startServer()
  check('plugin enabled', srv.lines.some(l => /PortalDirector ready/.test(l)))
  await run('op Admin', 150)
  const admin = await connect('Admin')
  const obs = await connect('Obs')
  await sleep(4000)
  await run('pd set count 3', 300)
  await run('pd set radius 20', 300)
  await run('pd set placement air', 300)
  await run('pd set layout random', 300)

  let out = await run('pd preview show', 600)
  check('the console cannot show a preview', /needs a player/.test(out), last(out))

  check('before anything, nobody sees obsidian', seen(admin, OBSIDIAN) === 0 && seen(obs, OBSIDIAN) === 0, `${seen(admin, OBSIDIAN)} / ${seen(obs, OBSIDIAN)}`)
  admin.chat('/pd preview status')
  await sleep(500)
  admin.chat('/pd preview show')
  const shown = await waitFor(() => seen(admin, OBSIDIAN) > 0, 20000)
  check('the admin sees obsidian frames', shown, seen(admin, OBSIDIAN))
  check('the admin sees portal blocks', await waitFor(() => seen(admin, 'nether_portal') > 0, 8000), seen(admin, 'nether_portal'))
  await sleep(1500)
  check('the other player sees none of it', seen(obs, OBSIDIAN) === 0 && seen(obs, 'nether_portal') === 0, `${seen(obs, OBSIDIAN)} / ${seen(obs, 'nether_portal')}`)
  out = await run('pd list', 600)
  check('nothing was built or saved', !/sky|portal-\d|Portal \d/i.test(out) || /No portals|none|empty/i.test(out), last(out))

  admin.chat('/pd preview hide')
  const gone = await waitFor(() => seen(admin, OBSIDIAN) === 0 && seen(admin, 'nether_portal') === 0, 15000)
  check('hide puts the real blocks back for the admin', gone, `${seen(admin, OBSIDIAN)} / ${seen(admin, 'nether_portal')}`)

  admin.chat('/pd preview show')
  await waitFor(() => seen(admin, OBSIDIAN) > 0, 20000)
  const first = seen(admin, OBSIDIAN)
  admin.chat('/pd preview show')
  await sleep(4000)
  check('showing again still shows a preview', seen(admin, OBSIDIAN) > 0, `${first} then ${seen(admin, OBSIDIAN)}`)

  admin.chat('/pd preview hide')
  const clean = await waitFor(() => seen(admin, OBSIDIAN) === 0 && seen(admin, 'nether_portal') === 0, 15000)
  check('after showing twice, one hide leaves no block of the first preview behind', clean, `${seen(admin, OBSIDIAN)} / ${seen(admin, 'nether_portal')}`)
  admin.chat('/pd set layout grid')
  await sleep(500)
  admin.chat('/pd preview show')
  await sleep(1500)
  check('a layout other than random is refused', seen(admin, OBSIDIAN) === 0, seen(admin, OBSIDIAN))
  await run('pd set layout random', 300)

  admin.chat('/pd preview show')
  await waitFor(() => seen(admin, OBSIDIAN) > 0, 20000)
  admin.end()
  await sleep(1500)
  const again = await connect('Admin')
  await sleep(4000)
  check('after leaving and coming back there is no stale preview', seen(again, OBSIDIAN) === 0 && seen(again, 'nether_portal') === 0, `${seen(again, OBSIDIAN)}`)
  again.chat('/pd preview status')
  await sleep(600)

  const errors = srv.lines.filter(l => /(ERROR|Exception)/.test(l) && /PortalDirector|portaldirector/i.test(l))
  check('no plugin errors in the console', errors.length === 0, errors.slice(0, 2).join(' | '))
  fs.writeFileSync(path.join(REPORT, 'preview-latest.json'), JSON.stringify({ checks }, null, 2))
  fs.writeFileSync(path.join(REPORT, 'preview-console.log'), srv.lines.join('\n'))
  for (const b of bots.values()) { try { b.end() } catch (e) { /* ignore */ } }
  await sleep(1000)
  await srv.stop().catch(() => {})
  const failed = checks.filter(c => !c.ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
}

main().catch(async e => {
  console.error(e)
  try { fs.writeFileSync(path.join(REPORT, 'preview-console.log'), srv.lines.join('\n')) } catch (x) { /* ignore */ }
  for (const b of bots.values()) { try { b.end() } catch (x) { /* ignore */ } }
  await srv.stop().catch(() => {})
  process.exit(2)
})
