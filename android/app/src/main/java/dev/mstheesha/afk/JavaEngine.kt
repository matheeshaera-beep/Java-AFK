package dev.mstheesha.afk

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class JavaEngine(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val mutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "http://127.0.0.1:3000"
    private var nodeStarted = false
    private var pollStarted = false
    private var nodeIdle = MutableStateFlow(true)

    // State flows (mirror AfkEngine)
    private val _state = MutableStateFlow("disconnected")
    private val _detail = MutableStateFlow("")
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    private val _authRequired = MutableStateFlow<Pair<String, String>?>(null)
    private val _chat = MutableStateFlow<List<String>>(emptyList())

    val state = _state
    val detail = _detail
    val logs = _logs
    val authRequired = _authRequired
    val chat = _chat

    @Volatile
    var kickedCount = 0
        private set

    private fun pushLog(line: String) {
        val prev = _logs.value
        _logs.value = (prev + line).takeLast(200)
    }

    // JNI native methods
    external fun startNodeWithArguments(args: Array<String>): Int
    external fun initNodeEnvironment(filesDir: String)

    companion object {
        init {
            System.loadLibrary("native-lib")
            System.loadLibrary("node")
        }
    }

    // ============ PUBLIC API ============

    fun start(configJson: String) {
        scope.launch {
            mutex.withLock {
                if (!nodeStarted) {
                    copyNodeProject()
                    startNode()
                    if (waitForBridge()) {
                        pushLog("Bridge ready")
                        if (!pollStarted) {
                            pollStarted = true
                            scope.launch { pollStatus() }
                        }
                    } else {
                        _state.value = "error"
                        pushLog("Bridge not ready after 15s")
                        return@withLock
                    }
                }
                nodeIdle.value = false
                val config = JSONObject(configJson)
                saveConfig(config)
                sendCommand(config.put("type", "start"))
                _state.value = "connecting"
                _detail.value = "Connecting to server…"
            }
        }
    }

    fun stop() {
        scope.launch {
            mutex.withLock {
                if (_state.value == "disconnected") return@withLock
                sendCommand(JSONObject().put("type", "stop"))
                _authRequired.value = null
                nodeIdle.value = true
                _state.value = "disconnected"
                _detail.value = ""
                pushLog("Stopped")
            }
        }
    }

    fun reconnect() {
        scope.launch {
            mutex.withLock {
                if (_state.value == "disconnected") return@withLock
                sendCommand(JSONObject().put("type", "reconnect"))
                _state.value = "connecting"
                _detail.value = "Connecting to server…"
            }
        }
    }

    fun sendChat(message: String) {
        if (message.isBlank()) return
        scope.launch {
            sendCommand(JSONObject().put("type", "chat").put("message", message.trim()))
            val prev = _chat.value
            _chat.value = (prev + "> $message".trim()).takeLast(200)
        }
    }

    fun clearToken() {
        scope.launch {
            val authDir = File(context.filesDir, "minecraft-auth")
            if (authDir.exists()) deleteRecursive(authDir)
            pushLog("Saved login token cleared")
        }
    }

    fun statusJson(): String {
        return try {
            val res = client.newCall(Request.Builder().url("$baseUrl/status").build()).execute()
            res.body?.string() ?: "{}"
        } catch (e: Exception) {
            "{}"
        }
    }

    // ============ INTERNAL ============

    private suspend fun sendCommand(json: JSONObject) {
        try {
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/command")
                .post(body)
                .build()
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) pushLog("Command failed: ${res.code}")
            }
        } catch (e: Exception) {
            pushLog("Command error: ${e.message}")
        }
    }

    private fun copyNodeProject() {
        val targetDir = File(context.filesDir, "nodejs-project")
        if (targetDir.exists()) {
            deleteRecursive(targetDir)
        }
        targetDir.mkdirs()
        copyAssetFolder("nodejs-project", targetDir)
        pushLog("Node project copied to ${targetDir.absolutePath}")
    }

    private fun deleteRecursive(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        file.delete()
    }

    private fun copyAssetFolder(assetPath: String, targetDir: File) {
        val assets = context.assets
        val files = assets.list(assetPath) ?: return
        for (file in files) {
            val src = "$assetPath/$file"
            val dest = File(targetDir, file)
            val subFiles = assets.list(src)
            if (subFiles != null && subFiles.isNotEmpty()) {
                dest.mkdirs()
                copyAssetFolder(src, dest)
            } else {
                assets.open(src).use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun startNode() {
        val mainJs = File(context.filesDir, "nodejs-project/main.js").absolutePath
        val args = arrayOf("node", mainJs)
        Thread({
            try {
                val result = startNodeWithArguments(args)
                nodeStarted = false
                _state.value = "error"
                pushLog("Node.js exited with result: $result")
            } catch (e: Throwable) {
                nodeStarted = false
                _state.value = "error"
                pushLog("Node.js crash: ${e.message}")
            }
        }, "NodeThread").start()
        nodeStarted = true
        pushLog("Node.js starting…")
    }

    private fun waitForBridge(): Boolean {
        var attempts = 0
        while (attempts < 30) {
            try {
                val res = client.newCall(Request.Builder().url("$baseUrl/status").build()).execute()
                if (res.isSuccessful) {
                    return true
                }
            } catch (e: Exception) {}
            Thread.sleep(500)
            attempts++
        }
        return false
    }

    private suspend fun pollStatus() {
        while (nodeStarted) {
            try {
                val res = client.newCall(Request.Builder().url("$baseUrl/status").build()).execute()
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: "{}"
                    val obj = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                    if (nodeIdle.value) {
                        delay(3000)
                        continue
                    }
                    val connected = obj.optBoolean("connected")
                    if (connected) {
                        _state.value = "connected"
                        _detail.value = "Online as ${obj.optString("username")}"
                        if (_authRequired.value != null) _authRequired.value = null
                    } else if (obj.has("msa_code")) {
                        val msa = obj.optJSONObject("msa_code")
                        if (msa != null) {
                            _state.value = "authenticating"
                            val url = msa.optString("verificationUri", "https://www.microsoft.com/link")
                            val code = msa.optString("userCode", "")
                            _detail.value = "Sign in required"
                            pushLog("Microsoft sign-in needed: $url code $code")
                            _authRequired.value = url to code
                            sendCommand(JSONObject().put("type", "auth_done"))
                        }
                    } else if (_state.value == "connecting" || _state.value == "authenticating") {
                        _detail.value = "Connecting to server…"
                    }
                    if (obj.has("health") && obj.has("food")) {
                        pushLog("Health: ${obj.optDouble("health")}/20 Food: ${obj.optDouble("food")}/20")
                    }
                }
            } catch (e: Exception) {}
            delay(3000)
        }
    }

    private fun saveConfig(config: JSONObject) {
        val configFile = File(context.filesDir, "java-bot-config.json")
        FileOutputStream(configFile).use { it.write(config.toString().toByteArray()) }
    }
}
