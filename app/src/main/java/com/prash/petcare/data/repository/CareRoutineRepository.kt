package com.prash.petcare.data.repository

import com.prash.petcare.data.local.dao.CareRoutineDao
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.relation.CareRoutineWithTasks
import kotlinx.coroutines.flow.Flow

/**
 * Supplies repeating care schedules to the ViewModel layer.
 *
 * See [UserRepository] for why this layer exists and how it fits the MVVM chain.
 *
 * @property careRoutineDao Injected through the constructor so tests can substitute a fake.
 */
class CareRoutineRepository(private val careRoutineDao: CareRoutineDao) {

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

    suspend fun addRoutine(routine: CareRoutine): Long = careRoutineDao.insert(routine)

    suspend fun updateRoutine(routine: CareRoutine) = careRoutineDao.update(routine)

    /**
     * Pauses or resumes a routine without deleting it.
     *
     * This is the preferred way to stop a schedule: [deleteRoutine] discards the routine
     * permanently, whereas pausing keeps it available to switch back on later.
     */
    suspend fun setRoutineActive(routineId: Long, isActive: Boolean) =
        careRoutineDao.setActive(routineId, isActive)

    /**
     * Deletes a routine permanently. Tasks it already generated survive as history, with their
     * link to this routine cleared.
     */
    suspend fun deleteRoutine(routine: CareRoutine) = careRoutineDao.delete(routine)
}
