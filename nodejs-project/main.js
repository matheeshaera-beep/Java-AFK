const mineflayer = require('mineflayer')
const http = require('http')
const fs = require('fs')

// ============ CONFIG ============
const DEFAULT_HOST = '127.0.0.1'
const DEFAULT_PORT = 25565
const DEFAULT_USERNAME = 'AFKBot'
const DEFAULT_AUTH = 'offline'
const BRIDGE_PORT = 3000

// ============ STATE ============
let config = {
  host: DEFAULT_HOST,
  port: DEFAULT_PORT,
  username: DEFAULT_USERNAME,
  auth: DEFAULT_AUTH,
  accessToken: null,
  profilesFolder: null
}
let bot = null
let bridgeServer = null
let reconnectTimer = null
let isConnecting = false
let sessionActive = false
let creating = false
let pendingMsaCode = null
const logBuffer = []
const origLog = console.log
console.log = (...args) => {
  const msg = args.map(a => typeof a === 'string' ? a : JSON.stringify(a)).join(' ')
  logBuffer.push(Date.now() + ' ' + msg)
  if (logBuffer.length > 200) logBuffer.shift()
  origLog(...args)
}

// ============ BRIDGE SERVER ============
function startBridgeServer() {
  bridgeServer = http.createServer((req, res) => {
    if (req.method === 'POST' && req.url === '/command') {
      let body = ''
      req.on('data', chunk => body += chunk)
      req.on('end', () => {
        try {
          const cmd = JSON.parse(body)
          const result = handleCommand(cmd)
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: true, ...result }))
        } catch (e) {
          res.writeHead(400, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ ok: false, error: e.message }))
        }
      })
    } else if (req.method === 'GET' && req.url === '/status') {
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify(statusJson()))
    } else if (req.method === 'GET' && req.url === '/logs') {
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify(logBuffer))
    } else {
      res.writeHead(404)
      res.end()
    }
  })

  bridgeServer.listen(BRIDGE_PORT, '127.0.0.1', () => {
    console.log(`Bridge server listening on 127.0.0.1:${BRIDGE_PORT}`)
  })
}

function statusJson() {
  const status = {
    connected: !!bot?.entity,
    username: bot?.username,
    health: bot?.health,
    food: bot?.food,
    position: bot?.entity?.position,
    active: sessionActive
  }
  if (pendingMsaCode) {
    status.msa_code = {
      userCode: pendingMsaCode.userCode,
      verificationUri: pendingMsaCode.verificationUri,
      message: pendingMsaCode.message
    }
  }
  return status
}

function log(...args) {
  console.log(...args)
}

// ============ BOT ============
function disposeBot() {
  if (bot) {
    try { bot.quit('Session closed') } catch (e) {}
    bot = null
  }
}

function scheduleReconnect() {
  if (!sessionActive || isConnecting) return
  isConnecting = true
  const delay = pendingMsaCode ? 20000 : 5000
  log(`Reconnecting in ${delay / 1000}s...`)
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    createBot()
  }, delay)
}

function createBot() {
  if (!sessionActive || creating) return
  creating = true
  isConnecting = true
  disposeBot()

  const opts = {
    host: config.host,
    port: config.port,
    username: config.username,
    auth: config.auth,
    version: false
  }

  if (config.auth === 'microsoft' && config.accessToken) {
    opts.accessToken = config.accessToken
  }

  if (config.auth === 'microsoft') {
    opts.profilesFolder = config.profilesFolder
    opts.onMsaCode = (response) => {
      log('[MSA] Device code:', response && response.user_code)
      pendingMsaCode = {
        userCode: response.user_code,
        verificationUri: response.verification_uri || response.verificationUrl || 'https://www.microsoft.com/link',
        message: response.message
      }
    }
  }

  log('Creating bot:', { host: opts.host, port: opts.port, auth: opts.auth, username: opts.username, hasToken: !!opts.accessToken, profilesFolder: opts.profilesFolder || null })

  bot = mineflayer.createBot(opts)

  bot.on('session', (sess) => {
    log('Session attached:', sess && sess.username || 'none')
  })

  bot.on('login', (sess) => {
    log('Logged in as:', bot.username || sess.username)
  })

  bot.once('spawn', () => {
    log('Bot spawned!')
    isConnecting = false
    creating = false
    pendingMsaCode = null
    if (config.chatCommand) {
      setTimeout(() => {
        try { bot.chat(config.chatCommand) } catch (e) {}
      }, (config.commandDelaySeconds || 5) * 1000)
    }
  })

  bot.on('kicked', (reason, loggedIn) => {
    log('Kicked:', reason)
    pendingMsaCode = null
    scheduleReconnect()
  })

  bot.on('end', (reason) => {
    log('Ended:', reason)
    pendingMsaCode = null
    creating = false
    scheduleReconnect()
  })

  bot.on('error', (err) => {
    log('Error:', err.message)
    creating = false
    scheduleReconnect()
  })
}

function handleCommand(cmd) {
  switch (cmd.type) {
    case 'start': {
      sessionActive = true
      const { type, ...rest } = cmd
      config = { ...config, ...rest }
      if (reconnectTimer) clearTimeout(reconnectTimer)
      reconnectTimer = null
      isConnecting = false
      pendingMsaCode = null
      createBot()
      return { started: true }
    }
    case 'stop':
      sessionActive = false
      pendingMsaCode = null
      if (reconnectTimer) clearTimeout(reconnectTimer)
      reconnectTimer = null
      creating = false
      isConnecting = false
      disposeBot()
      return { stopped: true }
    case 'reconnect':
      if (!sessionActive) return { ok: false }
      if (reconnectTimer) clearTimeout(reconnectTimer)
      reconnectTimer = null
      isConnecting = false
      createBot()
      return { ok: true }
    case 'chat':
      if (cmd.message && bot) bot.chat(cmd.message)
      return { ok: true }
    case 'config':
      if (cmd.config) config = { ...config, ...cmd.config }
      return { ok: true }
    case 'auth_done':
      pendingMsaCode = null
      return { ok: true }
    default:
      return { ok: false }
  }
}

// ============ STARTUP ============
startBridgeServer()
log('Java AFK Bot ready (bridge on port', BRIDGE_PORT, ')')