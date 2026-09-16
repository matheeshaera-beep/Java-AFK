package dev.mstheesha.afk

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

    fun runningEngine(): Pair<Long, JavaEngine>? = synchronized(engines) {
        engines.entries.firstOrNull { it.value.state.value != "disconnected" }
            ?.let { it.key to it.value }
    }

    private var context: android.content.Context? = null
        private set

    fun setContext(ctx: android.content.Context) {
        context = ctx
    }
}