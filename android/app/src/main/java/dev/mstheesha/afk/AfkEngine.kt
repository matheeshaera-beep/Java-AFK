package dev.mstheesha.afk

import android.os.Handler
import android.os.Looper
import engine.Engine_
import engine.Event
import kotlinx.coroutines.flow.MutableStateFlow

class AfkEngine {
    private val handler = Handler(Looper.getMainLooper())

    private val engine: Engine_ = Engine_().apply {
        setEventCallback(object : Event {
            override fun onStateChanged(state: String, detail: String) {
                handler.post {
                    _state.value = state
                    _detail.value = detail
                }
            }

            override fun onLog(line: String) {
                handler.post { pushLog(line) }
            }

            override fun onAuthRequired(codeUrl: String, userCode: String) {
                handler.post { _authRequired.value = codeUrl to userCode }
            }

            override fun onKicked(reason: String) {
                handler.post {
                    pushLog("Kicked: $reason")
                    kickedCount++
                }
            }

            override fun onChat(author: String, message: String) {
                handler.post {
                    val prev = _chat.value
                    _chat.value = (prev + "$author: $message").takeLast(200)
                }
            }
        })
    }

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

    fun start(configJson: String) = engine.start(configJson)
    fun stop() = engine.stop()
    fun reconnect() = engine.reconnect()
    fun hasSavedToken(): Boolean = false
    fun clearToken() = engine.clearToken()
    fun statusJson(): String = engine.statusJSON()
    fun sendChat(message: String) {
        if (message.isBlank()) return
        engine.sendChat(message.trim())
        _chat.value = (_chat.value + "> $message".trim()).takeLast(200)
    }
}