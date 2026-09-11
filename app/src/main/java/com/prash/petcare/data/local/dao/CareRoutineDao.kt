package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.relation.CareRoutineWithTasks
import kotlinx.coroutines.flow.Flow

/**
 * Database access for the `care_routines` table.
 *
 * See [UserDao] for the reasoning behind the [Flow] versus `suspend` return types used here.
 */
@Dao
interface CareRoutineDao {

    @Insert
    suspend fun insert(routine: CareRoutine): Long

    @Update
    suspend fun update(routine: CareRoutine)

    /**
     * Deletes a routine permanently.
     *
     * Tasks this routine already generated are *not* deleted — their `routineId` is set to null
     * by the SET_NULL rule on [com.prash.petcare.data.local.entity.CareTask], preserving the
     * care history. To stop a routine without losing the schedule, prefer [setActive].
     */
    @Delete
    suspend fun delete(routine: CareRoutine)

    /** Observes every routine for a pet, active ones first, then alphabetically. */
    @Query("SELECT * FROM care_routines WHERE petId = :petId ORDER BY isActive DESC, title ASC")
    fun observeByPet(petId: Long): Flow<List<CareRoutine>>

    /** Only the routines still generating tasks — used by the task scheduling logic. */
    @Query("SELECT * FROM care_routines WHERE petId = :petId AND isActive = 1 ORDER BY title ASC")
    fun observeActiveByPet(petId: Long): Flow<List<CareRoutine>>

    /**
     * Every active routine across all of an owner's pets.
     *
     * The join reaches `pets` because a routine only knows its pet, not the owner; storing the
     * owner id on the routine as well would duplicate a fact the pet row already holds and
     * risk the two disagreeing.
     */
    @Query(
        """
        SELECT care_routines.* FROM care_routines
        INNER JOIN pets ON pets.id = care_routines.petId
        WHERE pets.ownerId = :ownerId AND care_routines.isActive = 1
        ORDER BY care_routines.title ASC
        """
    )
    fun observeActiveByOwner(ownerId: Long): Flow<List<CareRoutine>>

    @Query("SELECT * FROM care_routines WHERE id = :routineId")
    fun observeById(routineId: Long): Flow<CareRoutine?>

    @Query("SELECT * FROM care_routines WHERE id = :routineId")
    suspend fun getById(routineId: Long): CareRoutine?

    /**
     * Pauses or resumes a routine.
     *
     * A targeted UPDATE rather than read-modify-write of the whole row: it is one statement, so
     * a concurrent edit to the routine's title cannot be undone by this toggle.
     */
    @Query("UPDATE care_routines SET isActive = :isActive WHERE id = :routineId")
    suspend fun setActive(routineId: Long, isActive: Boolean)

    /** Observes a routine together with the tasks it has produced — see [CareRoutineWithTasks]. */
    @Transaction
    @Query("SELECT * FROM care_routines WHERE id = :routineId")
    fun observeRoutineWithTasks(routineId: Long): Flow<CareRoutineWithTasks?>
}
