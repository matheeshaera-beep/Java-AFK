const mineflayer = require('mineflayer')
const http = require('http')
const fs = require('fs')
const os = require('os')

// ============ CONFIG ============
const DEFAULT_HOST = '127.0.0.1'
const DEFAULT_PORT = 25565
const DEFAULT_USERNAME = 'AFKBot'
const DEFAULT_AUTH = 'offline'
const BRIDGE_PORT = 3000
const MAX_BODY = 1024 * 64 // 64 KB max bridge request body
const MAX_BOTS = 2 // hard limit: max 2 concurrent servers/bots
const MAX_CHAT = 300 // ring buffer of chat messages per server
const MAX_LOGS = 200 // ring buffer of log lines per server
const MAX_SAVE_CHAR = 0 // placeholder to keep fs usage intentional

const origLog = console.log

// ============ SESSION STORE ============
// One session entry per Minecraft server. Each owns its own bot, timers,
// log/chat buffers and MS auth state. A single Node process hosts all of them.
const sessions = new Map() // serverId (string) -> session

function newSession(serverId) {
  return {
    serverId,
    config: {
      host: DEFAULT_HOST,
      port: DEFAULT_PORT,
      username: DEFAULT_USERNAME,
      auth: DEFAULT_AUTH,
      accessToken: null,
      profilesFolder: null,
      chatCommand: '',
      commandDelaySeconds: 5,
      viewDistance: 12,
      chatMode: 'enabled'
    },
    bot: null,
    reconnectTimer: null,
    isConnecting: false,
    sessionActive: false,
    creating: false,
    pendingMsaCode: null,
    logBuffer: [],
    chatBuffer: [],
    chatSeq: 0
  }
}

function sessionLog(sess, msg) {
  try {
    const line = Date.now() + ' ' + String(msg)
    sess.logBuffer.push(line)
    if (sess.logBuffer.length > MAX_LOGS) sess.logBuffer.shift()
    origLog('[' + sess.serverId + ']', msg)
  } catch (e) {}
}

function pushChat(sess, type, sender, text) {
  if (!text) return
  sess.chatBuffer.push({
    seq: ++sess.chatSeq,
    ts: Date.now(),
    type: type || 'chat',
    sender: sender || null,
    text: String(text)
  })
  if (sess.chatBuffer.length > MAX_CHAT) sess.chatBuffer.shift()
}

