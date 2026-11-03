'use strict'
const { spawn, execFileSync } = require('node:child_process')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { EventEmitter } = require('node:events')

const ANSI = /\x1b\[[0-9;]*m/g

function findJava () {
  if (process.env.JAVA25_HOME) return path.join(process.env.JAVA25_HOME, 'bin', 'java.exe')
  const jdks = path.join(os.homedir(), '.gradle', 'jdks')
  if (fs.existsSync(jdks)) {
    const hit = fs.readdirSync(jdks).find(d => d.startsWith('eclipse_adoptium-25'))
    if (hit) return path.join(jdks, hit, 'bin', 'java.exe')
  }
  return 'java'
}

/** Runs the isolated Paper server and gives the harness its console. */
class TestServer extends EventEmitter {
  constructor ({ dir, jar, memory = '1200M' }) {
    super()
    Object.assign(this, { dir, jar, memory })
    this.logFile = process.env.PD_LOG || null
    this.lines = []
    this.proc = null
    this.exitCode = null
  }

  start () {
    this.exitCode = null
    this.proc = spawn(findJava(), ['-Xms256M', `-Xmx${this.memory}`, '-XX:+UseSerialGC', '-XX:TieredStopAtLevel=1', '-jar', this.jar, '--nogui'], {
      cwd: this.dir,
      stdio: ['pipe', 'pipe', 'pipe']
    })
    let buffer = ''
    const onData = chunk => {
      buffer += chunk.toString('utf8')
      let i
      while ((i = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, i).replace(/\r$/, '').replace(ANSI, '')
        buffer = buffer.slice(i + 1)
        this.lines.push(line)
        if (this.logFile) fs.appendFileSync(this.logFile, line + String.fromCharCode(10))
        this.emit('line', line)
      }
    }
    this.proc.stdout.on('data', onData)
    this.proc.stderr.on('data', onData)
    this.proc.on('exit', code => { this.exitCode = code; this.emit('exit', code) })
    return this
  }

  command (text) {
    this.proc.stdin.write(text + '\n')
  }

  /** Resolves with the first line after `from` (a lines index) that matches. */
  waitForLine (regex, timeoutMs, from = 0, label = String(regex)) {
    const existing = this.lines.slice(from).find(l => regex.test(l))
    if (existing) return Promise.resolve(existing)
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.off('line', onLine)
        reject(new Error(`Timed out waiting for ${label}`))
      }, timeoutMs)
      const onLine = line => {
        if (regex.test(line)) {
          clearTimeout(timer)
          this.off('line', onLine)
          resolve(line)
        }
      }
      this.on('line', onLine)
    })
  }

  async stop (timeoutMs = 60000) {
    if (!this.proc || this.exitCode !== null) return this.exitCode
    this.command('stop')
    await new Promise(resolve => {
      const timer = setTimeout(() => {
        try { execFileSync('taskkill', ['/PID', String(this.proc.pid), '/T', '/F'], { stdio: 'ignore' }) } catch {}
        resolve()
      }, timeoutMs)
      this.once('exit', () => { clearTimeout(timer); resolve() })
    })
    return this.exitCode
  }
}

module.exports = { TestServer }
