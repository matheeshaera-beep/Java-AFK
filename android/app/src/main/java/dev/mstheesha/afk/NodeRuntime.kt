package dev.mstheesha.afk

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Singleton owner of the embedded Node.js runtime (mineflayer bridge).
 *
 * Node's native side uses std::call_once + a compare-and-swap flag so that
 * node::Start() runs at most once per Android process; starting a second V8 in
 * one process segfaults. All Minecraft bots are multiplexed inside the single
 * shared Node instance (main.js exposes an HTTP bridge on 127.0.0.1:3001).
 */
object NodeRuntime {

    private lateinit var appContext: Context

    /** Called from AppGraph with the Application context — must run before
     *  anything touches [projectDir]. Idempotent. */
    fun init(context: Context) {
        appContext = context.applicationContext
        System.loadLibrary("native-lib")
    }

    val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    const val baseUrl = "http://127.0.0.1:3001" // shifted from 3000: old manual install owns 3000 while running
    const val MAX_BOTS = 5

    // JNI native methods
    external fun startNodeWithArguments(args: Array<String>): Int
    external fun isNodeStarted(): Boolean
    external fun initNodeEnvironment(filesDir: String)

    /**
     * Dedicated single-thread executor that OWNS the embedded Node bridge for
     * the whole process. Every start/stop/reconnect of the bridge runs on this
     * thread and NEVER on the Android main/RenderThread, so the native mutex
     * V8/Node keeps internally is created+locked+unlocked+destroyed by exactly
     * one owner thread. That removes the SIGABRT ("pthread_mutex_lock called on
     * a destroyed mutex") which used to fire on the RenderThread when the bridge
     * thread raced the UI while disposing a previous runtime.
     */
    private val bridgeThread: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AfkBridgeOwner").apply { isDaemon = true }
    }

    /** Node project directory in internal storage (shared by all sessions). */
    val projectDir: File get() = File(appContext.filesDir, "nodejs-project")

    fun bridgeUp(): Boolean = try {
        val res = http.newCall(
            Request.Builder().url("$baseUrl/status").build()
        ).execute()
        res.use { it.isSuccessful && (res.body?.string()?.contains("\"ready\":true") ?: false) }
    } catch (e: Exception) {
        false
    }

    /**
     * Starts the embedded Node bridge exactly once per process. Every
     * start/stop/reconnect runs on the single [bridgeThread] owner so the native
     * runtime is never disposed while another thread (e.g. RenderThread) is still
     * inside one of its mutex-protected sections.
     */
    fun ensureStarted(blocked: (String) -> Unit = {}): Boolean = try {
        if (bridgeUp()) return true
        bridgeThread.execute {
            try {
                // Staged on the single owner thread: copy assets first (fresh
                // installs have no files dir yet — without main.js Node dies
                // during startup), then env, then start. Serialized by the
                // single-thread executor; stamp check makes repeats cheap.
                copyNodeProjectOnce()
                initNodeEnvironment(appContext.filesDir.absolutePath)
                startNodeWithArguments(arrayOf("node", File(projectDir, "main.js").absolutePath))
            } catch (e: Throwable) {
                android.util.Log.e("NodeRuntime", "Node fatal: ${e.message}", e)
            }
        }
        var attempts = 0
        while (attempts < 30) {
            if (bridgeUp()) return true
            Thread.sleep(500)
            attempts++
        }
        blocked("Bridge did not become ready in 15s (check node logs)")
        false
    } catch (e: Throwable) {
        android.util.Log.e("NodeRuntime", "ensureStarted crash: ${e.message}", e)
        false
    }

    private fun copyNodeProjectOnce() {
        val stampFile = File(projectDir, ".app_asset_version")
        val currentStamp = BuildConfig.VERSION_NAME
        val needsFullCopy = !projectDir.exists() ||
            !stampFile.exists() ||
            stampFile.readTextOrNull() != currentStamp
        if (needsFullCopy) {
            projectDir.deleteRecursively()
            projectDir.mkdirs()
            copyAssetFolder("nodejs-project", projectDir)
            stampFile.writeText(currentStamp)
            return
        }
        // DEPLOY TRAP: the stamp only proves node_modules is fresh. The entry
        // files MUST be refreshed on every start, or a version-bumped main.js
        // is shadowed by the stale copy in internal storage forever.
        copyAssetFile("nodejs-project/main.js", File(projectDir, "main.js"))
        copyAssetFile("nodejs-project/package.json", File(projectDir, "package.json"))
    }

    private fun copyAssetFile(assetPath: String, dest: File) {
        try {
            appContext.assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NodeRuntime", "copy $assetPath failed: ${e.message}")
        }
    }

    private fun copyAssetFolder(assetPath: String, targetDir: File) {
        val assets = appContext.assets
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

    private fun File.readTextOrNull(): String? = try { readText() } catch (e: Exception) { null }
}
