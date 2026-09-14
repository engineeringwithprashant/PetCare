package com.prash.petcare.ui.common

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

/**
 * Remembers whether the user has chosen light or dark mode, and applies that choice.
 *
 * **Why this is not in Room, and not a ViewModel.** Everything in the app's data layer describes the
 * user's *pets* — their care, their costs, their photographs — and is synced to Firestore so it
 * follows the account onto another device. A theme choice is none of those things: it belongs to
 * this installation on this handset, it would be wrong to carry it to a tablet, and it has to be
 * readable before any database is opened, because the decision is made in
 * [com.prash.petcare.PetCareApplication.onCreate] before the first Activity exists.
 *
 * `SharedPreferences` is what Android provides for exactly this — a couple of values, read
 * synchronously, needed at start-up. Adding it does not widen the data layer; nothing here is ever
 * read by a repository, a DAO or the sync.
 *
 * **How the mode actually changes.** [AppCompatDelegate.setDefaultNightMode] is the whole mechanism:
 * AppCompat recreates the running Activity, Android re-resolves every resource, and `values-night`
 * takes over from `values`. No layout and no fragment is told about it, which is precisely why the
 * app's themed colours were worth building the way they are — see values/colors.xml.
 *
 * An object rather than a class: there is one setting per process and nothing to construct.
 */
object ThemePreference {

    private const val PREFERENCES_NAME = "petcare_display"
    private const val KEY_NIGHT_MODE = "night_mode"

    /**
     * Applies the stored choice.
     *
     * Called once from the Application, before any Activity is created, so the first screen is drawn
     * in the right mode rather than being drawn light and then recreated dark — which the user would
     * see as a flash on every launch.
     */
    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(storedMode(context))
    }

    /**
     * Switches to the opposite of whatever is on screen now, and remembers it.
     *
     * The *effective* mode is toggled rather than the stored one, and the difference matters on the
     * first tap. Until the user chooses, the stored value is "follow the system", which is neither
     * light nor dark; flipping that would need a rule for what the opposite of "follow the system"
     * is. Asking the resources what is actually being shown — see [isNightModeActive] — turns the
     * first tap into the plain answer the user expects: whatever I can see, give me the other one.
     *
     * Writing the value and applying it are both done here so the two can never disagree. The `edit`
     * extension commits with `apply()` by default, which writes to disk on a background thread:
     * nothing is waiting on the result, and the in-memory value is updated immediately either way.
     */
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

    /**
     * Whether dark mode is what the user is currently looking at.
     *
     * Read from the [Configuration] the context was created with rather than from the stored
     * preference, because those two are not the same question. The stored value may be "follow the
     * system", and only the configuration knows what the system then decided. A `Context` from a
     * view or a fragment always carries the configuration its resources were resolved against, so
     * this is accurate even in the moment after a mode change.
     *
     * The UI_MODE_NIGHT_MASK is needed because `uiMode` packs two unrelated things into one int —
     * the night setting and the device type (television, car, watch). Masking keeps the comparison
     * to the half being asked about.
     */
    fun isNightModeActive(context: Context): Boolean {
        val nightBits = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightBits == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * The stored choice, defaulting to following the device.
     *
     * Following the system is the right default rather than forcing light: a user who has put their
     * whole phone into dark mode has already stated a preference, and an app that ignores it is the
     * one bright window at midnight.
     */
    private fun storedMode(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
}
