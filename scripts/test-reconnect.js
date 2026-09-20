// DEV-ONLY reconnect test harness for main.js (NOT shipped in the APK).
// Runs fake servers + the real bridge, asserts the Phase-1 reconnect behavior.
//
// Usage: node scripts/test-reconnect.js
// Env overrides for speed: AFK_BACKOFF_BASE_MS, AFK_CONNECT_TIMEOUT_MS, AFK_STABLE_MS
const { spawn } = require('child_process')
const net = require('net')
const path = require('path')

const MAIN_JS = path.join(__dirname, '..', 'android/app/src/main/assets/nodejs-project/main.js')
const NODE_MODULES = path.join(__dirname, '..', 'android/app/src/main/assets/nodejs-project/node_modules')
const mc = require(path.join(NODE_MODULES, 'minecraft-protocol'))

const BRIDGE = 'http://127.0.0.1:3001'
const results = []
function check(name, ok, detail) {
  results.push({ name, ok, detail })
  console.log((ok ? 'PASS' : 'FAIL') + ' | ' + name + (detail ? ' | ' + detail : ''))
}

async function api(method, p, body) {
  const r = await fetch(BRIDGE + p, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined
  })
  return r.json()
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms))

async function waitForBridge(log) {
  for (let i = 0; i < 60; i++) {
    try {
      const s = await api('GET', '/status')
      if (s && s.ready) return true
    } catch (e) {}
    await sleep(500)
  }
  console.log('bridge never became ready. log so far:\n' + log.lines.join('\n'))
  return false
}

