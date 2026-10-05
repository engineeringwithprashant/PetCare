package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A record that a row used to exist and was deleted — a "tombstone". */
@Entity(
    tableName = "sync_deletions",
    // Unique on the pair, not on the id alone, because the same number is a valid row id in every table.
    indices = [Index(value = ["entityType", "entityId"], unique = true)]
)
data class SyncDeletion(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val entityType: SyncEntityType,
    val entityId: Long,
    val deletedAt: Long = System.currentTimeMillis()
)
