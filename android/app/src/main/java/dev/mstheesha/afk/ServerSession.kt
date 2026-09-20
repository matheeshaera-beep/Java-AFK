package dev.mstheesha.afk

import android.content.Context
import android.net.TrafficStats
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-server session. Talks to the ONE shared Node runtime (NodeRuntime) over the
 * local HTTP bridge on 127.0.0.1:3001, routed by serverId. Never starts its own
 * Node process — doing so would double-initialize V8 and crash the app.
 */
class ServerSession(private val context: Context, val serverId: Long) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            android.util.Log.w("ServerSession", "unhandled: ${e.message}")
        },
    )
    private val mutex = Mutex()
    private val sessionKey = serverId.toString()

    private val _state = MutableStateFlow("disconnected")
    private val _detail = MutableStateFlow("")
    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    private val _authRequired = MutableStateFlow<Pair<String, String>?>(null)
    private val _chat = MutableStateFlow<List<ChatLine>>(emptyList())

    val state = _state
    val detail = _detail
    val logs = _logs
    val authRequired = _authRequired
    val chat = _chat

    // Session-owned counters: survive UI navigation (unlike composable
    // remember state). Time accrues only while 'connected'; reset only on
    // Stop. No background ticker — refreshCounters() recomputes from the
    // poll loop plus a 1 s UI ticker that exists only while SessionScreen
    // is composed.
    private val _afkSeconds = MutableStateFlow(0L)
    val afkSeconds: StateFlow<Long> = _afkSeconds
    private val _sessionDataBytes = MutableStateFlow(0L)
    val sessionDataBytes: StateFlow<Long> = _sessionDataBytes
    private var dataBaselineBytes = 0L
    private var connectedAtMs = 0L
    private var accumulatedMs = 0L

    /** All state transitions go through here for connected-time accounting. */
    private fun setState(s: String) {
        if (_state.value == s) return
        val now = System.currentTimeMillis()
        if (_state.value == "connected" && connectedAtMs > 0) {
            accumulatedMs += now - connectedAtMs
            connectedAtMs = 0
        }
        _state.value = s
        if (s == "connected" && connectedAtMs == 0L) connectedAtMs = now
        refreshCounters()
    }

    /** Recomputes the visible counters from the accounting above. */
    fun refreshCounters() {
        val now = System.currentTimeMillis()
        val extra =
            if (_state.value == "connected" && connectedAtMs > 0) now - connectedAtMs else 0
        _afkSeconds.value = (accumulatedMs + extra) / 1000
        _sessionDataBytes.value =
            (uidBytes() - dataBaselineBytes).coerceAtLeast(0L)
    }

    private fun uidBytes(): Long {
        val uid = context.applicationInfo.uid
        return TrafficStats.getUidRxBytes(uid).coerceAtLeast(0) +
            TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
    }

    @Volatile
    var kickedCount = 0
        private set

    private var polling = AtomicBoolean(false)
    private var lastChatSeq = 0

    // Stashed from the start() config for the "how it connected" summary.
    private var lastAuthLabel = ""
    private var lastServerRef = ""

    /** Gate for chat polling — the UI enables this only while the chat tab is open. */
    fun setChatPolling(enabled: Boolean) {
        chatPollingEnabled.set(enabled)
    }

    private val chatPollingEnabled = AtomicBoolean(false)

    data class ChatLine(
        val seq: Int,
        val ts: Long,
        val type: String,
        val sender: String?,
        val text: String,
    )

    /** Stable unique id per line so LazyColumn can key items (no takeLast
     *  window in the UI, no jump on updates). Bridge lines use the bridge
     *  seq; local-only lines use negative ids from [nextLocalLogId]. */
    data class LogLine(val id: Long, val text: String)

    private var nextLocalLogId = -1L

    private fun pushLog(line: String) {
        val prev = _logs.value
        _logs.value = (prev + LogLine(nextLocalLogId--, line)).takeLast(500)
    }

    private fun pushBridgeLog(seq: Long, line: String) {
        val prev = _logs.value
        _logs.value = (prev + LogLine(seq, line)).takeLast(500)
        if (seq > lastLogSeq) lastLogSeq = seq
    }

    private fun pushChat(seq: Int, ts: Long, type: String, sender: String?, text: String) {
        val prev = _chat.value
        _chat.value = (prev + ChatLine(seq, ts, type, sender, text)).takeLast(500)
        if (seq > lastChatSeq) lastChatSeq = seq
    }

    // ============ PUBLIC API ============

    fun start(configJson: String) {
        scope.launch {
            mutex.withLock {
                val config = JSONObject(configJson)
                lastAuthLabel =
                    if (config.optString("auth") == "microsoft") "Microsoft account" else "Offline mode"
                lastServerRef =
                    config.optString("host") + ":" + config.optInt("port", 25565)
                // Live stage 1: visible immediately, before the bridge poll.
                setState("connecting")
                _detail.value = "Starting local bridge…"
                // Baseline for this session's data-usage counter.
                dataBaselineBytes = uidBytes()
                _sessionDataBytes.value = 0
                // Fresh run: time restarts here (and on Stop). Drops and
                // reconnects in between keep accumulating instead.
                accumulatedMs = 0
                connectedAtMs = 0
                _afkSeconds.value = 0
                val blocked = { msg: String ->
                    setState("error")
                    _detail.value = msg
                    pushLog("Node assets blocked: $msg")
                }
                if (!NodeRuntime.ensureStarted(blocked)) {
                    pushLog("Bridge not ready; cannot start")
                    return@withLock
                }
                config.put("serverId", sessionKey)
                val res = post("start", config)
                if (res != null) {
                    if (res.optInt("status", 0) == 409) {
                        setState("error")
                        _detail.value = res.optString("error", "Maximum ${NodeRuntime.MAX_BOTS} servers can run at the same time.")
                        pushLog(_detail.value)
                        return@withLock
                    }
                } else {
                    pushLog("Start command not acknowledged")
                }
                setState("connecting")
                _detail.value = "Connecting to server…"
                startPolling()
            }
        }
    }

    fun stop() {
        scope.launch {
            mutex.withLock {
                polling.set(false)
                // Always tell the bridge, even if we already look
                // disconnected (a wedged pre-spawn attempt still retries
                // server-side until it hears stop).
                post("stop", JSONObject(), quiet = true)
                _authRequired.value = null
                setState("disconnected")
                _detail.value = ""
                accumulatedMs = 0
                connectedAtMs = 0
                _afkSeconds.value = 0
                _sessionDataBytes.value = 0
                pushLog("Stopped")
            }
        }
    }

    fun reconnect() {
        scope.launch {
            mutex.withLock {
                if (_state.value == "disconnected") {
                    if (!NodeRuntime.ensureStarted()) { pushLog("Bridge not ready"); return@withLock }
                }
                post("reconnect", JSONObject())
                setState("connecting")
                _detail.value = "Connecting to server…"
                startPolling()
            }
        }
    }

    fun sendChat(message: String) {
        if (message.isBlank()) return
        scope.launch {
            // No local echo, no invented seq: the bridge appends an 'out'
            // line after a successful send and the cursor follows bridge
            // seqs only — echoing locally used to skip server messages.
            post("chat", JSONObject().put("message", message.trim()))
            try {
                pullChat(true)
            } catch (e: Exception) {
                android.util.Log.w("ServerSession", "sendChat pull failed: ${e.message}")
            }
        }
    }

    /** Pushes live settings to a running bot (chat visibility, command,
     *  delays, view distance). Without this, edits made while connected sit
     *  in the database until the next Stop/Start. Quiet: a down bridge just
     *  means the next Start carries the full config anyway. */
    fun pushLiveConfig(chatCommand: String, delaySeconds: Int, viewDistance: Int, chatMode: String) {
        scope.launch {
            post(
                "config",
                JSONObject()
                    .put("chatCommand", chatCommand)
                    .put("commandDelaySeconds", delaySeconds)
                    .put("viewDistance", viewDistance)
                    .put("chatMode", chatMode),
                quiet = true,
            )
        }
    }

    fun clearToken() {        scope.launch {
            val authDir = File(context.filesDir, "minecraft-auth")
            if (authDir.exists()) authDir.deleteRecursively()
            val tokenFile = File(context.filesDir, "ms_token.json")
            if (tokenFile.exists()) tokenFile.delete()
            pushLog("Saved login token cleared")
        }
    }

    fun dispose() {
        scope.launch {
            mutex.withLock {
                polling.set(false)
                post("stop", JSONObject(), quiet = true)
                setState("disconnected")
                _detail.value = ""
                accumulatedMs = 0
                connectedAtMs = 0
                _afkSeconds.value = 0
                _sessionDataBytes.value = 0
            }
        }
    }

    // ============ INTERNAL ============

    private fun post(action: String, json: JSONObject, quiet: Boolean = false): JSONObject? {
        return try {
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${NodeRuntime.baseUrl}/servers/$sessionKey/$action")
                .post(body)
                .build()
            NodeRuntime.http.newCall(request).execute().use { res ->
                val b = res.body?.string() ?: "{}"
                val obj = try { JSONObject(b) } catch (e: Exception) { JSONObject() }
                obj.put("status", res.code)
                if (!res.isSuccessful && !quiet) pushLog("Command failed: ${res.code} ${obj.optString("error")}")
                obj
            }
        } catch (e: Exception) {
            if (!quiet) pushLog("Command error: ${e.message}")
            null
        }
    }

    private fun get(path: String): JSONObject? {
        return try {
            val request = Request.Builder()
                .url("${NodeRuntime.baseUrl}/servers/$sessionKey/$path")
                .build()
            NodeRuntime.http.newCall(request).execute().use { res ->
                val b = res.body?.string() ?: "{}"
                try { JSONObject(b) } catch (e: Exception) { JSONObject() }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun startPolling() {
        if (!polling.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (polling.get()) {
                    pollMerged()
                    if (_state.value == "disconnected") {
                        polling.set(false)
                    } else {
                        // Fast while the UI is visible, slow and
                        // battery-friendly when the screen is off.
                        delay(if (AppGraph.uiVisible) 3000 else 20000)
                    }
                }
            } finally {
                polling.set(false)
            }
        }
    }

    // Bridge log cursor: the bridge numbers every entry (logSeq, monotonic
    // per session) and /logs?after= returns only newer ones — one small
    // incremental fetch instead of the full 500-line buffer every 3 s.
    private var lastLogSeq = 0L

    // Last hearts/hunger values pushed to the log (health lines are logged
    // only on change, or every 5 min as a heartbeat).
    private var lastHealthLogAt = 0L
    private var lastLoggedHearts = Double.NaN
    private var lastLoggedFood = -1

    /** ONE bridge request per cycle: status + new logs + new chat (replaces
     *  the old 2-request status+logs loop plus the tab-gated chat pull). */
    private fun pollMerged() {
        val obj = get("poll?logAfter=$lastLogSeq&chatAfter=$lastChatSeq") ?: return
        obj.optJSONObject("status")?.let { handleStatus(it) }
        val logs = obj.optJSONArray("logs")
        if (logs != null) {
            for (i in 0 until logs.length()) {
                val e = logs.optJSONObject(i) ?: continue
                pushBridgeLog(e.optLong("seq"), e.optString("text"))
            }
        }
        val msgs = obj.optJSONArray("msgs")
        if (msgs != null) {
            for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                pushChat(
                    m.optInt("seq"),
                    m.optLong("ts"),
                    m.optString("type", "chat"),
                    m.optString("sender").ifBlank { null },
                    m.optString("text"),
                )
            }
        }
        refreshCounters()
    }

    private fun handleStatus(status: JSONObject) {
        if (status.optBoolean("active", false)) {
            when {
                status.optString("phase") == "waiting" -> {
                    // Online -> waiting means a kick/drop happened since the
                    // last poll: count it so the UI snackbar fires.
                    if (_state.value == "connected") kickedCount++
                    setState("reconnecting")
                    val secs = (status.optLong("reconnectInMs", 0) + 999) / 1000
                    val err = status.optString("lastError").ifBlank { "disconnected" }
                    _detail.value =
                        "Reconnecting in ${secs}s (attempt ${status.optInt("attempt", 0)}) — $err"
                }
                status.optBoolean("connected") -> {
                    setState("connected")
                    _detail.value = buildString {
                        append("Online as ${status.optString("username")}")
                        if (lastAuthLabel.isNotBlank()) append(" · $lastAuthLabel")
                        if (lastServerRef.isNotBlank()) append(" · $lastServerRef")
                    }
                    if (_authRequired.value != null) _authRequired.value = null
                    // Hearts + hunger: log only when the values change, or
                    // every 5 min as a heartbeat (the old 10 s line filled
                    // the buffer and pushed out kicks/reconnects).
                    val now = System.currentTimeMillis()
                    val hp = status.optDouble("health", -1.0)
                    val food = status.optInt("food", -1)
                    if (hp >= 0 && food >= 0) {
                        val hearts = hp / 2.0
                        if (hearts != lastLoggedHearts || food != lastLoggedFood ||
                            now - lastHealthLogAt > 300_000
                        ) {
                            lastHealthLogAt = now
                            lastLoggedHearts = hearts
                            lastLoggedFood = food
                            val heartsStr =
                                if (hearts % 1.0 == 0.0) hearts.toInt().toString()
                                else "%.1f".format(hearts)
                            val cal = java.util.Calendar.getInstance()
                            val clock = "%02d:%02d:%02d".format(
                                cal.get(java.util.Calendar.HOUR_OF_DAY),
                                cal.get(java.util.Calendar.MINUTE),
                                cal.get(java.util.Calendar.SECOND),
                            )
                            pushLog("$clock Hearts $heartsStr/10 · Hunger $food/20")
                        }
                    }
                }
                status.has("msa_code") -> {
                    val msa = status.optJSONObject("msa_code")
                    if (msa != null) {
                        setState("authenticating")
                        val url = msa.optString("verificationUri", "https://www.microsoft.com/link")
                        val code = msa.optString("userCode", "")
                        _detail.value = "Sign in required"
                        pushLog("Microsoft sign-in needed: $url code $code")
                        _authRequired.value = url to code
                        post("auth_done", JSONObject())
                    }
                }
                status.optBoolean("loggingIn") -> {
                    setState("connecting")
                    _detail.value = "Logging in…"
                }
                _state.value == "connecting" || _state.value == "authenticating" -> {
                    _detail.value = "Connecting to server…"
                }
            }
        } else {
            setState("disconnected")
            _detail.value = ""
        }
    }

    /** Fetch any missed chat once (called when the chat tab opens). */
    fun refreshChatNow() {
        // Blocking network call — must run off the main thread (it used to
        // throw NetworkOnMainThreadException from a LaunchedEffect, silently
        // swallowed by an empty catch).
        scope.launch { pullChat(true) }
    }

    private fun pullChat(connected: Boolean) {
        if (!connected) return
        try {
            val request = Request.Builder()
                .url("${NodeRuntime.baseUrl}/servers/$sessionKey/chat?after=$lastChatSeq")
                .build()
            NodeRuntime.http.newCall(request).execute().use { res ->
                val b = res.body?.string() ?: "{}"
                val obj = try { JSONObject(b) } catch (e: Exception) { JSONObject() }
                val arr = obj.optJSONArray("msgs") ?: return
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    pushChat(
                        m.optInt("seq"),
                        m.optLong("ts"),
                        m.optString("type", "chat"),
                        m.optString("sender").ifBlank { null },
                        m.optString("text"),
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ServerSession", "pullChat failed: ${e.message}")
        }
    }
}