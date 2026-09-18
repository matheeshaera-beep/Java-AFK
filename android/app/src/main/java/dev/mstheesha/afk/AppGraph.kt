package dev.mstheesha.afk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

object AppGraph {
    private val sessions = mutableMapOf<Long, ServerSession>()
    private val sessionJobs = mutableMapOf<Long, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeCount = MutableStateFlow(0)
    val activeCount: Flow<Int> get() = _activeCount

    // Display names for sessions, keyed by server id. The notification reads
    // these to show which server(s) are actually running.
    private val serverNames = mutableMapOf<Long, String>()
    private val _namesTick = MutableStateFlow(0)
    val namesTick: Flow<Int> get() = _namesTick

    /** Remembered from UI surfaces that know the entity (list rows, session). */
    fun noteServerName(id: Long, name: String) = synchronized(sessions) {
        if (serverNames[id] != name) {
            serverNames[id] = name
            _namesTick.value++
        }
    }

    /** Names of sessions whose state is anything but disconnected. */
    fun activeServerNames(): List<String> = synchronized(sessions) {
        sessions.filter { (_, s) -> s.state.value != "disconnected" }
            .map { (id, _) -> serverNames[id] ?: "Server $id" }
    }

    lateinit var db: AfkDatabase
        private set

    fun init(context: android.content.Context) {
        if (!::db.isInitialized) db = AfkDatabase.get(context)
        NodeRuntime.init(context)
    }

    /** Returns the shared ServerSession for a server (never starts a Node per server). */
    fun sessionFor(serverId: Long): ServerSession = synchronized(sessions) {
        sessions.getOrPut(serverId) {
            val session = ServerSession(context(), serverId)
            sessionJobs[serverId] = scope.launch(Dispatchers.Default) {
                session.state.collect { recomputeActiveCount() }
            }
            session
        }
    }
    fun removeSession(serverId: Long) {
        val session = synchronized(sessions) {
            sessions.remove(serverId)?.also { sessionJobs.remove(serverId)?.cancel() }
        }
        session?.dispose()
        recomputeActiveCount()
    }

    /** Disconnects every session (each posts stop to the bridge and goes
     *  disconnected). Used by the notification's force-stop action so no
     *  session is left showing a stale Connected state. */
    fun stopAllSessions() {
        val all = synchronized(sessions) { sessions.values.toList() }
        all.forEach { it.stop() }
    }

    /**
     * A session starts being "active" when it leaves 'disconnected' (a start or
     * reconnect was requested) and returns to idle once stopped/disconnected.
     */
    private fun recomputeActiveCount() {
        val n = synchronized(sessions) { sessions.values.count { it.state.value != "disconnected" } }
        _activeCount.value = n
        // State transitions also change the notification's server-name list.
        _namesTick.value++
    }

    private fun context(): android.content.Context =
        appContext ?: throw IllegalStateException("AppGraph not initialized with a context")

    private var appContext: android.content.Context? = null
        private set

    fun setContext(ctx: android.content.Context) {
        appContext = ctx.applicationContext
    }
}