// ============ BRIDGE SERVER (single, bound once per process) ============
function startBridgeServer() {
  const bridge = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1:' + BRIDGE_PORT)
    const path = url.pathname

    // Fast minimal handlers with tiny bodies
    const json = (code, obj) => {
      res.writeHead(code, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify(obj))
    }
    const readBody = (cb) => {
      let body = ''
      let overflow = false
      req.on('data', chunk => {
        body += chunk
        if (body.length > MAX_BODY) { overflow = true; req.destroy() }
      })
      req.on('error', () => cb(null, 'request error'))
      req.on('end', () => {
        if (overflow) return cb(null, 'body too large')
        try { cb(body ? JSON.parse(body) : {}) } catch (e) { cb(null, 'invalid json') }
      })
    }

    // GET /status
    if (req.method === 'GET' && path === '/status') {
      return json(200, {
        ok: true,
        maxBots: MAX_BOTS,
        active: [...sessions.values()].filter(s => s.sessionActive).length,
        ready: true
      })
    }

    // GET /servers
    if (req.method === 'GET' && path === '/servers') {
      const list = []
      for (const sess of sessions.values()) {
        list.push({ serverId: sess.serverId, active: sess.sessionActive, connected: !!sess.bot?.entity })
      }
      return json(200, { ok: true, servers: list })
    }

    // /servers/<serverId>/...
    const m = path.match(/^\/servers\/([^/]+)(?:\/(\w+))?$/)
    const serverId = m ? decodeURIComponent(m[1]) : null
    const action = m ? m[2] : null
    const sess = serverId != null ? sessions.get(serverId) : undefined

    if (m && action === 'start') {
      return readBody(cmd => {
        if (typeof cmd === 'string') return json(400, { ok: false, error: cmd })
        if (sess && sess.sessionActive) return json(200, { ok: true, already: true })
        const activeCount = [...sessions.values()].filter(s => s.sessionActive).length
        if (activeCount >= MAX_BOTS) {
          return json(409, { ok: false, error: 'Maximum 2 servers can run at the same time.' })
        }
        const s = sess || newSession(serverId)
        sessions.set(serverId, s)
        s.config = { ...s.config, ...(cmd || {}) }
        s.sessionActive = true
        if (s.reconnectTimer) clearTimeout(s.reconnectTimer)
        s.reconnectTimer = null
        s.isConnecting = false
        s.creating = false
        s.pendingMsaCode = null
        sessionLog(s, 'Session starting')
        createBot(s)
        return json(200, { ok: true, started: true, active: sessions.size })
      })
    }

    if (serverId == null || !sess) return json(404, { ok: false, error: 'session not found' })

    if (action === 'stop') {
      sess.sessionActive = false
      sess.pendingMsaCode = null
      if (sess.reconnectTimer) clearTimeout(sess.reconnectTimer)
      sess.reconnectTimer = null
      sess.creating = false
      sess.isConnecting = false
      disposeBot(sess)
      return json(200, { ok: true, stopped: true, active: sessions.size })
    }

    if (action === 'reconnect') {
      if (!sess.sessionActive) return json(200, { ok: false })
      if (sess.reconnectTimer) clearTimeout(sess.reconnectTimer)
      sess.reconnectTimer = null
      sess.isConnecting = false
      createBot(sess)
      return json(200, { ok: true })
    }

    if (action === 'auth_done') {
      // Kotlin told us it has shown the sign-in dialog; stop re-offering the code.
      sess.pendingMsaCode = null
      return json(200, { ok: true })
    }

    if (action === 'config') {
      return readBody(cmd => {
        if (typeof cmd === 'string') return json(400, { ok: false, error: cmd })
        sess.config = { ...sess.config, ...(cmd || {}) }
        return json(200, { ok: true })
      })
    }

    if (action === 'chat' && req.method === 'POST') {
      return readBody(cmd => {
        if (typeof cmd === 'string') return json(400, { ok: false, error: cmd })
        if (cmd.message && sess.bot?.entity) {
          try {
            const msg = String(cmd.message)
            const isCommand = msg.startsWith('/')
            if (isCommand || sess.config.chatMode !== 'commandsOnly') {
              sess.bot.chat(msg)
            }
          } catch (e) { sessionLog(sess, 'chat error: ' + e.message) }
        }
        return json(200, { ok: true })
      })
    }

    if (action === 'status') {
      const status = {
        ok: true,
        active: sess.sessionActive,
        connected: !!sess.bot?.entity,
        username: sess.bot?.username,
        health: sess.bot?.health,
        food: sess.bot?.food,
        position: sess.bot?.entity?.position
      }
      if (sess.pendingMsaCode) {
        status.msa_code = {
          userCode: sess.pendingMsaCode.userCode,
          verificationUri: sess.pendingMsaCode.verificationUri,
          message: sess.pendingMsaCode.message
        }
      }
      return json(200, status)
    }

    if (action === 'logs') {
      return json(200, { ok: true, logs: sess.logBuffer })
    }

    if (action === 'chat' && req.method === 'GET') {
      const after = parseInt(url.searchParams.get('after') || '0', 10)
      const lines = sess.chatBuffer.filter(c => c.seq > after)
      return json(200, { ok: true, msgs: lines })
    }
  })

  bridge.on('error', err => {
    origLog('[bridge] server error:', err && err.message)
  })

  bridge.listen(BRIDGE_PORT, '127.0.0.1', () => {
    origLog(`[bridge] listening on 127.0.0.1:${BRIDGE_PORT} (max ${MAX_BOTS} bots)`)
  })
}

// ============ BOT ============
function disposeBot(sess) {
  if (sess.bot) {
    try { sess.bot.removeAllListeners() } catch (e) {}
    try { sess.bot.quit('Session closed') } catch (e) {}
    sess.bot = null
  }
}

function scheduleReconnect(sess) {
  if (!sess.sessionActive || sess.isConnecting) return
  sess.isConnecting = true
  if (sess.reconnectTimer) clearTimeout(sess.reconnectTimer)
  const delay = sess.pendingMsaCode ? 20000 : 5000
  sessionLog(sess, 'Reconnecting in ' + (delay / 1000) + 's...')
  sess.reconnectTimer = setTimeout(() => {
    sess.reconnectTimer = null
    createBot(sess)
  }, delay)
}

