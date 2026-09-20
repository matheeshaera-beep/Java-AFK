// DEV-ONLY window test for the inventory GUI feature (NOT shipped in the APK).
// Fake 1.21.1 server opens a chest menu; asserts /window contents and that
// clicks reach the server as window_click packets.
//
// Usage: node scripts/test-window.js
const { spawn } = require('child_process')
const path = require('path')

const MAIN_JS = path.join(__dirname, '..', 'android/app/src/main/assets/nodejs-project/main.js')
const NODE_MODULES = path.join(__dirname, '..', 'android/app/src/main/assets/nodejs-project/node_modules')
const mc = require(path.join(NODE_MODULES, 'minecraft-protocol'))
const mcData = require(path.join(NODE_MODULES, 'minecraft-data'))('1.21.1')

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
const emptySlot = () => ({ itemCount: 0 })
const fullSlot = (itemId, count) => ({
  itemCount: count, itemId, addedComponentCount: 0, removedComponentCount: 0,
  components: [], removeComponents: []
})

async function main() {
  const diamondId = mcData.itemsByName['diamond'].id
  const ironId = mcData.itemsByName['iron_ingot'].id
  console.log('item ids: diamond=' + diamondId + ' iron_ingot=' + ironId)
  const log = { lines: [] }
  const bridge = spawn(process.execPath, [MAIN_JS], { stdio: ['ignore', 'pipe', 'pipe'] })
  bridge.stdout.on('data', d => d.toString().split('\n').forEach(l => { l = l.trim(); if (l) log.lines.push(l) }))

  for (let i = 0; i < 60; i++) {
    try { const s = await api('GET', '/status'); if (s && s.ready) break } catch (e) {}
    await sleep(500)
  }

  const clicks = []
  let srvClient = null
  let serverSawClose = false
  const srv = mc.createServer({ 'online-mode': false, version: '1.21.1', port: 25585, host: '127.0.0.1' })
  srv.on('playerJoin', (client) => {
    srvClient = client
    client.on('close_window', () => { serverSawClose = true })
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
    client.on('window_click', (pkt) => clicks.push({ windowId: pkt.windowId, slot: pkt.slot, mode: pkt.mode, mouseButton: pkt.mouseButton }))
    // open a 27-slot chest menu 1.5 s after join
    setTimeout(() => {
      try {
        client.write('open_window', { windowId: 1, inventoryType: 2, windowTitle: nbt('Shop') })
        const items = []
        for (let i = 0; i < 63; i++) items.push(emptySlot())
        items[0] = fullSlot(diamondId, 3)
        items[5] = fullSlot(ironId, 64)
        setTimeout(() => {
          try { client.write('window_items', { windowId: 1, stateId: 0, items, carriedItem: emptySlot() }) } catch (e) {}
        }, 300)
      } catch (e) {}
    }, 1500)
  })

  try {
    await api('POST', '/servers/w/start', { host: '127.0.0.1', port: 25585, auth: 'offline', username: 'W' })
    let win = null
    for (let i = 0; i < 30; i++) {
      await sleep(500)
      win = await api('GET', '/servers/w/window')
      if (win.open) break
    }
    console.log('/window response: ' + JSON.stringify(win))
    check('window-open', win.open === true, 'title=' + win.title)
    check('window-title', win.title === 'Shop', JSON.stringify(win.title))
    const bySlot = Object.fromEntries((win.slots || []).map(s => [s.slot, s]))
    check('slot0-diamond', bySlot[0] && /diamond/i.test(bySlot[0].name) && bySlot[0].count === 3, JSON.stringify(bySlot[0]))
    check('slot5-iron', bySlot[5] && /iron/i.test(bySlot[5].name) && bySlot[5].count === 64, JSON.stringify(bySlot[5]))
    check('empties-skipped', (win.slots || []).length === 2, 'slots=' + (win.slots || []).length)

    const c0 = await api('POST', '/servers/w/window/click', { slot: 0 })
    const c5 = await api('POST', '/servers/w/window/click', { slot: 5 })
    await sleep(1500)
    console.log('server got clicks: ' + JSON.stringify(clicks))
    check('click-ack', c0.ok === true && c5.ok === true)
    check('click-slot0-reached', clicks.some(c => c.slot === 0), JSON.stringify(clicks))
    check('click-slot5-reached', clicks.some(c => c.slot === 5), JSON.stringify(clicks))
    const openLog = log.lines.find(l => /Window opened/.test(l))
    const clickLog = log.lines.filter(l => /Window click/.test(l))
    console.log('bridge log: ' + JSON.stringify([openLog, ...clickLog]))
    check('bridge-logs', !!openLog && clickLog.length >= 2, (openLog || 'no open log') + ' | clicks=' + clickLog.length)

    // bad slot rejected, and clicks with no window rejected
    const bad = await api('POST', '/servers/w/window/click', { slot: 'x' })
    check('bad-slot-rejected', bad.ok === false, JSON.stringify(bad))

    // user swipe-down: close route hits the server, window goes null
    const closeRes = await api('POST', '/servers/w/window/close', {})
    await sleep(1500)
    const afterClose = await api('GET', '/servers/w/window')
    console.log('close res: ' + JSON.stringify(closeRes) + ' window now: ' + JSON.stringify(afterClose) + ' serverSawClose=' + serverSawClose)
    check('close-ack', closeRes.ok === true)
    check('close-reached-server', serverSawClose === true)
    check('window-null-after-close', afterClose.open === false, JSON.stringify(afterClose))
    await api('POST', '/servers/w/stop', {})
  } finally {
    srv.close()
    bridge.kill()
  }
  const failed = results.filter(r => !r.ok)
  console.log('\n==== SUMMARY: ' + (results.length - failed.length) + '/' + results.length + ' passed ====')
  process.exit(failed.length ? 1 : 0)
}

main().catch(e => { console.error('HARNESS ERROR', e); process.exit(2) })
