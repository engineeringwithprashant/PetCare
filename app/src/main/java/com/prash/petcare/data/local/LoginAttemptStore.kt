package com.prash.petcare.data.local

import android.content.Context
import androidx.core.content.edit

/** What this device remembers about one email address's recent failed sign-ins. */
data class LoginAttemptRecord(
    val failedAttempts: Int = 0,
    val lockedUntil: Long = 0L,
    val lastAttemptAt: Long = 0L
) {

    /** True while this address is inside its lock window. */
    val isLocked: Boolean get() = lockedUntil > 0L
}

/** Counts consecutive failed password attempts per email address. */
class LoginAttemptStore(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis
) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** What is known about [email] right now. */
    fun recordFor(email: String): LoginAttemptRecord {
        val key = keyFor(email)
        val lockedUntil = preferences.getLong(key + KEY_LOCKED_UNTIL, 0L)
        val lastAttemptAt = preferences.getLong(key + KEY_LAST_ATTEMPT, 0L)

        // Stale record: too long since the last failure for it to count as consecutive.
        if (lastAttemptAt > 0L && now() - lastAttemptAt >= STALE_AFTER_MILLIS) {
            clear(email)
            return LoginAttemptRecord()
        }

        // The lock has run out. The failure count goes with it: the user has served the wait.
        if (lockedUntil in 1..now()) {
            clear(email)
            return LoginAttemptRecord()
        }

        return LoginAttemptRecord(
            failedAttempts = preferences.getInt(key + KEY_ATTEMPTS, 0),
            lockedUntil = lockedUntil,
            lastAttemptAt = lastAttemptAt
        )
    }

    /** Records one wrong password for [email] and returns the record that results — including the lock. */
    fun recordFailure(email: String): LoginAttemptRecord {
        val moment = now()
        // Through recordFor, so a failure arriving after a stale or expired record starts from zero.
        val attempts = recordFor(email).failedAttempts + 1
        val lockedUntil = if (attempts >= MAX_ATTEMPTS) moment + LOCK_DURATION_MILLIS else 0L

        val key = keyFor(email)
        preferences.edit {
            putInt(key + KEY_ATTEMPTS, attempts)
            putLong(key + KEY_LAST_ATTEMPT, moment)
            putLong(key + KEY_LOCKED_UNTIL, lockedUntil)
            // Which address this was. See lockedEmail() for why the screen needs it by name.
            putString(KEY_LAST_EMAIL, email.trim())
        }
        pruneStaleRecords()

        return LoginAttemptRecord(attempts, lockedUntil, moment)
    }

    /** The address currently locked out on this device, or null if none is. */
    fun lockedEmail(): String? {
        val email = preferences.getString(KEY_LAST_EMAIL, null)?.takeIf { it.isNotBlank() }
            ?: return null
        return email.takeIf { recordFor(it).isLocked }
    }

    /** Forgets everything about [email]: no count, no lock. */
    fun clear(email: String) {
        val key = keyFor(email)
        preferences.edit {
            remove(key + KEY_ATTEMPTS)
            remove(key + KEY_LOCKED_UNTIL)
            remove(key + KEY_LAST_ATTEMPT)
            // Forget the pointer too, when it names this address.
            if (preferences.getString(KEY_LAST_EMAIL, null)?.trim().equals(email.trim(), true)) {
                remove(KEY_LAST_EMAIL)
            }
        }
    }

    /** Drops every address whose last failure is older than [STALE_AFTER_MILLIS]. */
    private fun pruneStaleRecords() {
        val cutoff = now() - STALE_AFTER_MILLIS
        val stale = preferences.all.keys
            .filter { it.endsWith(KEY_LAST_ATTEMPT) }
            .filter { preferences.getLong(it, 0L) < cutoff }
            .map { it.removeSuffix(KEY_LAST_ATTEMPT) }
        if (stale.isEmpty()) return

        preferences.edit {
            stale.forEach { key ->
                remove(key + KEY_ATTEMPTS)
                remove(key + KEY_LOCKED_UNTIL)
                remove(key + KEY_LAST_ATTEMPT)
            }
        }
    }

    /** The preference key prefix for one address. */
    private fun keyFor(email: String): String = KEY_PREFIX + email.trim().lowercase()

    companion object {

        /** How many wrong passwords in a row lock sign-in. */
        const val MAX_ATTEMPTS = 5

        /** How long the lock lasts, in milliseconds. Quoted on screen as "15 minutes". */
        const val LOCK_DURATION_MILLIS = 15L * 60L * 1000L

        /** After this long with no failure, a record is forgotten. See the class note on pruning. */
        private const val STALE_AFTER_MILLIS = 24L * 60L * 60L * 1000L

        /** Its own file rather than sharing another: unrelated settings, unrelated lifetimes. */
        private const val PREFERENCES_NAME = "petcare_login_attempts"

        private const val KEY_PREFIX = "account:"
        private const val KEY_ATTEMPTS = "|attempts"
        private const val KEY_LOCKED_UNTIL = "|locked_until"
        private const val KEY_LAST_ATTEMPT = "|last_attempt"

        /** The address of the most recent failure, so [lockedEmail] can answer on a cold start. */
        private const val KEY_LAST_EMAIL = "last_attempted_email"
    }
}
