const mineflayer = require('mineflayer')
const http = require('http')
const fs = require('fs')

// ============ CONFIG ============
const DEFAULT_HOST = '127.0.0.1'
const DEFAULT_PORT = 25565
const DEFAULT_USERNAME = 'AFKBot'
const DEFAULT_AUTH = 'offline'
const BRIDGE_PORT = 3001 // shifted from 3000: old manual install (dev.mstheesha.afk) owns 3000 while running
const MAX_BODY = 1024 * 64 // 64 KB max bridge request body
const MAX_BOTS = 2 // hard limit: max 2 concurrent servers/bots
const MAX_CHAT = 500 // ring buffer of chat messages per server
const MAX_LOGS = 500 // ring buffer of log lines per server
const MAX_SAVE_CHAR = 0 // placeholder to keep fs usage intentional
const BOT_VERSION = '2.1'

// Timing (overridable by env for tests).
const CONNECT_TIMEOUT_MS = parseInt(process.env.AFK_CONNECT_TIMEOUT_MS || '60000', 10)
const STABLE_MS = parseInt(process.env.AFK_STABLE_MS || '60000', 10)
const BACKOFF_BASE_MS = parseInt(process.env.AFK_BACKOFF_BASE_MS || '5000', 10)
const BACKOFF_CAP_MS = 300000 // 5 min

const origLog = console.log

// ============ SESSION STORE ============
// One session entry per Minecraft server. Each owns its own bot, timers,
// log/chat buffers and MS auth state. A single Node process hosts all of them.
//
// phase state machine (per session):
//   idle -> connecting -> online -> waiting -> connecting -> ...
//   - idle:       no bot, no timers (stopped, or auto-reconnect gave up)
//   - connecting: bot object exists, spawn not seen yet (watchdog armed)
//   - online:     spawn seen, bot usable
//   - waiting:    bot down, reconnect timer armed
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
    sessionActive: false,
    phase: 'idle',
    attempt: 0,
    reconnectAt: 0,
    lastError: null,
    connectedSince: 0,
    version: null, // cached negotiated MC version; skips the ping on retry
    connectTimer: null,
    stableTimer: null,
    reconnectTimer: null,
    pendingMsaCode: null,
    logBuffer: [], // entries: {seq, text}; seq from logSeq, monotonic per session
    logSeq: 0,
    chatBuffer: [],
    chatSeq: 0
  }
}

function dayTime() {
  const t = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return p(t.getHours()) + ':' + p(t.getMinutes()) + ':' + p(t.getSeconds())
}

