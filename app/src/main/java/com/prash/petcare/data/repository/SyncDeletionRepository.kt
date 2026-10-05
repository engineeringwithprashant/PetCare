package com.prash.petcare.data.repository

import com.prash.petcare.data.local.dao.SyncDeletionDao
import com.prash.petcare.data.local.entity.SyncDeletion
import kotlinx.coroutines.flow.Flow

/** Supplies the queue of pending deletions to the sync layer. */
class SyncDeletionRepository(private val syncDeletionDao: SyncDeletionDao) {

    /** Everything still waiting to be sent, oldest first. */
    suspend fun getPendingDeletions(): List<SyncDeletion> = syncDeletionDao.getAll()

    /** Observes how many deletions are queued. */
    fun observePendingCount(): Flow<Int> = syncDeletionDao.observeCount()

    /** Forgets deletions that Firestore has confirmed. */
    suspend fun clearDeletions(ids: List<Long>) {
        if (ids.isNotEmpty()) syncDeletionDao.deleteByIds(ids)
    }
}
