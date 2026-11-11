'use strict'
/*
 * Group enrollment live test on an isolated Paper server, with real bot clients that walk onto the joining block:
 * /pd group enrollment false stops joining by the block while keeping the block and the members, true resumes it, another
 * group is not affected, the setting is saved and survives /pd reload, bad input is refused, tab completion and help.
 * Nothing here is visual verification.
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
const log = (...a) => console.log('[enrollment-test]', ...a)
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
const info = name => runBlock('pd group info ' + name, /Nether travel (on|off)/)

const mineflayer = require('@wp2508/mineflayer')
const ALPHA = (x, y) => (x.toLowerCase() < y.toLowerCase() ? -1 : x.toLowerCase() > y.toLowerCase() ? 1 : 0)
const isSorted = l => l.every((v, i) => i === 0 || ALPHA(l[i - 1], v) <= 0)
const names = o => o.map(x => (x.match || x).toString())
const tab = async (bot, text) => names(await bot.tabComplete(text, true))
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

async function main () {
  prepareServer()
  fs.mkdirSync(REPORT, { recursive: true })
  srv.start()
  await srv.waitForLine(/Done \(/, 300000, 0, 'server start')
  check('plugin enabled', srv.lines.some(l => /PortalDirector ready/.test(l)))
  const found = await runUntil('execute in minecraft:overworld run locate biome minecraft:plains', /\[(-?\d+), (?:~|-?\d+), (-?\d+)\]/, 120000)
  const loc = found.match(/\[(-?\d+), (?:~|-?\d+), (-?\d+)\]/)
  const X0 = +loc[1] + 40
  const Z0 = +loc[2] + 40
  const Y = 150
  await runUntil(`forceload add ${X0 - 20} ${Z0 - 20} ${X0 + 40} ${Z0 + 20}`, /Marked \d+ chunks/, 600000)
  await sleep(3000)
  // a stone road with an obsidian block at x+5 and a gold block at x+15, in the sky so nothing else is near
  await run(`fill ${X0 - 5} ${Y - 1} ${Z0 - 3} ${X0 + 30} ${Y - 1} ${Z0 + 3} stone`, 2500)
  const OB = X0 + 5
  const GB = X0 + 15
  await run(`setblock ${OB} ${Y - 1} ${Z0} obsidian`, 300)
  await run(`setblock ${GB} ${Y - 1} ${Z0} gold_block`, 300)

  for (const n of ['Alpha', 'Bravo', 'Charlie', 'Obs']) await connect(n)
  for (const n of ['Alpha', 'Bravo', 'Charlie', 'Obs']) await run('op ' + n, 120)
  const OBSERVER = bots.get('Obs')

  /** A bot walks east over the block at blockX. */
  async function walkOver (name, blockX) {
    const bot = bots.get(name)
    await run(`execute in minecraft:overworld run tp ${name} ${blockX - 2.5} ${Y} ${Z0 + 0.5} 270 0`, 700)
    bot.look(-Math.PI / 2, 0, true)
    await sleep(500)
    bot.setControlState('forward', true)
    await waitFor(async () => (await serverX(name)) > blockX + 1.5, 6000, 100)
    bot.setControlState('forward', false)
    await sleep(500)
  }
  // the console prints the arrow of /pd group info as a question mark, so only the name at the start of its line is matched
  const listed = (out, n) => new RegExp('^\\s+' + n + '\\b', 'm').test(out)

  await run('pd group create NULL')
  await run('pd group create Other')
  let out = await run('pd group block obsidian NULL')
  check('a joining block is set', /step on obsidian now join NULL/.test(out), last(out))
  await run('pd group block gold_block Other')

  // ---- enrollment is open by default
  log('open by default')
  out = await run('pd group enrollment NULL', 500)
  out = await run('pd group enrollment', 500)
  check('with several groups and no name the command asks which one', /more than one group|Which group|Say which/i.test(out), last(out))
  out = await run('pd group enrollment true NULL', 500)
  check('true opens it', /Enrollment is open/.test(out), last(out))
  await walkOver('Alpha', OB)
  out = await info('NULL')
  check('a player who steps on the block joins', listed(out, 'Alpha'), last(out))
  check('info shows the joining block and that enrollment is open', /Joining block: obsidian, enrollment open/.test(out), out.split('\n').find(l => /Joining block/.test(l)))

  // ---- closing it
  log('closed')
  out = await run('pd group enrollment false NULL', 600)
  check('false closes it and says the members stay', /Enrollment is closed/.test(out) && /1 member stays/.test(out), last(out))
  out = await info('NULL')
  check('info shows it closed, with the block kept and Alpha still in', /Joining block: obsidian, enrollment closed/.test(out) && listed(out, 'Alpha'), out.split('\n').find(l => /Joining block/.test(l)))
  await walkOver('Bravo', OB)
  await walkOver('Bravo', OB)
  out = await info('NULL')
  check('a player who steps on the block no longer joins', !listed(out, 'Bravo') && listed(out, 'Alpha'), last(out))
  await walkOver('Alpha', OB)
  out = await info('NULL')
  check('an existing member stepping on it changes nothing', (out.match(/Alpha/g) || []).length === 1, last(out))
  out = await run('pd group enrollment NULL', 500)
  check('asking says closed', /Enrollment is closed for NULL/.test(out), last(out))
  const file = path.join(PLUGIN_DIR, 'groups.yml')
  check('it is saved in groups.yml', /enrollment: false/.test(fs.readFileSync(file, 'utf8')))

  // ---- another group is not affected
  log('another group')
  await walkOver('Charlie', GB)
  out = await info('Other')
  check('a different group with its own block still enrolls while NULL is closed', listed(out, 'Charlie'), last(out))

  // ---- reload keeps it closed
  await run('pd reload', 800)
  out = await info('NULL')
  check('after /pd reload it is still closed', /enrollment closed/.test(out), out.split('\n').find(l => /Joining block/.test(l)))
  await walkOver('Bravo', OB)
  out = await info('NULL')
  check('and still nobody joins', !listed(out, 'Bravo'), last(out))

  // ---- opening it again
  log('open again')
  out = await run('pd group enrollment on NULL', 600)
  check('on works like true', /Enrollment is open/.test(out), last(out))
  await walkOver('Bravo', OB)
  out = await info('NULL')
  check('players join through the block again', listed(out, 'Bravo') && listed(out, 'Alpha'), last(out))
  out = await run('pd group enrollment off NULL', 500)
  check('off works like false', /Enrollment is closed/.test(out), last(out))
  out = await run('pd group enrollment maybe NULL', 500)
  check('anything else is refused with the two choices', /enrollment true .* enrollment false/.test(out), last(out))
  out = await run('pd group enrollment true Nogroup', 500)
  check('an unknown group is refused', /no group called Nogroup/.test(out), last(out))
  await run('pd group enrollment true NULL', 400)

  // ---- tab completion and help
  log('tab and help')
  try {
    let t = await tab(OBSERVER, '/pd group ')
    check('/pd group suggests enrollment in A to Z order', t.includes('enrollment') && isSorted(t), t.join(' '))
    t = await tab(OBSERVER, '/pd group enrollment ')
    check('enrollment suggests true and false', JSON.stringify(t) === JSON.stringify(['false', 'true']), t.join(' '))
    t = await tab(OBSERVER, '/pd group enrollment false ')
    check('then the group names', t.includes('NULL') && t.includes('Other'), t.join(' '))
  } catch (e) { check('tab completion', false, e.message) }
  out = await runBlock('pd help group 2', /Page \d+\/\d+/).catch(() => '')
  out += await runBlock('pd help group', /Page \d+\/\d+/).catch(() => '')
  check('help lists it', /enrollment true\|false/.test(out), last(out))

  const errors = srv.lines.filter(l => /(ERROR|Exception)/.test(l) && /PortalDirector|portaldirector/i.test(l))
  check('no plugin errors in the console', errors.length === 0, errors.slice(0, 2).join(' | '))
  fs.writeFileSync(path.join(REPORT, 'enrollment-latest.json'), JSON.stringify({ checks }, null, 2))
  fs.writeFileSync(path.join(REPORT, 'enrollment-console.log'), srv.lines.join('\n'))
  for (const b of bots.values()) { try { b.end() } catch (e) { /* ignore */ } }
  await sleep(1000)
  await srv.stop().catch(() => {})
  const failed = checks.filter(c => !c.ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed`)
  process.exit(failed.length ? 1 : 0)
}

main().catch(async e => {
  console.error(e)
  try { fs.writeFileSync(path.join(REPORT, 'enrollment-console.log'), srv.lines.join('\n')) } catch (x) { /* ignore */ }
  for (const b of bots.values()) { try { b.end() } catch (x) { /* ignore */ } }
  await srv.stop().catch(() => {})
  process.exit(2)
})
