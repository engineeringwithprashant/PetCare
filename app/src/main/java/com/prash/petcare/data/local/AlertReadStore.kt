package com.prash.petcare.data.local

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Remembers which alerts the user has already read. */
class AlertReadStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** The ids read so far, seeded from disk on construction. */
    private val _readIds = MutableStateFlow(
        preferences.getStringSet(KEY_READ_IDS, emptySet())?.toSet() ?: emptySet()
    )

    /** Which alert ids the user has already seen. */
    val readIds: StateFlow<Set<String>> = _readIds.asStateFlow()

    /** Marks one alert read — what tapping an alert card does, alongside opening its screen. */
    fun markRead(alertId: String) {
        markRead(setOf(alertId))
    }

    /** Marks several read at once — what the "MARK READ" button does. */
    fun markRead(alertIds: Set<String>) {
        if (alertIds.isEmpty()) return
        write(_readIds.value + alertIds)
    }

    /** Forgets every id that is not in [currentIds]. */
    fun keepOnly(currentIds: Set<String>) {
        val kept = _readIds.value.intersect(currentIds)
        if (kept.size != _readIds.value.size) {
            write(kept)
        }
    }

    /** Stores the set and publishes it. */
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
