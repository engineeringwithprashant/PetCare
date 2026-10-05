package com.prash.petcare.ui.common

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

/** Remembers whether the user has chosen light or dark mode, and applies that choice. */
object ThemePreference {

    private const val PREFERENCES_NAME = "petcare_display"
    private const val KEY_NIGHT_MODE = "night_mode"

    /** Applies the stored choice. */
    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(storedMode(context))
    }

    /** Switches to the opposite of whatever is on screen now, and remembers it. */
    fun toggle(context: Context) {
        val next = if (isNightModeActive(context)) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }

        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit { putInt(KEY_NIGHT_MODE, next) }

        // Recreates the current Activity, which is what makes the change visible.
        AppCompatDelegate.setDefaultNightMode(next)
    }

    /** Whether dark mode is what the user is currently looking at. */
    fun isNightModeActive(context: Context): Boolean {
        val nightBits = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightBits == Configuration.UI_MODE_NIGHT_YES
    }

    /** The stored choice, defaulting to following the device. */
    private fun storedMode(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
}
