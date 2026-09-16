package dev.mstheesha.afk

import android.content.Context

object Prefs {
    private const val FILE = "afk_prefs"
    private const val KEY_DARK = "dark_mode"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isDark(ctx: Context, systemDark: Boolean): Boolean =
        prefs(ctx).getBoolean(KEY_DARK, systemDark)

    fun setDark(ctx: Context, dark: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DARK, dark).apply()
    }
}
