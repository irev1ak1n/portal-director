'use strict'
/*
 * Prepares a fresh play server for manual testing: generates the area around a grassy plains spot, puts the world
 * spawn there and shuts the server down cleanly. Usage: node prepare-play.js <server-dir> <port>
 */
const fs = require('node:fs')
const path = require('node:path')
const { TestServer } = require('./lib/server')

const dir = path.resolve(process.argv[2] || path.join(__dirname, '..', '..', 'portal-director-play'))
const port = process.argv[3] || '25592'
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

async function main () {
  const props = fs.readFileSync(path.join(dir, 'server.properties'), 'utf8')
  if (!new RegExp('^server-ip=127\\.0\\.0\\.1$', 'm').test(props) || !new RegExp('^server-port=' + port + '$', 'm').test(props)) {
    throw new Error('Refusing to run: the server must be bound to 127.0.0.1:' + port)
  }
  fs.rmSync(path.join(dir, 'world'), { recursive: true, force: true })
  const srv = new TestServer({ dir, jar: 'paper-26.3-231.jar', memory: '2G' })
  const run = async (cmd, settle = 500) => {
    const from = srv.lines.length
    srv.command(cmd)
    await sleep(settle)
    return srv.lines.slice(from).join('\n')
  }
  srv.start()
  try {
    await srv.waitForLine(/Done \(/, 240000, 0, 'server start')
    let from = srv.lines.length
    srv.command('execute in minecraft:overworld run locate biome minecraft:plains')
    const hit = await srv.waitForLine(/\[(-?\d+), (?:~|-?\d+), (-?\d+)\]/, 90000, from, 'plains')
    const m = hit.match(/\[(-?\d+), (?:~|-?\d+), (-?\d+)\]/)
    const bx = +m[1]
    const bz = +m[2]
    console.log('plains near', bx, bz)
    await run(`forceload add ${bx - 160} ${bz - 160} ${bx + 160} ${bz}`, 1000)
    await run(`forceload add ${bx - 160} ${bz + 1} ${bx + 160} ${bz + 160}`, 1000)
    await sleep(40000)
    let chosen = null
    for (let ring = 0; ring < 8 && !chosen; ring++) {
      for (const [dx, dz] of [[0, 0], [24, 0], [-24, 0], [0, 24], [0, -24], [24, 24], [-24, -24], [24, -24], [-24, 24]]) {
        const x = bx + dx + ring * 40
        const z = bz + dz
        await run(`execute positioned ${x} 0 ${z} positioned over motion_blocking_no_leaves if block ~ ~-1 ~ minecraft:grass_block if biome ~ ~ ~ minecraft:plains run summon marker ~ ~ ~ {Tags:["pdspawn"]}`, 120)
        const out = await run('data get entity @e[type=minecraft:marker,tag=pdspawn,limit=1] Pos', 250)
        const p = out.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
        await run('kill @e[type=minecraft:marker,tag=pdspawn]', 80)
        if (p) {
          chosen = { x: Math.floor(+p[1]), y: Math.floor(+p[2]), z: Math.floor(+p[3]) }
          break
        }
      }
    }
    if (!chosen) throw new Error('no grassy plains spot found near ' + bx + ', ' + bz)
    await run(`setworldspawn ${chosen.x} ${chosen.y} ${chosen.z}`)
    await run('gamerule spawn_radius 4', 300)
    await run('forceload remove all', 500)
    fs.writeFileSync(path.join(dir, 'PLAY-SPOT.txt'), `Grassy plains spawn: ${chosen.x} ${chosen.y} ${chosen.z}\n`)
    console.log('spawn set to', chosen)
  } finally {
    await srv.stop()
  }
}

main().catch(e => { console.error('ERROR', e.message); process.exit(1) })
