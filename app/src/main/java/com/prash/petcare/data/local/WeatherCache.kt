package com.prash.petcare.data.local

import android.content.Context
import androidx.core.content.edit

/**
 * The last temperature the app successfully fetched, and when it fetched it.
 *
 * @property temperatureCelsius The reading itself.
 * @property fetchedAtMillis When it was read from the network, as a wall-clock timestamp — what
 *   [com.prash.petcare.data.repository.WeatherRepository] measures its half-hour freshness window
 *   against.
 */
data class CachedWeather(
    val temperatureCelsius: Double,
    val fetchedAtMillis: Long
)

/**
 * Stores that last reading across app launches.
 *
 * **Why SharedPreferences and not Room.** Everything in the Room database describes the user's pets
 * and is synced to Firestore so it follows the account to another device. This is neither: it is one
 * number about the weather *here*, it is worthless on another handset, and it is meaningless within
 * the hour. Giving it an entity, a DAO and a place in the sync would be a lot of machinery around a
 * value with a thirty-minute life. SharedPreferences is what Android provides for exactly this —
 * a couple of primitives, read synchronously — and the app already uses it the same way for the
 * light/dark choice (see [com.prash.petcare.ui.common.ThemePreference], which explains the same
 * reasoning at more length).
 *
 * It lives under `data.local` because it *is* local persistence, and behind the repository like
 * every other data source: nothing above the data layer knows this file exists.
 *
 * @param context Any context; only the application context is kept, so no screen can be leaked.
 */
class WeatherCache(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * The stored reading, or null when nothing has ever been fetched on this device.
     *
     * Both values are checked rather than just one: a reading with no timestamp could not be aged,
     * and a timestamp with no reading has nothing to show. Neither should be possible — [write] puts
     * them in together — but reading them back as a pair is what makes that a guarantee rather than
     * an assumption about a file the user could in principle clear between the two lines.
     */
    fun read(): CachedWeather? {
        if (!preferences.contains(KEY_TEMPERATURE) || !preferences.contains(KEY_FETCHED_AT)) {
            return null
        }
        return CachedWeather(
            // Stored as a Float because SharedPreferences has no putDouble. Nothing is lost that
            // matters: a Float holds a temperature to far more precision than the card's whole
            // degrees, or than the weather itself is known to.
            temperatureCelsius = preferences.getFloat(KEY_TEMPERATURE, 0f).toDouble(),
            fetchedAtMillis = preferences.getLong(KEY_FETCHED_AT, 0L)
        )
    }

    /**
     * Replaces the stored reading.
     *
     * Only ever called after a *successful* fetch, so a failure leaves the previous value in place
     * to be shown while offline rather than wiping the card. Both values are written in one `edit`
     * block, which commits them as a single transaction — a reading can never end up stored with the
     * previous fetch's timestamp and so look fresher than it is.
     *
     * The `edit` extension commits with `apply()`, writing to disk on a background thread. Nothing
     * waits on the result, and the in-memory value is updated immediately either way.
     */
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
