package dev.mstheesha.afk

import android.content.Context
import android.net.TrafficStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private val scope = CoroutineScope(Dispatchers.IO)
    private val mutex = Mutex()
    private val sessionKey = serverId.toString()

    private val _state = MutableStateFlow("disconnected")
    private val _detail = MutableStateFlow("")
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    private val _authRequired = MutableStateFlow<Pair<String, String>?>(null)
    private val _chat = MutableStateFlow<List<ChatLine>>(emptyList())

    val state = _state
    val detail = _detail
    val logs = _logs
    val authRequired = _authRequired
    val chat = _chat

    // Session-owned counters: survive UI navigation (unlike composable
    // remember state). Tick while connected; zeroed on stop/dispose.
    private val _afkSeconds = MutableStateFlow(0L)
    val afkSeconds: StateFlow<Long> = _afkSeconds
    private val _sessionDataBytes = MutableStateFlow(0L)
    val sessionDataBytes: StateFlow<Long> = _sessionDataBytes
    private var dataBaselineBytes = 0L

    init {
        scope.launch {
            while (true) {
                delay(1000)
                if (_state.value == "connected") {
                    _afkSeconds.value++
                    _sessionDataBytes.value =
                        (uidBytes() - dataBaselineBytes).coerceAtLeast(0L)
                }
            }
        }
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

    private fun pushLog(line: String) {
        val prev = _logs.value
        _logs.value = (prev + line).takeLast(200)
    }

    private fun pushChat(seq: Int, ts: Long, type: String, sender: String?, text: String) {
        val prev = _chat.value
        _chat.value = (prev + ChatLine(seq, ts, type, sender, text)).takeLast(300)
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
                _state.value = "connecting"
                _detail.value = "Starting local bridge…"
                // Baseline for this session's data-usage counter.
                dataBaselineBytes = uidBytes()
                _sessionDataBytes.value = 0
                val blocked = { msg: String ->
                    _state.value = "error"
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
                        _state.value = "error"
                        _detail.value = res.optString("error", "Maximum 2 servers can run at the same time.")
                        pushLog(_detail.value)
                        return@withLock
                    }
                } else {
                    pushLog("Start command not acknowledged")
                }
                _state.value = "connecting"
                _detail.value = "Connecting to server…"
                startPolling()
            }
        }
    }

    fun stop() {
        scope.launch {
            mutex.withLock {
                if (_state.value == "disconnected") return@withLock
                post("stop", JSONObject())
                _authRequired.value = null
                _state.value = "disconnected"
                _detail.value = ""
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
                _state.value = "connecting"
                _detail.value = "Connecting to server…"
            }
        }
    }

    fun sendChat(message: String) {
        if (message.isBlank()) return
        scope.launch {
            post("chat", JSONObject().put("message", message.trim()))
            val prev = _chat.value
            _chat.value = (prev + ChatLine(++lastChatSeq, System.currentTimeMillis(), "chat", null, "> $message".trim())).takeLast(300)
        }
    }

    fun clearToken() {
        scope.launch {
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
                if (_state.value != "disconnected") {
                    post("stop", JSONObject())
                }
                _state.value = "disconnected"
                _detail.value = ""
                _afkSeconds.value = 0
                _sessionDataBytes.value = 0
            }
        }
    }

    // ============ INTERNAL ============

    private fun post(action: String, json: JSONObject): JSONObject? {
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
                if (!res.isSuccessful) pushLog("Command failed: ${res.code} ${obj.optString("error")}")
                obj
            }
        } catch (e: Exception) {
            pushLog("Command error: ${e.message}")
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
            while (polling.get()) {
                pollOnce()
                pullLogs()
                delay(3000)
            }
        }
    }

    // Last bridge log line already shown. The bridge keeps a 200-line ring;
    // polls run every 3s so rotation between polls is impossible in practice.
    private var lastSeenLog: String? = null

    // Last time a hearts/hunger line was pushed (10s cadence, connected only).
    private var lastHealthLogAt = 0L

    /** Merges new lines from the bridge's per-session log buffer into the
     *  visible log. Without this the panel only ever showed local lines
     *  (e.g. "Stopped") while the bridge logged everything. */
    private fun pullLogs() {
        val obj = get("logs") ?: return
        val arr = obj.optJSONArray("logs") ?: return
        val fresh = mutableListOf<String>()
        for (i in 0 until arr.length()) fresh.add(arr.optString(i))
        if (fresh.isEmpty()) return
        val last = lastSeenLog
        val unseen = if (last == null) {
            fresh
        } else {
            val idx = fresh.indexOf(last)
            if (idx < 0) fresh else fresh.subList(idx + 1, fresh.size)
        }
        unseen.forEach { pushLog(it) }
        lastSeenLog = fresh.last()
    }

    private suspend fun pollOnce() {
        val status = get("status") ?: return
        if (status.optBoolean("active", false)) {
            when {
                status.optBoolean("connected") -> {
                    _state.value = "connected"
                    _detail.value = buildString {
                        append("Online as ${status.optString("username")}")
                        if (lastAuthLabel.isNotBlank()) append(" · $lastAuthLabel")
                        if (lastServerRef.isNotBlank()) append(" · $lastServerRef")
                    }
                    if (_authRequired.value != null) _authRequired.value = null
                    // Hearts + hunger in the log every 10s while connected.
                    val now = System.currentTimeMillis()
                    if (now - lastHealthLogAt > 10_000) {
                        lastHealthLogAt = now
                        val hp = status.optDouble("health", -1.0)
                        val food = status.optInt("food", -1)
                        if (hp >= 0 && food >= 0) {
                            val hearts = hp / 2.0
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
                        _state.value = "authenticating"
                        val url = msa.optString("verificationUri", "https://www.microsoft.com/link")
                        val code = msa.optString("userCode", "")
                        _detail.value = "Sign in required"
                        pushLog("Microsoft sign-in needed: $url code $code")
                        _authRequired.value = url to code
                        post("auth_done", JSONObject())
                    }
                }
                status.optBoolean("loggingIn") -> {
                    _state.value = "connecting"
                    _detail.value = "Logging in…"
                }
                _state.value == "connecting" || _state.value == "authenticating" -> {
                    _detail.value = "Connecting to server…"
                }
            }
        } else {
            _state.value = "disconnected"
            _detail.value = ""
        }
        // Chat is pulled only while the chat tab is open (chatPollingEnabled).
        if (chatPollingEnabled.get()) pullChat(status.optBoolean("connected"))
    }

    /** Fetch any missed chat once (called when the chat tab opens). */
    fun refreshChatNow() {
        pullChat(true)
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
        } catch (e: Exception) {}
    }
}