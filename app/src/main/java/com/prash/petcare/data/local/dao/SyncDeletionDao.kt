package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.prash.petcare.data.local.entity.SyncDeletion
import kotlinx.coroutines.flow.Flow

/** Database access for the `sync_deletions` table — the queue of deletions still to be sent. */
@Dao
interface SyncDeletionDao {

    /** Records a deletion. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(deletion: SyncDeletion)

    /** Records several deletions at once, for a delete that removes a batch of rows. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(deletions: List<SyncDeletion>)

    /** Everything still waiting to be sent, oldest first so deletions replay in the order made. */
    @Query("SELECT * FROM sync_deletions ORDER BY deletedAt ASC")
    suspend fun getAll(): List<SyncDeletion>

    /** Observes whether anything is queued. */
    @Query("SELECT COUNT(*) FROM sync_deletions")
    fun observeCount(): Flow<Int>

    /** Clears tombstones that have been confirmed to Firestore. */
    @Query("DELETE FROM sync_deletions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
