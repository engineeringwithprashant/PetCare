package com.prash.petcare.data.local

import android.content.Context
import androidx.core.content.edit

/** The last temperature the app successfully fetched, and when it fetched it. */
data class CachedWeather(
    val temperatureCelsius: Double,
    val fetchedAtMillis: Long
)

/** Stores that last reading across app launches. */
class WeatherCache(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** The stored reading, or null when nothing has ever been fetched on this device. */
    fun read(): CachedWeather? {
        if (!preferences.contains(KEY_TEMPERATURE) || !preferences.contains(KEY_FETCHED_AT)) {
            return null
        }
        return CachedWeather(
            // Stored as a Float because SharedPreferences has no putDouble.
            temperatureCelsius = preferences.getFloat(KEY_TEMPERATURE, 0f).toDouble(),
            fetchedAtMillis = preferences.getLong(KEY_FETCHED_AT, 0L)
        )
    }

    /** Replaces the stored reading. */
    fun write(temperatureCelsius: Double, fetchedAtMillis: Long) {
        preferences.edit {
            putFloat(KEY_TEMPERATURE, temperatureCelsius.toFloat())
            putLong(KEY_FETCHED_AT, fetchedAtMillis)
        }
    }

    private companion object {
        /** Its own file rather than sharing petcare_display: unrelated settings, unrelated lifetimes. */
        const val PREFERENCES_NAME = "petcare_weather"
        const val KEY_TEMPERATURE = "temperature_celsius"
        const val KEY_FETCHED_AT = "fetched_at"
    }
}
