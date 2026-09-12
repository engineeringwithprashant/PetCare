package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A record that a row **used to exist and was deleted** — a "tombstone".
 *
 * **Why deleting a row is not enough.** The sync layer decides what to send by looking at what is
 * in the database. A deleted row is, by definition, not there any more, so a deletion leaves no
 * trace for the sync to find and the copy sitting in Firestore would survive. Worse, the next pull
 * would see a remote document with no local row, conclude it was something this device had never
 * seen, and put the deleted pet straight back. A tombstone is what turns "this row is absent" into
 * the positive statement "this row was deleted", which is a thing that can be sent.
 *
 * **Why no owner is recorded.** Row ids come from SQLite's AUTOINCREMENT and are unique within
 * their table across the whole database, so pet 4 belongs to exactly one account. A tombstone
 * therefore cannot be confused with another user's row, and removing a document that the current
 * user's Firestore collection does not contain is simply a no-op.
 *
 * Rows here are deleted once the deletion has been confirmed to Firestore, so the table stays
 * empty in normal use and only fills up while the device is offline.
 *
 * @property id Primary key, assigned by SQLite on insert.
 * @property entityType Which table the deleted row was in — see [SyncEntityType].
 * @property entityId The deleted row's primary key.
 * @property deletedAt When the deletion happened, as epoch milliseconds. Used as the deletion's
 *   own "last write" timestamp, so a row deleted on one device and edited on another resolves the
 *   same way any other conflict does: the later action wins.
 */
@Entity(
    tableName = "sync_deletions",
    // Unique on the pair, not on the id alone, because the same number is a valid row id in every
    // table. Combined with the DAO's REPLACE conflict strategy, this also makes recording the same
    // deletion twice harmless — the second simply refreshes the first rather than queueing a
    // duplicate to send.
    indices = [Index(value = ["entityType", "entityId"], unique = true)]
)
data class SyncDeletion(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val entityType: SyncEntityType,
    val entityId: Long,
    val deletedAt: Long = System.currentTimeMillis()
)
