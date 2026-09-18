package com.prash.petcare.data.local

import android.content.Context
import androidx.core.content.edit

/**
 * What this device remembers about one email address's recent failed sign-ins.
 *
 * A plain value class with no behaviour: [LoginAttemptStore] reads and writes it, and
 * [com.prash.petcare.ui.auth.AuthViewModel] turns it into what the screen shows. Times are epoch
 * milliseconds, the same unit the rest of the app stores times in.
 *
 * @property failedAttempts How many wrong passwords in a row have been entered for this address.
 *   Reset to zero by a successful sign-in, by a password reset, and once the record goes stale.
 * @property lockedUntil When sign-in reopens, or 0 when this address is not locked. A lock that has
 *   already passed is never reported here — [LoginAttemptStore.recordFor] clears it first.
 * @property lastAttemptAt When the most recent failure happened, or 0 if there has never been one.
 *   The locked screen names this time ("Last try 5:38 PM").
 */
data class LoginAttemptRecord(
    val failedAttempts: Int = 0,
    val lockedUntil: Long = 0L,
    val lastAttemptAt: Long = 0L
) {

    /** True while this address is inside its lock window. */
    val isLocked: Boolean get() = lockedUntil > 0L
}

/**
 * Counts consecutive failed password attempts per email address, and locks sign-in on this device
 * once there have been too many.
 *
 * **TODO(server-side-lockout): this is a client-side speed bump, not account security.** Everything
 * here lives in this app's own SharedPreferences file, so it protects nothing that an attacker with
 * the handset could not get around — clearing the app's data, or simply using a different device or
 * the Firebase API directly, wipes the lock. It exists to slow down someone idly guessing at a
 * borrowed phone and to tell the real owner plainly what is happening. The *real* throttling is
 * Firebase Authentication's own server-side rate limiting, which is unaffected by any of this and
 * still applies (it surfaces in the app as [com.prash.petcare.data.remote.AuthFailure.RATE_LIMITED]).
 * Nobody should later read this class as a guarantee that an account cannot be brute-forced.
 *
 * **Why SharedPreferences rather than Room.** The same reasoning as [AlertReadStore] and
 * [WeatherCache], which set it out at more length. Everything in the Room database describes the
 * user's animals and is synced to Firestore so it follows the account to another handset. "This
 * handset has seen three wrong passwords for this address in the last minute" is none of those
 * things: it is two numbers, it is meaningless within the hour, and carrying it to another device
 * would be actively wrong — the lock is deliberately about *this* device. Adding an entity, a DAO, a
 * migration and a place in the sync for it would be a great deal of machinery around two integers.
 *
 * **Because nothing here is in Room, adding this feature needs no database migration** — no
 * reinstall, and no existing data is touched.
 *
 * **Why it is keyed by email.** The count belongs to the address being typed, not to the app: typing
 * a colleague's address must not consume the attempts left on your own, and coming back to your own
 * address must show the count you actually left it at. The address is normalised
 * (trimmed and lower-cased) so "Prashant@x.app" and "prashant@x.app " are one account here, as they
 * are to Firebase.
 *
 * **Why records are pruned.** Without it the file would keep a row for every address ever mistyped
 * on the device. Anything untouched for [STALE_AFTER_MILLIS] is dropped on the next write and read
 * as a clean slate — far longer than the 15-minute lock it has to outlive, but short enough that
 * yesterday's two failures do not count towards today's five. "Consecutive" reasonably means
 * "consecutive, and recent".
 *
 * @param context Any context; only the application context is kept, so no screen can be leaked.
 * @param now Reads the current time. Injectable so a unit test can move the clock forward past a
 *   lock instead of waiting fifteen real minutes.
 */
