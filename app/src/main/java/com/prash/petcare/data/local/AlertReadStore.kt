package com.prash.petcare.data.local

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers which alerts the user has already read.
 *
 * **Why this exists at all.** Every alert on the notifications screen is *derived* — a vet visit
 * coming up, a task that went unticked, a pet reaching a month old — rather than stored as a row of
 * its own. That is deliberate (see [com.prash.petcare.ui.notifications.NotificationsViewModel]), but
 * it leaves one thing with nowhere to live: whether the user has seen a given alert. That is not a
 * fact about the pet, so nothing in the pet data can answer it.
 *
 * **Why SharedPreferences and not Room.** The same reasoning as [WeatherCache] and
 * [com.prash.petcare.ui.common.ThemePreference], which both explain it at more length. Everything in
 * the Room database describes the user's animals and is synced to Firestore so it follows the account
 * to another handset. "I have already glanced at this" is none of those things: it is a handful of
 * short strings, it is meaningless once the alert it refers to stops being true, and carrying it to
 * another device would be wrong rather than helpful. Adding an entity, a DAO, a migration and a place
 * in the sync for it would be a great deal of machinery around a set of strings.
 *
 * It lives under `data.local` because it *is* local persistence, alongside [WeatherCache].
 *
 * **Why it publishes a flow.** Marking an alert read has to redraw the list — the card loses its pale
 * fill and its dot, and the "3 new alerts" headline counts down. Exposing the set as a [StateFlow]
 * lets the ViewModel combine it with the pet data like any other source, so a tap updates the screen
 * through exactly the same path a database change does, with no manual refresh.
 *
 * **Why ids are pruned on every write.** An alert's id encodes what it is derived from (the task, the
 * pet, the expense). Those come and go, so without pruning the file would accumulate an id for every
 * alert the app had ever produced. [keepOnly] is called with the ids currently in play, so the stored
 * set can never outgrow what is on screen.
 *
 * @param context Any context; only the application context is kept, so no screen can be leaked.
 */
class AlertReadStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * The ids read so far, seeded from disk on construction.
     *
     * Copied out of the returned set with `toSet()`. `getStringSet` explicitly warns that the
     * instance it hands back must not be modified and may be reused by the preferences file itself,
     * so holding it directly would tie this flow's value to a collection the framework owns.
     */
    private val _readIds = MutableStateFlow(
        preferences.getStringSet(KEY_READ_IDS, emptySet())?.toSet() ?: emptySet()
    )

    /** Which alert ids the user has already seen. */
    val readIds: StateFlow<Set<String>> = _readIds.asStateFlow()

    /** Marks one alert read — what tapping an alert card does, alongside opening its screen. */
    fun markRead(alertId: String) {
        markRead(setOf(alertId))
    }

    /**
     * Marks several read at once — what the "MARK READ" button does.
     *
     * One write rather than one per alert: the button clears everything on screen, and looping over
     * [markRead] would emit a new set for each id and make the list re-diff once per alert.
     */
    fun markRead(alertIds: Set<String>) {
        if (alertIds.isEmpty()) return
        write(_readIds.value + alertIds)
    }

    /**
     * Forgets every id that is not in [currentIds].
     *
     * Called by the ViewModel each time it rebuilds the list. An id whose alert no longer exists —
     * the task was finally ticked, the pet's birthday passed — can never come back meaning the same
     * thing, so keeping it would only grow the file. Should an id genuinely return (a repeating task
     * missed again next week), it is a *new* occurrence and deserves to be unread again.
     *
     * Does nothing when there is nothing to remove, so the common case writes no file and emits no
     * new value — which matters because this runs on every emission of the pet data.
     */
    fun keepOnly(currentIds: Set<String>) {
        val kept = _readIds.value.intersect(currentIds)
        if (kept.size != _readIds.value.size) {
            write(kept)
        }
    }

    /**
     * Stores the set and publishes it.
     *
     * Both in one place so the file and the flow can never disagree. The `edit` extension commits
     * with `apply()`, which writes to disk on a background thread; nothing waits on the result, and
     * the in-memory value below is updated immediately either way.
     */
    private fun write(ids: Set<String>) {
        preferences.edit { putStringSet(KEY_READ_IDS, ids) }
        _readIds.value = ids
    }

    private companion object {
        /** Its own file rather than sharing another: unrelated settings, unrelated lifetimes. */
        const val PREFERENCES_NAME = "petcare_alerts"
        const val KEY_READ_IDS = "read_alert_ids"
    }
}