function createBot(sess) {
  if (!sess.sessionActive || sess.creating) return
  sess.creating = true
  sess.isConnecting = true
  disposeBot(sess)

  const opts = {
    host: sess.config.host,
    port: sess.config.port,
    username: sess.config.username,
    auth: sess.config.auth,
    version: false
  }

  const vd = parseInt(sess.config.viewDistance, 10)
  if (!isNaN(vd) && vd >= 2 && vd <= 12) opts.viewDistance = vd

  // Client chat settings — tells the server how much public chat to send.
  // Safe: same packet mineflayer always writes at login (settings.js).
  opts.chat = sess.config.chatMode === 'commandsOnly' ? 'commandsOnly'
    : sess.config.chatMode === 'hidden' ? 'disabled' : 'enabled'

  if (sess.config.auth === 'microsoft' && sess.config.accessToken) {
    opts.accessToken = sess.config.accessToken
  }

  if (sess.config.auth === 'microsoft') {
    opts.profilesFolder = sess.config.profilesFolder
    opts.onMsaCode = (response) => {
      sessionLog(sess, '[MSA] Device code: ' + (response && response.user_code))
      sess.pendingMsaCode = {
        userCode: response.user_code,
        verificationUri: response.verification_uri || response.verificationUrl || 'https://www.microsoft.com/link',
        message: response.message
      }
    }
  }

  sessionLog(sess, 'Creating bot: ' + JSON.stringify({
    host: opts.host, port: opts.port, auth: opts.auth, username: opts.username,
    hasToken: !!opts.accessToken, chat: opts.chat
  }))

  let bot
  try {
    bot = mineflayer.createBot(opts)
  } catch (e) {
    sess.creating = false
    sess.isConnecting = false
    sessionLog(sess, 'createBot failed: ' + e.message)
    scheduleReconnect(sess)
    return
  }
  sess.bot = bot

  // Capture reference to detect stale events after dispose/replace
  const currentBot = bot

  currentBot.on('session', (sessInfo) => {
    if (currentBot !== sess.bot) return
    sessionLog(sess, 'Session attached: ' + (sessInfo && sessInfo.username || 'none'))
  })

  currentBot.on('login', (sessInfo) => {
    if (currentBot !== sess.bot) return
    sessionLog(sess, 'Logged in as: ' + (currentBot.username || sessInfo.username))
  })

  currentBot.once('spawn', () => {
    if (currentBot !== sess.bot) return
    sessionLog(sess, 'Bot spawned!')
    sess.isConnecting = false
    sess.creating = false
    sess.pendingMsaCode = null
    if (sess.config.chatCommand) {
      const chatBot = currentBot
      setTimeout(() => {
        if (chatBot === sess.bot && chatBot?.entity) {
          try { chatBot.chat(sess.config.chatCommand) } catch (e) {}
        }
      }, (sess.config.commandDelaySeconds || 5) * 1000)
    }
  })

  // Chat capture — uses messages mineflayer already receives over the Minecraft
  // connection. No extra packets, no external requests.
  const CHAT_MAP = { 'chat': 'chat', 'system': 'system', 'game_info': 'info' }
  currentBot.on('message', (msg, position, sender, verified) => {
    if (currentBot !== sess.bot) return
    const type = CHAT_MAP[position] || (position === 'whisper' ? 'whisper' : 'info')
    // "Hidden" filters normal public chat locally too (system/whisper/error stay).
    if (type === 'chat' && sess.config.chatMode === 'hidden') return
    let senderName = sender
    if (sender && typeof sender === 'string') {
      try { const s = JSON.parse(sender); senderName = s.name || sender } catch (e) {}
    } else if (sender && typeof sender === 'object') {
      senderName = sender.name || null
    }
    pushChat(sess, type, senderName, String(msg))
  })

  currentBot.on('kicked', (reason, loggedIn) => {
    if (currentBot !== sess.bot) return
    sessionLog(sess, 'Kicked: ' + reason)
    pushChat(sess, 'error', null, 'Kicked: ' + reason)
    sess.pendingMsaCode = null
    scheduleReconnect(sess)
  })

  currentBot.on('end', (reason) => {
    if (currentBot !== sess.bot) return
    sessionLog(sess, 'Ended: ' + reason)
    pushChat(sess, 'info', null, 'Disconnected: ' + reason)
    sess.pendingMsaCode = null
    sess.creating = false
    scheduleReconnect(sess)
  })

  currentBot.on('error', (err) => {
    if (currentBot !== sess.bot) return
    sessionLog(sess, 'Error: ' + err.message)
    pushChat(sess, 'error', null, 'Error: ' + err.message)
    sess.creating = false
    scheduleReconnect(sess)
  })
}

// ============ PROCESS GUARDS ============
process.on('unhandledRejection', (reason) => {
  origLog('[unhandledRejection]', reason)
})
process.on('uncaughtException', (err) => {
  origLog('[uncaughtException]', err && err.stack || err)
})

// ============ STARTUP ============
startBridgeServer()
origLog('Java AFK Bot ready (single bridge on port ' + BRIDGE_PORT + ')')