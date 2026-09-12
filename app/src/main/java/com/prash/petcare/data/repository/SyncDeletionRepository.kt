package com.prash.petcare.data.repository

import com.prash.petcare.data.local.dao.SyncDeletionDao
import com.prash.petcare.data.local.entity.SyncDeletion
import kotlinx.coroutines.flow.Flow

/**
 * Supplies the queue of pending deletions to the sync layer.
 *
 * Exists so [com.prash.petcare.data.sync.SyncManager] reaches Room the way everything else in the
 * app does — through a repository rather than a DAO. It is the thinnest repository here because
 * there is genuinely nothing to decide: tombstones are written by the four data repositories as
 * rows are deleted, and read and cleared by the sync.
 *
 * See [UserRepository] for why this layer exists and how it fits the MVVM chain.
 *
 * @property syncDeletionDao Injected through the constructor so tests can substitute a fake.
 */
class SyncDeletionRepository(private val syncDeletionDao: SyncDeletionDao) {

    /** Everything still waiting to be sent, oldest first. */
    suspend fun getPendingDeletions(): List<SyncDeletion> = syncDeletionDao.getAll()

    /**
     * Observes how many deletions are queued.
     *
     * The sync watches this so that deleting something is itself a reason to sync. Without it a
     * deletion would be the one kind of change that never woke the sync up, because the other
     * triggers all watch for rows appearing or changing — and a deleted row does neither.
     */
    fun observePendingCount(): Flow<Int> = syncDeletionDao.observeCount()

    /**
     * Forgets deletions that Firestore has confirmed.
     *
     * Takes the specific ids that were sent rather than clearing the table, so a row deleted by the
     * user while the sync was in flight is still there to be sent next time.
     */
    suspend fun clearDeletions(ids: List<Long>) {
        if (ids.isNotEmpty()) syncDeletionDao.deleteByIds(ids)
    }
}
