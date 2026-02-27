package com.androsnd

import android.content.Context
import android.content.SharedPreferences

object PreferencesManager {
    const val PREFS_NAME = "androsnd_prefs"

    fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
