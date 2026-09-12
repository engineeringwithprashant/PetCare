package com.prash.petcare.data.repository

import com.prash.petcare.data.local.dao.CareRoutineDao
import com.prash.petcare.data.local.dao.SyncDeletionDao
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.SyncDeletion
import com.prash.petcare.data.local.entity.SyncEntityType
import com.prash.petcare.data.local.relation.CareRoutineWithTasks
import kotlinx.coroutines.flow.Flow

/**
 * Supplies repeating care schedules to the ViewModel layer.
 *
 * See [UserRepository] for why this layer exists and how it fits the MVVM chain, and
 * [PetRepository] for why the sync timestamps and tombstones are maintained in this layer.
 *
 * @property careRoutineDao Injected through the constructor so tests can substitute a fake.
 * @property syncDeletionDao Where deletions are recorded for the sync layer to send on.
 */
class CareRoutineRepository(
    private val careRoutineDao: CareRoutineDao,
    private val syncDeletionDao: SyncDeletionDao
) {

    /** Observes all routines for a pet, active ones listed first. */
    fun observeRoutinesForPet(petId: Long): Flow<List<CareRoutine>> =
        careRoutineDao.observeByPet(petId)

    /** Observes only the routines still generating tasks for a pet. */
    fun observeActiveRoutinesForPet(petId: Long): Flow<List<CareRoutine>> =
        careRoutineDao.observeActiveByPet(petId)

    /** Observes every active routine across all of an owner's pets. */
    fun observeActiveRoutinesForOwner(ownerId: Long): Flow<List<CareRoutine>> =
        careRoutineDao.observeActiveByOwner(ownerId)

    fun observeRoutine(routineId: Long): Flow<CareRoutine?> = careRoutineDao.observeById(routineId)

    /** Observes a routine with the tasks it has generated, for its detail screen. */
    fun observeRoutineWithTasks(routineId: Long): Flow<CareRoutineWithTasks?> =
        careRoutineDao.observeRoutineWithTasks(routineId)

    suspend fun getRoutine(routineId: Long): CareRoutine? = careRoutineDao.getById(routineId)

    suspend fun addRoutine(routine: CareRoutine): Long = careRoutineDao.insert(
        routine.copy(updatedAt = System.currentTimeMillis(), isPendingSync = true)
    )

    suspend fun updateRoutine(routine: CareRoutine) = careRoutineDao.update(
        routine.copy(updatedAt = System.currentTimeMillis(), isPendingSync = true)
    )

    /**
     * Pauses or resumes a routine without deleting it.
     *
     * This is the preferred way to stop a schedule: [deleteRoutine] discards the routine
     * permanently, whereas pausing keeps it available to switch back on later.
     */
    suspend fun setRoutineActive(routineId: Long, isActive: Boolean) =
        careRoutineDao.setActive(routineId, isActive, System.currentTimeMillis())

    /**
     * Deletes a routine permanently. Tasks it already generated survive as history, with their
     * link to this routine cleared.
     *
     * The tombstone is written first, while the routine's id is still readable from a row that
     * exists.
     */
    suspend fun deleteRoutine(routine: CareRoutine) {
        syncDeletionDao.insert(
            SyncDeletion(entityType = SyncEntityType.CARE_ROUTINE, entityId = routine.id)
        )
        careRoutineDao.delete(routine)
    }

    // -------------------------------------------------------------------------------------------
    // Used by the sync layer — see the equivalent section of PetRepository.
    // -------------------------------------------------------------------------------------------

    /** Every routine belonging to an owner, paused ones included, as a one-off read. */
    suspend fun getRoutinesForOwner(ownerId: Long): List<CareRoutine> =
        careRoutineDao.getByOwner(ownerId)

    /** The sync layer's change signal — see [PetDao.observeLatestUpdateForOwner]. */
    fun observeLatestRoutineUpdate(ownerId: Long): Flow<Long?> =
        careRoutineDao.observeLatestUpdateForOwner(ownerId)

    /** Routine ids another account on this device owns — see [PetDao.getIdsNotOwnedBy]. */
    suspend fun getRoutineIdsNotOwnedBy(ownerId: Long): Set<Long> =
        careRoutineDao.getIdsNotOwnedBy(ownerId).toSet()

    /** Marks routines as uploaded once Firestore has them — see [PetDao.clearPendingSync]. */
    suspend fun clearPendingRoutineSync(ownerId: Long, syncedUpTo: Long) =
        careRoutineDao.clearPendingSync(ownerId, syncedUpTo)

    /** How many routines are still waiting to be uploaded, for the sync indicator. */
    fun observePendingRoutineCount(ownerId: Long): Flow<Int> =
        careRoutineDao.observePendingSyncCount(ownerId)

    /** Writes routines pulled from Firestore into Room, keeping the timestamps they arrived with. */
    suspend fun upsertRoutinesFromSync(routines: List<CareRoutine>) =
        careRoutineDao.upsertAll(routines)
}
