package com.example.gusa.util

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.example.gusa.R
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.MapStyleOptions

object ThemeHelper {
    private const val PREFS_NAME = "gusa_theme_prefs"
    private const val KEY_THEME = "selected_theme"

    const val THEME_LIGHT = 0
    const val THEME_DARK = 1
    const val THEME_SYSTEM = 2

    fun applyTheme(context: Context, theme: Int) {
        val mode = when (theme) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        saveTheme(context, theme)
    }

    fun applySavedTheme(context: Context) {
        val theme = getSavedTheme(context)
        val mode = when (theme) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun toggleTheme(context: Context) {
        val currentTheme = getSavedTheme(context)
        val newTheme = if (currentTheme == THEME_DARK) THEME_LIGHT else THEME_DARK
        applyTheme(context, newTheme)
    }

    fun getSavedTheme(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_THEME, THEME_SYSTEM)
    }

    private fun saveTheme(context: Context, theme: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_THEME, theme).apply()
    }

    fun applyMapStyle(context: Context, map: GoogleMap) {
        val isDarkMode = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        if (isDarkMode) {
            try {
                val success = map.setMapStyle(
                    MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark)
                )
                if (!success) {
                    Log.e("ThemeHelper", "Style parsing failed.")
                }
            } catch (e: Exception) {
                Log.e("ThemeHelper", "Can't find map_style_dark. Error: ", e)
            }
        } else {
            map.setMapStyle(null)
        }
    }
}
