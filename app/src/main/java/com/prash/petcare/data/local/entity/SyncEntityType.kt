package com.prash.petcare.data.local.entity

/**
 * Which table a [SyncDeletion] refers to.
 *
 * A deletion has to name both *what* was deleted and *which* row, because a row id on its own is
 * ambiguous across tables — pet 4, task 4 and expense 4 can all exist at once. This constant is the
 * "what": the sync layer maps it to the remote collection the document should be removed from.
 *
 * [User] deliberately has no constant here: an account is not synced, only the data belonging to
 * one — see [com.prash.petcare.data.sync.SyncManager] for why.
 *
 * Stored in SQLite as a String via [com.prash.petcare.data.local.converter.Converters].
 */
enum class SyncEntityType {
    PET,
    CARE_ROUTINE,
    CARE_TASK,
    EXPENSE
}
