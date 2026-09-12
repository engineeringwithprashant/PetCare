package com.prash.petcare.data.sync

/**
 * How the background sync is getting on, for the indicator on the home screen.
 *
 * The states are more than a "syncing yes/no" because the distinctions matter to the person reading
 * them — above all [Pending] versus [Synced], which is the difference between "your last change is
 * only on this phone" and "everything is safely uploaded".
 *
 * A sealed interface rather than an enum so [Synced] and [Pending] can carry a value; an enum
 * constant cannot. "Backed up 2 minutes ago" and "3 changes waiting" both say considerably more
 * than the bare state name would.
 */
sealed interface SyncStatus {

    /**
     * Nothing has been attempted yet in this session.
     *
     * The state the app starts in, before anyone has signed in — at which point there is no account
     * whose data could be synced. Shown as nothing at all rather than as a message, since there is
     * nothing for the user to know.
     */
    data object Idle : SyncStatus

    /** A sync is running right now. */
    data object Syncing : SyncStatus

    /**
     * There are local changes that Firestore does not have yet.
     *
     * The honest resting state whenever the device is offline with unsaved work, and equally when a
     * push failed for any other reason. It is read from the database rather than remembered in
     * memory — see [com.prash.petcare.data.local.entity.Pet.isPendingSync] — so closing and
     * reopening the app reports exactly the same thing rather than forgetting there was anything
     * outstanding.
     *
     * @property changeCount How many records are waiting, counting queued deletions.
     */
    data class Pending(val changeCount: Int) : SyncStatus

    /**
     * Everything on this device is in Firestore, as of [atMillis].
     *
     * Reached only when a cycle has succeeded *and* nothing is left queued, so it is a genuine
     * all-clear rather than "the last attempt happened to work".
     *
     * @property atMillis When the last successful sync completed, as epoch milliseconds.
     */
    data class Synced(val atMillis: Long) : SyncStatus

    /**
     * Firestore could not be reached, but nothing is waiting to go up.
     *
     * The quiet case — opening the app with no signal and no unsent edits. Distinguished from
     * [Pending] because nothing is at risk, and from [Synced] because this session has not actually
     * confirmed anything with the server.
     */
    data object Offline : SyncStatus
}