async function main() {
  const env = {
    ...process.env,
    AFK_BACKOFF_BASE_MS: process.env.AFK_BACKOFF_BASE_MS || '1000',
    AFK_CONNECT_TIMEOUT_MS: process.env.AFK_CONNECT_TIMEOUT_MS || '4000',
    AFK_STABLE_MS: process.env.AFK_STABLE_MS || '5000'
  }
  console.log('env: BACKOFF_BASE=' + env.AFK_BACKOFF_BASE_MS + ' CONNECT_TIMEOUT=' + env.AFK_CONNECT_TIMEOUT_MS + ' STABLE=' + env.AFK_STABLE_MS)
  const log = { lines: [] }
  const bridge = spawn(process.execPath, [MAIN_JS], { env, stdio: ['ignore', 'pipe', 'pipe'] })
  bridge.stdout.on('data', d => d.toString().split('\n').forEach(l => { l = l.trim(); if (l) { log.lines.push(l); console.log('[bridge]', l) } }))
  bridge.stderr.on('data', d => d.toString().split('\n').forEach(l => { l = l.trim(); if (l) console.log('[bridge:err]', l) }))

  if (!await waitForBridge(log)) { bridge.kill(); process.exit(1) }
  const since = (n) => log.lines.slice(n)
  const hasLine = (arr, re) => arr.find(l => re.test(l))

  // ---------- fake servers ----------
  // a. accept TCP and destroy at once
  const destroySrv = net.createServer(sock => sock.destroy())
  await new Promise(r => destroySrv.listen(25599, '127.0.0.1', r))
  // c. accept TCP but never answer
  const holdSrv = net.createServer(() => {})
  await new Promise(r => holdSrv.listen(25598, '127.0.0.1', r))
  // b/e. minimal 1.21.1 MC server: login + position + health -> spawn; kick on demand.
  // NOTE: 1.20.2+ has a configuration state, so play packets must be sent on
  // 'playerJoin' (post-configuration), not 'login'.
  const mcServerState = { mode: 'kick-once', kicked: false, connections: 0 }
  const mcSrv = mc.createServer({ 'online-mode': false, version: '1.21.1', port: 25597, host: '127.0.0.1' })
  mcSrv.on('playerJoin', (client) => {
    mcServerState.connections++
    client.write('login', {
      entityId: 1, isHardcore: false,
      worldNames: ['minecraft:overworld'], maxPlayers: 20,
      viewDistance: 2, simulationDistance: 2,
      reducedDebugInfo: false, enableRespawnScreen: true, doLimitedCrafting: false,
      worldState: { dimension: 0, name: 'minecraft:overworld', hashedSeed: [0, 0], gamemode: 1, previousGamemode: 1, isDebug: false, isFlat: false, death: undefined, portalCooldown: 0 },
      enforcesSecureChat: false
    })
    client.write('position', { x: 0, y: 80, z: 0, yaw: 0, pitch: 0, flags: {}, teleportId: 1 })
    // mineflayer emits 'spawn' on the first update_health with health > 0
    client.write('update_health', { health: 20, food: 20, foodSaturation: 5 })
    const mode = mcServerState.mode
    if (mode === 'kick-once' && !mcServerState.kicked) {
      mcServerState.kicked = true
      setTimeout(() => {
        try {
          client.write('kick_disconnect', { reason: { type: 'compound', name: '', value: { text: { type: 'string', value: 'Kicked by test: stop flying' } } } })
          client.end()
        } catch (e) {}
      }, 1500)
    } else if (mode === 'ban') {
      setTimeout(() => {
        try {
          client.write('kick_disconnect', { reason: { type: 'compound', name: '', value: { text: { type: 'string', value: 'You are banned from this server!' } } } })
          client.end()
        } catch (e) {}
      }, 1500)
    }
    // mode 'stay': remain online
  })

  try {
    // ===== a. destroy-at-connect -> repeated backoff retries, forever =====
    let m0 = log.lines.length
    await api('POST', '/servers/a/start', { host: '127.0.0.1', port: 25599, auth: 'offline', username: 'T' })
    await sleep(12000) // attempts at ~1s,2s,4s (base 1s) -> ~4 retries in window
    const recA = since(m0).filter(l => /Reconnecting in/.test(l))
    const attempts = recA.map(l => parseInt((l.match(/attempt (\d+)/) || [])[1] || '0', 10))
    const secs = recA.map(l => parseInt((l.match(/Reconnecting in (\d+)s/) || [])[1] || '0', 10))
    const growing = secs.length >= 3 && secs[0] <= secs[1] && secs[1] <= secs[2]
    check('a.destroy-retries-repeat', recA.length >= 3, recA.slice(0, 5).join(' || '))
    check('a.backoff-grows', growing, 'secs=' + secs.join(',') + ' attempts=' + attempts.join(','))
    const stA = await api('GET', '/servers/a/status')
    check('a.phase-waiting-while-down', stA.phase === 'waiting' && stA.connected === false, JSON.stringify(stA))
    await api('POST', '/servers/a/stop', {})
    const stA2 = await api('GET', '/servers/a/status')
    check('a.stop-idle', stA2.phase === 'idle' && stA2.attempt === 0, JSON.stringify(stA2))

    // ===== c. hold-open (no data) -> watchdog fires, retries continue =====
    m0 = log.lines.length
    await api('POST', '/servers/c/start', { host: '127.0.0.1', port: 25598, auth: 'offline', username: 'T' })
    await sleep(11000) // watchdog 4s -> backoff ~1s -> watchdog 4s -> attempt 2+
    const cLines = since(m0)
    const timeouts = cLines.filter(l => /connect timeout/i.test(l)).length
    check('c.watchdog-fires', timeouts >= 1, cLines.filter(l => /timeout|Reconnecting/i.test(l)).join(' || '))
    const stC = await api('GET', '/servers/c/status')
    check('c.retries-continue', stC.attempt >= 2 && stC.lastError === 'connect timeout', JSON.stringify(stC))
    await api('POST', '/servers/c/stop', {})

    // ===== b. spawn -> NBT kick (readable) -> reconnect -> stable reset =====
    m0 = log.lines.length
    mcServerState.mode = 'kick-once'; mcServerState.kicked = false
    await api('POST', '/servers/b/start', { host: '127.0.0.1', port: 25597, auth: 'offline', username: 'T' })
    await sleep(6000) // spawn + kick at 1.5s + reconnect (base 1s) + 2nd spawn
    const bLines = since(m0)
    check('b.spawned', !!hasLine(bLines, /Bot spawned!/), hasLine(bLines, /Bot spawned!/) || 'no spawn')
    const kickLine = hasLine(bLines, /Kicked:/)
    check('b.kick-readable', !!kickLine && !/\[object Object\]/.test(kickLine), kickLine || 'no kick line')
    const spawns = bLines.filter(l => /Bot spawned!/.test(l)).length
    check('b.reconnect-after-kick', spawns >= 2, 'spawn count=' + spawns)
    // stable reset: STABLE_MS=5s after 2nd spawn -> attempt back to 0
    await sleep(7000)
    const stB = await api('GET', '/servers/b/status')
    check('b.stable-resets-attempt', stB.phase === 'online' && stB.attempt === 0, JSON.stringify(stB))
    // chat captured the kick as an error line?
    const chatB = await api('GET', '/servers/b/chat?after=0')
    check('b.kick-in-chat', (chatB.msgs || []).some(m => /Kicked by test/.test(m.text)), JSON.stringify((chatB.msgs || []).map(m => m.text).slice(-3)))
    await api('POST', '/servers/b/stop', {})

    // ===== e. banned kick -> no retry =====
    m0 = log.lines.length
    mcServerState.mode = 'ban'
    await api('POST', '/servers/e/start', { host: '127.0.0.1', port: 25597, auth: 'offline', username: 'T' })
    await sleep(5000) // spawn + ban kick at 1.5s; must NOT schedule anything after
    const eLines = since(m0)
    check('e.autostop-logged', !!hasLine(eLines, /Auto-reconnect stopped/), hasLine(eLines, /Auto-reconnect stopped|Reconnecting/) || 'neither')
    const stopIdx = log.lines.findIndex((l, i) => i >= m0 && /Auto-reconnect stopped/.test(l))
    const afterStop = stopIdx >= 0 ? log.lines.slice(stopIdx + 1) : []
    check('e.no-retry-after-ban', stopIdx >= 0 && !afterStop.some(l => /Reconnecting in/.test(l)), 'lines after stop=' + afterStop.length)
    const stE = await api('GET', '/servers/e/status')
    check('e.session-inactive-idle', stE.active === false && stE.phase === 'idle', JSON.stringify(stE))

    // ===== d. 10x Start/Stop -> handles + RSS flat =====
    const dbg0 = await api('GET', '/debug')
    for (let i = 0; i < 10; i++) {
      await api('POST', '/servers/d/start', { host: '127.0.0.1', port: 25599, auth: 'offline', username: 'T' })
      await sleep(300)
      await api('POST', '/servers/d/stop', {})
    }
    await sleep(1000)
    global.gc && global.gc()
    const dbg1 = await api('GET', '/debug')
    console.log('debug before: ' + JSON.stringify(dbg0) + ' after: ' + JSON.stringify(dbg1))
    check('d.handles-flat', dbg1.handles <= dbg0.handles + 3, 'handles ' + dbg0.handles + ' -> ' + dbg1.handles)
    check('d.rss-flat', dbg1.rssMB <= dbg0.rssMB + 15, 'rssMB ' + dbg0.rssMB + ' -> ' + dbg1.rssMB)
    await api('POST', '/servers/d/stop', {})
  } finally {
    destroySrv.close(); holdSrv.close(); mcSrv.close()
    bridge.kill()
  }

  const failed = results.filter(r => !r.ok)
  console.log('\n==== SUMMARY: ' + (results.length - failed.length) + '/' + results.length + ' passed ====')
  process.exit(failed.length ? 1 : 0)
}

main().catch(e => { console.error('HARNESS ERROR', e); process.exit(2) })
