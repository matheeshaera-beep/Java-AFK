package dev.mstheesha.afk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

object AppGraph {
    private val engines = mutableMapOf<Long, JavaEngine>()

    lateinit var db: AfkDatabase
        private set

    fun init(context: android.content.Context) {
        if (!::db.isInitialized) db = AfkDatabase.get(context)
    }

    fun engineFor(serverId: Long): JavaEngine = synchronized(engines) {
        engines.getOrPut(serverId) { context?.let { JavaEngine(it) } ?: throw IllegalStateException("Context not set") }
    }

    fun removeEngine(serverId: Long) {
        val engine = synchronized(engines) { engines.remove(serverId) }
        engine?.dispose()
    }

    fun runningEngine(): Pair<Long, JavaEngine>? = synchronized(engines) {
        engines.entries.firstOrNull { it.value.state.value != "disconnected" }
            ?.let { it.key to it.value }
    }

    /** Emits the id of the currently-running engine whenever any engine state changes. */
    fun runningEngineIdFlow(): Flow<Long?> = synchronized(engines) {
        if (engines.isEmpty()) {
            kotlinx.coroutines.flow.flowOf(null)
        } else {
            val flows: List<kotlinx.coroutines.flow.Flow<Pair<Long, String>>> =
                engines.entries.map { (id, engine) -> engine.state.map { id to it } }
            combine(flows) { states: Array<Pair<Long, String>> ->
                states.firstOrNull { (_, state) -> state != "disconnected" }?.first
            }
        }
    }

    private var context: android.content.Context? = null
        private set

    fun setContext(ctx: android.content.Context) {
        context = ctx
    }
}