package io.github.jerome3o.starchart

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "starchart"

    const val KEY_DAILY_REMINDER = "daily_reminder"
    const val KEY_TRACKING_ENABLED = "tracking_enabled"
    const val KEY_MAP_STYLE = "map_style"
    const val KEY_LAST_TAB = "last_tab"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
