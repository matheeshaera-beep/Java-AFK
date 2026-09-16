package dev.mstheesha.afk

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Prefs {
    private const val FILE = "afk_prefs"
    private const val KEY_DARK = "dark_mode"
    private const val KEY_NET_DAY = "net_day"
    private const val KEY_NET_DAILY = "net_daily"
    private const val KEY_NET_LAST_TOTAL = "net_last_total"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isDark(ctx: Context, systemDark: Boolean): Boolean =
        prefs(ctx).getBoolean(KEY_DARK, systemDark)

    fun setDark(ctx: Context, dark: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DARK, dark).apply()
    }

    fun dailyNetBytes(ctx: Context, currentTotal: Long): Long {
        val p = prefs(ctx)
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val day = p.getString(KEY_NET_DAY, today)!!
        if (day != today) {
            p.edit().putString(KEY_NET_DAY, today).putLong(KEY_NET_DAILY, 0).putLong(KEY_NET_LAST_TOTAL, currentTotal).apply()
            return 0L
        }
        val accumulated = p.getLong(KEY_NET_DAILY, 0L)
        val lastTotal = p.getLong(KEY_NET_LAST_TOTAL, -1L)
        return if (lastTotal >= 0L && currentTotal >= lastTotal) {
            val delta = currentTotal - lastTotal
            val added = accumulated + delta
            p.edit().putLong(KEY_NET_DAILY, added).putLong(KEY_NET_LAST_TOTAL, currentTotal).apply()
            added
        } else {
            p.edit().putLong(KEY_NET_LAST_TOTAL, currentTotal).apply()
            accumulated
        }
    }
}