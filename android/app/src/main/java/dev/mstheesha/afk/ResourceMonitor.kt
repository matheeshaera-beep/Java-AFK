package dev.mstheesha.afk

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.TrafficStats
import java.io.File

// Usage of THIS app's process only (Compose UI + Go engine + native libs).
// CPU and RAM come from /proc/self (the app process), network from this app's
// UID. No device-wide readings, no reliance on /sys/class/kgsl.

data class AppStats(
    val cpuPercent: Float, // % of one core used by this process
    val rssMB: Long,       // resident RAM of this process
    val totalMemMB: Long,  // device RAM (bar scale)
    val netRxBytes: Long,  // cumulative RX of this app's UID
    val netTxBytes: Long,  // cumulative TX of this app's UID
)

object ResourceMonitor {
    private var lastTicks = -1L
    private var lastWallMs = -1L

    fun sample(app: Application): AppStats {
        val uid = app.applicationInfo.uid
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        return AppStats(
            cpuPercent = processCpuPercent(),
            rssMB = rssKB() / 1024,
            totalMemMB = (mem.totalMem / (1024 * 1024)).coerceAtLeast(1),
            netRxBytes = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0),
            netTxBytes = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0),
        )
    }

    // utime+stime delta of /proc/self/stat between samples (100 ticks/sec).
    private fun processCpuPercent(): Float {
        val ticks = try {
            val afterComm = File("/proc/self/stat").readText().substringAfterLast(')')
            val f = afterComm.trim().split(Regex("\\s+"))
            (f.getOrNull(11)?.toLongOrNull() ?: 0L) + (f.getOrNull(12)?.toLongOrNull() ?: 0L)
        } catch (_: Exception) {
            return 0f
        }
        val now = System.currentTimeMillis()
        val pT = lastTicks
        val pW = lastWallMs
        lastTicks = ticks
        lastWallMs = now
        if (pT < 0 || now <= pW) return 0f
        return ((ticks - pT).toFloat() / 100f) / ((now - pW) / 1000f) * 100f
    }

    // VmRSS = real RAM this process occupies right now.
    private fun rssKB(): Long = try {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: 0L
    } catch (_: Exception) {
        0L
    }
}