class LoginAttemptStore(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis
) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * What is known about [email] right now.
     *
     * **Reading also expires things**, which is what makes the lock clear itself: a lock whose time
     * has passed, and a record nobody has touched in a day, are erased here and reported as an empty
     * record. So there is no scheduled job and no "unlock" call anywhere — the next read after the
     * fifteen minutes simply finds no lock. The screen's countdown re-reads once a second, which is
     * how the locked screen turns itself back into the form.
     */
    fun recordFor(email: String): LoginAttemptRecord {
        val key = keyFor(email)
        val lockedUntil = preferences.getLong(key + KEY_LOCKED_UNTIL, 0L)
        val lastAttemptAt = preferences.getLong(key + KEY_LAST_ATTEMPT, 0L)

        // Stale record: too long since the last failure for it to count as consecutive.
        if (lastAttemptAt > 0L && now() - lastAttemptAt >= STALE_AFTER_MILLIS) {
            clear(email)
            return LoginAttemptRecord()
        }

        // The lock has run out. The failure count goes with it: the user has served the wait, so
        // they start the next round with all five attempts rather than being locked again by one
        // more mistake.
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

    /**
     * Records one wrong password for [email] and returns the record that results — including the
     * lock, when this failure was the [MAX_ATTEMPTS]th.
     *
     * The caller does not decide when to lock; this does, so the rule lives in exactly one place and
     * the ViewModel only has to draw whatever comes back.
     */
    fun recordFailure(email: String): LoginAttemptRecord {
        val moment = now()
        // Through recordFor, so a failure arriving after a stale or expired record starts from zero
        // rather than adding to a count that should already have been forgotten.
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

    /**
     * The address currently locked out on this device, or null if none is.
     *
     * **Why this exists.** Everything else here is looked up *by* an address, which is right while
     * the user is typing one. But on a cold start the log in field is empty, so there is no address
     * to look up, and the screen would show an ordinary form until the locked address happened to be
     * typed again — making a lock that is still very much in force look as though closing the app had
     * cleared it. This lets the screen ask the opposite question — "is anything locked?" — before a
     * single key is pressed.
     *
     * Only the most recent address is kept. A lock is a statement about *this device* being used to
     * guess at *an* account, and in practice that is one account at a time; keeping a list would mean
     * choosing which lock to show when two were live.
     *
     * Goes through [recordFor], so an expired or stale lock reports null here just as it does
     * everywhere else, and the same single rule decides whether a lock still stands.
     */
    fun lockedEmail(): String? {
        val email = preferences.getString(KEY_LAST_EMAIL, null)?.takeIf { it.isNotBlank() }
            ?: return null
        return email.takeIf { recordFor(it).isLocked }
    }

    /**
     * Forgets everything about [email]: no count, no lock.
     *
     * Called on a successful sign-in — the point of counting *consecutive* failures — and when a
     * password reset email goes out, since someone who can reset the password has proved they own
     * the mailbox and should not then be kept waiting by a lock meant for a stranger.
     */
    fun clear(email: String) {
        val key = keyFor(email)
        preferences.edit {
            remove(key + KEY_ATTEMPTS)
            remove(key + KEY_LOCKED_UNTIL)
            remove(key + KEY_LAST_ATTEMPT)
            // Forget the pointer too, when it names this address: leaving it behind would have
            // lockedEmail() keep answering with an account that no longer has a record at all.
            if (preferences.getString(KEY_LAST_EMAIL, null)?.trim().equals(email.trim(), true)) {
                remove(KEY_LAST_EMAIL)
            }
        }
    }

    /**
     * Drops every address whose last failure is older than [STALE_AFTER_MILLIS].
     *
     * Runs on each write rather than on a timer: writes are rare (someone has to type a wrong
     * password), and it is the only moment the file can grow. Iterating a copy of the keys avoids
     * mutating the map the preferences hand back while reading it.
     */
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

    /**
     * The preference key prefix for one address.
     *
     * Lower-cased and trimmed so the same account cannot hold two separate counts, and prefixed so
     * these keys can never collide with a future setting in this file.
     */
    private fun keyFor(email: String): String = KEY_PREFIX + email.trim().lowercase()

    companion object {

        /**
         * How many wrong passwords in a row lock sign-in.
         *
         * The screen's wording quotes this figure ("4 of 5 attempts left"), so the copy in
         * strings.xml has to be kept in step with it — see login_alert_detail.
         */
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