function sessionLog(sess, msg) {
  try {
    const line = dayTime() + ' ' + String(msg)
    sess.logBuffer.push({ seq: ++sess.logSeq, text: line })
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

// mineflayer 4.39 hands `kicked` the raw packet.reason, which is an NBT
// object on 1.20.3+ — String() on it gives "Kicked: [object Object]".
function reasonText(bot, r) {
  try {
    return require('prismarine-chat')(bot.registry).fromNotch(r).toString() || JSON.stringify(r)
  } catch (e) {
    try { return typeof r === 'string' ? r : JSON.stringify(r) } catch (_) { return String(r) }
  }
}

function clearTimer(sess, name) {
  if (sess[name]) { clearTimeout(sess[name]); sess[name] = null }
}

function clearAllTimers(sess) {
  clearTimer(sess, 'connectTimer')
  clearTimer(sess, 'stableTimer')
  clearTimer(sess, 'reconnectTimer')
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

    // GET /debug (dev/diagnostics: timer + handle leaks)
    if (req.method === 'GET' && path === '/debug') {
      let rssMB = 0
      try { rssMB = Math.round(process.memoryUsage().rss / 1048576) } catch (e) {}
      let handles = 0
      try { handles = process._getActiveHandles().length } catch (e) {}
      return json(200, {
        ok: true,
        handles,
        rssMB,
        sessions: [...sessions.values()].map(s => ({ id: s.serverId, phase: s.phase, attempt: s.attempt }))
      })
    }

    // GET /servers
    if (req.method === 'GET' && path === '/servers') {
      const list = []
      for (const sess of sessions.values()) {
        list.push({ serverId: sess.serverId, active: sess.sessionActive, connected: sess.phase === 'online', phase: sess.phase })
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
        clearAllTimers(s)
        s.attempt = 0
        s.reconnectAt = 0
        s.lastError = null
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
      clearAllTimers(sess)
      sess.attempt = 0
      sess.reconnectAt = 0
      sess.phase = 'idle'
      disposeBot(sess)
      return json(200, { ok: true, stopped: true, active: sessions.size })
    }

    if (action === 'reconnect') {
      if (!sess.sessionActive) return json(200, { ok: false })
      sess.attempt = 0
      clearAllTimers(sess)
      sess.reconnectAt = 0
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
              // Echo only after a successful send (no invented seq — the
              // bridge cursor advances here, Kotlin only follows it).
              pushChat(sess, 'out', null, '> ' + msg)
            }
          } catch (e) { sessionLog(sess, 'chat error: ' + e.message) }
        }
        return json(200, { ok: true })
      })
    }

    if (action === 'status') {
      return json(200, statusOf(sess))
    }

    // Merged poll: ONE request returns status + new logs + new chat (the UI
    // polls this instead of 2-3 separate requests; old endpoints still work).
    if (action === 'poll') {
      const logAfter = parseInt(url.searchParams.get('logAfter') || '0', 10)
      const chatAfter = parseInt(url.searchParams.get('chatAfter') || '0', 10)
      return json(200, {
        ok: true,
        status: statusOf(sess),
        logs: sess.logBuffer.filter(e => e.seq > logAfter),
        msgs: sess.chatBuffer.filter(c => c.seq > chatAfter)
      })
    }

    if (action === 'logs') {
      const after = parseInt(url.searchParams.get('after') || '0', 10)
      return json(200, { ok: true, logs: sess.logBuffer.filter(e => e.seq > after) })
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
// Shared shape for /status and the merged /poll (single source of truth).
function statusOf(sess) {
  const status = {
    ok: true,
    active: sess.sessionActive,
    phase: sess.phase,
    connected: sess.phase === 'online',
    loggingIn: sess.phase === 'connecting',
    username: sess.bot?.username,
    health: sess.bot?.health,
    food: sess.bot?.food,
    position: sess.bot?.entity?.position,
    attempt: sess.attempt,
    reconnectInMs: sess.phase === 'waiting' && sess.reconnectAt ? Math.max(0, sess.reconnectAt - Date.now()) : 0,
    lastError: sess.lastError
  }
  if (sess.pendingMsaCode) {
    status.msa_code = {
      userCode: sess.pendingMsaCode.userCode,
      verificationUri: sess.pendingMsaCode.verificationUri,
      message: sess.pendingMsaCode.message
    }
  }
  return status
}
// NOTE: never call removeAllListeners() here — that would remove mineflayer's
// own internal `bot.on('end', cleanup)` (plugins/physics.js) and leak the
// 50 ms physics setInterval plus the whole old bot on every Stop/reconnect.
// Setting sess.bot = null FIRST makes every stale handler a no-op (they all
// check `currentBot !== sess.bot`), then quit() + socket destroy ends it.
function disposeBot(sess) {
  const bot = sess.bot
  sess.bot = null
  if (!bot) return
  try { bot.on('error', () => {}) } catch (e) {}
  try { bot.quit('Session closed') } catch (e) {}
  try { if (bot._client && bot._client.socket) bot._client.socket.destroy() } catch (e) {}
}

// Single funnel for every down path (kicked / end / error / watchdog).
// Idempotent: the first event wins, later ones (e.g. 'end' after 'kicked')
// are ignored via bot.__down + the stale-bot guard.
function handleDown(sess, bot, reason) {
  if (!bot || bot !== sess.bot || bot.__down) return
  bot.__down = true
  const msg = String(reason || 'disconnected')
  sess.lastError = msg
  clearTimer(sess, 'connectTimer')
  clearTimer(sess, 'stableTimer')
  disposeBot(sess)
  scheduleReconnect(sess, msg)
}

function scheduleReconnect(sess, reason) {
  const r = String(reason || '')
  // Permanent failures: never retry (manual /start still works).
  if (/banned|not whitelisted|whitelist/i.test(r)) {
    sessionLog(sess, 'Auto-reconnect stopped: ' + r)
    sess.sessionActive = false
    sess.phase = 'idle'
    sess.reconnectAt = 0
    return
  }
  if (!sess.sessionActive) { sess.phase = 'idle'; return }
  clearTimer(sess, 'reconnectTimer')
  sess.attempt = (sess.attempt || 0) + 1
  let delay = Math.min(BACKOFF_BASE_MS * Math.pow(2, Math.min(sess.attempt - 1, 6)), BACKOFF_CAP_MS)
  delay = delay * (1 + Math.random() * 0.2)
  if (/throttl|too fast|wait before/i.test(r)) delay = Math.max(delay, 30000)
  sess.phase = 'waiting'
  sess.reconnectAt = Date.now() + Math.round(delay)
  const secs = Math.round(delay / 1000)
  sessionLog(sess, 'Reconnecting in ' + secs + 's (attempt ' + sess.attempt + ')')
  // ALWAYS armed while the session is active — there is no flag that can
  // silently swallow the retry (the old isConnecting/creating bug).
  sess.reconnectTimer = setTimeout(() => {
    sess.reconnectTimer = null
    sess.reconnectAt = 0
    createBot(sess)
  }, delay)
}

function createBot(sess) {
  if (!sess.sessionActive) return
  // Always start clean; no early-return flags (the old `creating` guard
  // wedged the session whenever a failure happened before spawn).
  disposeBot(sess)
  clearAllTimers(sess)
  sess.phase = 'connecting'

  const opts = {
    host: sess.config.host,
    port: sess.config.port,
    username: sess.config.username,
    auth: sess.config.auth,
    // Cached version skips the pre-login ping (that ping failure used to
    // emit only 'error' with no 'end', hanging the client pre-spawn).
    version: sess.version || false
  }

  const vd = parseInt(sess.config.viewDistance, 10)
  if (!isNaN(vd) && vd >= 2 && vd <= 12) opts.viewDistance = vd

  // Client chat settings — tells the server how much public chat to send.
  // Safe: same packet mineflayer always writes at login (settings.js).
  opts.chat = sess.config.chatMode === 'commandsOnly' ? 'commandsOnly'
    : sess.config.chatMode === 'hidden' ? 'disabled' : 'enabled'

  // Unused mineflayer plugins are disabled so they never parse packets.
  // Kept: physics, entities, blocks, chat, health, game, inventory,
  // settings, time, resource_pack (+ always-on core: kick, spawn_point...).
  opts.plugins = {
    bed: false, book: false, boss_bar: false, command_block: false,
    craft: false, creative: false, enchantment_table: false, fishing: false,
    furnace: false, rain: false, scoreboard: false, team: false,
    tablist: false, title: false, villager: false, anvil: false,
    sound: false, particle: false
  }

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
      // Pause the watchdog while the user completes the device-code flow.
      clearTimer(sess, 'connectTimer')
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
    const msg = (e && e.message) || String(e)
    sessionLog(sess, 'createBot failed: ' + msg)
    sess.lastError = msg
    scheduleReconnect(sess, msg)
    return
  }
  sess.bot = bot

  // Capture reference to detect stale events after dispose/replace
  const currentBot = bot

  // Connect watchdog: no spawn within the timeout -> treat as down.
  // (Before spawn, some failures emit only 'error' and never 'end'.)
  clearTimer(sess, 'connectTimer')
  sess.connectTimer = setTimeout(() => {
    sess.connectTimer = null
    sessionLog(sess, 'Connect timeout (no spawn in ' + Math.round(CONNECT_TIMEOUT_MS / 1000) + 's)')
    handleDown(sess, currentBot, 'connect timeout')
  }, CONNECT_TIMEOUT_MS)

  currentBot.on('session', (sessInfo) => {
    if (currentBot !== sess.bot) return
    sessionLog(sess, 'Session attached: ' + (sessInfo && sessInfo.username || 'none'))
    // MSA flow finished -> re-arm the watchdog if it was paused.
    if (!sess.connectTimer && sess.phase === 'connecting') {
      sess.connectTimer = setTimeout(() => {
        sess.connectTimer = null
        sessionLog(sess, 'Connect timeout (no spawn in ' + Math.round(CONNECT_TIMEOUT_MS / 1000) + 's)')
        handleDown(sess, currentBot, 'connect timeout')
      }, CONNECT_TIMEOUT_MS)
    }
  })

  currentBot.on('login', (sessInfo) => {
    if (currentBot !== sess.bot) return
    sessionLog(sess, 'Logged in as: ' + (currentBot.username || sessInfo.username))
  })

  currentBot.once('spawn', () => {
    if (currentBot !== sess.bot) return
    sessionLog(sess, 'Bot spawned!')
    sess.phase = 'online'
    sess.connectedSince = Date.now()
    try { if (currentBot.version) sess.version = currentBot.version } catch (e) {}
    sess.pendingMsaCode = null
    clearTimer(sess, 'connectTimer')
    // attempt resets only after STABLE_MS of continuous uptime, NOT at spawn
    // (spawn-then-instant-kick loops must keep backing off).
    clearTimer(sess, 'stableTimer')
    sess.stableTimer = setTimeout(() => {
      sess.stableTimer = null
      if (sess.phase === 'online' && currentBot === sess.bot) {
        sess.attempt = 0
        sessionLog(sess, 'Connection stable, retry counter reset')
      }
    }, STABLE_MS)
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
  const CHAT_MAP = { 'chat': 'chat', 'system': 'system' }
  currentBot.on('message', (msg, position, sender, verified) => {
    if (currentBot !== sess.bot) return
    // Action-bar traffic (position 'game_info', constant on servers like Donut
    // SMP) would flood the buffer and push real chat out — never store it.
    if (position === 'game_info') return
    const type = CHAT_MAP[position] || (position === 'whisper' ? 'whisper' : 'info')
    // "Hidden" filters normal public chat locally too (system/whisper/error stay).
    if (type === 'chat' && sess.config.chatMode === 'hidden') return
    let senderName = null
    if (typeof sender === 'string') {
      if (/^[0-9a-f]{8}-[0-9a-f]{4}-/i.test(sender)) {
        senderName = null // UUID string; the text already contains <Name>
      } else {
        try { const s = JSON.parse(sender); senderName = s.name || sender } catch (e) { senderName = sender }
      }
    } else if (sender && typeof sender === 'object') {
      senderName = sender.name || null
    }
    pushChat(sess, type, senderName, String(msg))
  })

  // mineflayer 4.39 has no explicit `profileless_chat` listener (1.19.3+:
  // /say, /msg, some plugins) — best-effort fallback straight off the
  // protocol client. minecraft-protocol ALSO translates profileless into a
  // 'playerChat' event, which mineflayer usually turns into a 'message' for
  // the same packet — but that translation listener runs AFTER this one, so
  // the dupe check is deferred 300 ms: if anything appended since the packet
  // arrived already contains the content, the fallback stays silent and /say
  // appears exactly once.
  currentBot._client.on('profileless_chat', (pkt) => {
    if (currentBot !== sess.bot) return
    const botAtSend = currentBot
    const baseline = sess.chatBuffer.length
    setTimeout(() => {
      if (botAtSend !== sess.bot) return
      try {
        const CM = require('prismarine-chat')(botAtSend.registry)
        const t = CM.fromNotch(pkt.message).toString()
        const n = pkt.name ? CM.fromNotch(pkt.name).toString() : null
        const line = n ? '<' + n + '> ' + t : t
        const now = Date.now()
        const dup = sess.chatBuffer.slice(baseline).some(e => (now - e.ts) < 2000 && e.text.includes(t))
        if (!dup) pushChat(sess, 'chat', null, line)
      } catch (e) { sessionLog(sess, 'profileless_chat: ' + e.message) }
    }, 300)
  })

  currentBot.on('kicked', (reason, loggedIn) => {
    if (currentBot !== sess.bot) return
    const text = reasonText(currentBot, reason)
    sessionLog(sess, 'Kicked: ' + text)
    pushChat(sess, 'error', null, 'Kicked: ' + text)
    sess.pendingMsaCode = null
    if (/outdated|incompatible/i.test(text)) sess.version = null
    handleDown(sess, currentBot, text)
  })

  currentBot.on('end', (reason) => {
    if (currentBot !== sess.bot) return
    const text = String(reason || 'disconnected')
    sessionLog(sess, 'Ended: ' + text)
    pushChat(sess, 'info', null, 'Disconnected: ' + text)
    sess.pendingMsaCode = null
    handleDown(sess, currentBot, text)
  })

  currentBot.on('error', (err) => {
    if (currentBot !== sess.bot) return
    const msg = (err && err.message) || String(err)
    // After spawn, packet-parse errors are not fatal (and socket errors are
    // always followed by 'end', which handles the retry). Before spawn an
    // auto-version ping failure emits only 'error', so it IS the down path.
    if (sess.phase === 'online') {
      sessionLog(sess, 'Error (ignored, still online): ' + msg)
      return
    }
    sessionLog(sess, 'Error: ' + msg)
    pushChat(sess, 'error', null, 'Error: ' + msg)
    handleDown(sess, currentBot, msg)
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
if (require.main === module) {
  startBridgeServer()
  origLog('Java AFK Bot ready v2.1 (single bridge on port ' + BRIDGE_PORT + ')')
} else {
  module.exports = { reasonText, sessions, newSession, scheduleReconnect, handleDown, createBot, disposeBot }
}
