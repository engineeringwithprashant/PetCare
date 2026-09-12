package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.prash.petcare.data.local.entity.SyncDeletion
import kotlinx.coroutines.flow.Flow

/**
 * Database access for the `sync_deletions` table — the queue of deletions still to be sent.
 *
 * See [UserDao] for the reasoning behind the [Flow] versus `suspend` return types used here.
 */
@Dao
interface SyncDeletionDao {

    /**
     * Records a deletion.
     *
     * REPLACE rather than the default ABORT: the unique index on (entityType, entityId) means
     * re-recording the same deletion would otherwise throw. It can genuinely happen — a pull can
     * restore a row from Firestore that the user then deletes again — and a crash is no way to
     * react to someone deleting something twice.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(deletion: SyncDeletion)

    /** Records several deletions at once, for a delete that removes a batch of rows. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(deletions: List<SyncDeletion>)

    /** Everything still waiting to be sent, oldest first so deletions replay in the order made. */
    @Query("SELECT * FROM sync_deletions ORDER BY deletedAt ASC")
    suspend fun getAll(): List<SyncDeletion>

    /**
     * Observes whether anything is queued.
     *
     * Exposed as a count rather than the rows themselves because the sync layer only needs to know
     * *that* there is something to send — a new tombstone is one of the things that should wake it
     * up, and comparing counts avoids re-reading a list it is about to read anyway.
     */
    @Query("SELECT COUNT(*) FROM sync_deletions")
    fun observeCount(): Flow<Int>

    /**
     * Clears tombstones that have been confirmed to Firestore.
     *
     * Takes the ids that were actually sent rather than emptying the table, so a deletion recorded
     * while the sync was mid-flight is not thrown away unsent.
     */
    @Query("DELETE FROM sync_deletions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
