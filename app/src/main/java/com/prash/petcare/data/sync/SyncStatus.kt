package com.prash.petcare.data.sync

/** How the background sync is getting on, for the indicator on the home screen. */
sealed interface SyncStatus {

    /** Nothing has been attempted yet in this session. */
    data object Idle : SyncStatus

    /** A sync is running right now. */
    data object Syncing : SyncStatus

    /** There are local changes that Firestore does not have yet. */
    data class Pending(val changeCount: Int) : SyncStatus

    /** Everything on this device is in Firestore, as of [atMillis]. */
    data class Synced(val atMillis: Long) : SyncStatus

    /** Firestore could not be reached, but nothing is waiting to go up. */
    data object Offline : SyncStatus
}
