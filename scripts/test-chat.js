// DEV-ONLY chat test for main.js Phase-3 (NOT shipped in the APK).
// Verifies: no missed/duplicated messages, no UUID senders, no action-bar
// lines, profileless_chat fallback, server-side 'out' echo.
//
// Usage: node scripts/test-chat.js
const { spawn } = require('child_process')
const path = require('path')

const MAIN_JS = path.join(__dirname, '..', 'android/app/src/main/assets/nodejs-project/main.js')
const NODE_MODULES = path.join(__dirname, '..', 'android/app/src/main/assets/nodejs-project/node_modules')
const mc = require(path.join(NODE_MODULES, 'minecraft-protocol'))

const BRIDGE = 'http://127.0.0.1:3001'
const results = []
function check(name, ok, detail) {
  results.push({ name, ok })
  console.log((ok ? 'PASS' : 'FAIL') + ' | ' + name + (detail ? ' | ' + detail : ''))
}
async function api(method, p, body) {
  const r = await fetch(BRIDGE + p, {
    method, headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined
  })
  return r.json()
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms))
const nbt = (text) => ({ type: 'compound', name: '', value: { text: { type: 'string', value: text } } })

async function main() {
  const log = { lines: [] }
  const bridge = spawn(process.execPath, [MAIN_JS], { stdio: ['ignore', 'pipe', 'pipe'] })
  bridge.stdout.on('data', d => d.toString().split('\n').forEach(l => { l = l.trim(); if (l) log.lines.push(l) }))
  bridge.stderr.on('data', d => console.log('[bridge:err]', d.toString().trim()))

  for (let i = 0; i < 60; i++) {
    try { const s = await api('GET', '/status'); if (s && s.ready) break } catch (e) {}
    await sleep(500)
  }

  const seenAtServer = []
  const srv = mc.createServer({ 'online-mode': false, version: '1.21.1', port: 25595, host: '127.0.0.1' })
  srv.on('playerJoin', (client) => {
    client.write('login', {
      entityId: 1, isHardcore: false,
      worldNames: ['minecraft:overworld'], maxPlayers: 20,
      viewDistance: 2, simulationDistance: 2,
      reducedDebugInfo: false, enableRespawnScreen: true, doLimitedCrafting: false,
      worldState: { dimension: 0, name: 'minecraft:overworld', hashedSeed: [0, 0], gamemode: 1, previousGamemode: 1, isDebug: false, isFlat: false, death: undefined, portalCooldown: 0 },
      enforcesSecureChat: false
    })
    client.write('position', { x: 0, y: 80, z: 0, yaw: 0, pitch: 0, flags: {}, teleportId: 1 })
    client.write('update_health', { health: 20, food: 20, foodSaturation: 5 })
    // answer tab-complete so queued commands don't stall on the 5 s timeout
    client.on('tab_complete', (pkt) => {
      try { client.write('tab_complete', { transactionId: pkt.transactionId, start: 0, length: 1, matches: [] }) } catch (e) {}
    })
    client.on('chat_message', (pkt) => seenAtServer.push(pkt.message))
    client.on('chat_command', (pkt) => seenAtServer.push('/' + pkt.command))
  })

  try {
    await api('POST', '/servers/chat1/start', { host: '127.0.0.1', port: 25595, auth: 'offline', username: 'Chatter' })
    // wait for online
    let online = false
    for (let i = 0; i < 40; i++) {
      const st = await api('GET', '/servers/chat1/status')
      if (st.connected) { online = true; break }
      await sleep(500)
    }
    check('online', online)
    if (!online) throw new Error('bot never came online')

    // find the server-side client to push messages from "others"
    const clients = srv.clients ? Object.values(srv.clients) : []
    const srvClient = clients[0]
    check('server-client-found', !!srvClient)
    const serverTexts = []
    for (let i = 1; i <= 5; i++) {
      const t = 'Villager' + i + ': hello ' + i
      serverTexts.push(t)
      srvClient.write('system_chat', { content: nbt(t), isActionBar: false })
    }
    // action-bar flood (must be dropped)
    for (let i = 1; i <= 3; i++) srvClient.write('system_chat', { content: nbt('BOSS BAR ' + i), isActionBar: true })
    // profileless message (/say style)
    srvClient.write('profileless_chat', { message: nbt('secret meeting at dawn'), type: { chatType: 1 }, name: nbt('Steve'), target: undefined })
    // profileless with a bogus chatType: mineflayer's playerChat path throws
    // on it, so ONLY the fallback can capture this one
    srvClient.write('profileless_chat', { message: nbt('ghost dispatch'), type: { chatType: 9999 }, name: nbt('Ghost'), target: undefined })

    // bot sends 5 messages + 5 commands
    for (let i = 1; i <= 5; i++) await api('POST', '/servers/chat1/chat', { message: 'hello from bot ' + i })
    for (let i = 1; i <= 5; i++) await api('POST', '/servers/chat1/chat', { message: '/ping ' + i })
    await sleep(4000) // let queued sends + polls flush

    const chat = await api('GET', '/servers/chat1/chat?after=0')
    const msgs = chat.msgs || []
    const texts = msgs.map(m => m.text)
    const seqs = msgs.map(m => m.seq)
    console.log('chat lines:', JSON.stringify(texts))

    check('server-msgs-present', serverTexts.every(t => texts.some(x => x.includes(t))), serverTexts.length + ' expected')
    check('no-actionbar', !texts.some(x => /BOSS BAR/.test(x)))
    check('profileless-captured', texts.filter(x => x.includes('secret meeting')).length === 1, texts.filter(x => /Steve|secret/.test(x)).join(' || ') || 'missing')
    check('profileless-fallback', texts.filter(x => x.includes('ghost dispatch')).length === 1, texts.filter(x => /Ghost|ghost/.test(x)).join(' || ') || 'missing')
    const uuidRe = /[0-9a-f]{8}-[0-9a-f]{4}-/i
    check('no-uuid-senders', !msgs.some(m => m.sender && uuidRe.test(m.sender)), JSON.stringify(msgs.map(m => m.sender)))
    const outLines = msgs.filter(m => m.type === 'out')
    check('out-echo-10', outLines.length === 10, 'out count=' + outLines.length)
    check('no-dup-seq', new Set(seqs).size === seqs.length, 'n=' + seqs.length)
    // server must have received all 10 bot sends (nothing lost outbound)
    check('server-got-10', seenAtServer.length === 10, JSON.stringify(seenAtServer))
    await api('POST', '/servers/chat1/stop', {})
  } finally {
    srv.close()
    bridge.kill()
  }
  const failed = results.filter(r => !r.ok)
  console.log('\n==== SUMMARY: ' + (results.length - failed.length) + '/' + results.length + ' passed ====')
  process.exit(failed.length ? 1 : 0)
}

main().catch(e => { console.error('HARNESS ERROR', e); process.exit(2) })
