package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
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

    /** Applies routines pulled from Firestore — see [PetDao.upsertAll] for why [Upsert]. */
    @Upsert
    suspend fun upsertAll(routines: List<CareRoutine>)

    /**
     * One snapshot of every routine belonging to an owner, for the sync layer.
     *
     * Joins through `pets` for the same reason [observeActiveByOwner] does, and unlike that query
     * it deliberately includes paused routines: a paused routine is still the user's data and
     * losing it on a reinstall would be no better than losing an active one.
     */
    @Query(
        """
        SELECT care_routines.* FROM care_routines
        INNER JOIN pets ON pets.id = care_routines.petId
        WHERE pets.ownerId = :ownerId
        """
    )
    suspend fun getByOwner(ownerId: Long): List<CareRoutine>

    /** The sync layer's change signal for routines — see [PetDao.observeLatestUpdateForOwner]. */
    @Query(
        """
        SELECT MAX(care_routines.updatedAt) FROM care_routines
        INNER JOIN pets ON pets.id = care_routines.petId
        WHERE pets.ownerId = :ownerId
        """
    )
    fun observeLatestUpdateForOwner(ownerId: Long): Flow<Long?>

    /** Routines belonging to another account on this device — see [PetDao.getIdsNotOwnedBy]. */
    @Query(
        """
        SELECT care_routines.id FROM care_routines
        INNER JOIN pets ON pets.id = care_routines.petId
        WHERE pets.ownerId != :ownerId
        """
    )
    suspend fun getIdsNotOwnedBy(ownerId: Long): List<Long>

    /**
     * Marks this owner's routines as uploaded — see [PetDao.clearPendingSync].
     *
     * The owner is reached with a subquery rather than a join, because SQLite's UPDATE has no FROM
     * clause: `UPDATE ... INNER JOIN` is not valid SQL here, whereas `petId IN (SELECT ...)` is and
     * expresses exactly the same restriction.
     */
    @Query(
        """
        UPDATE care_routines SET isPendingSync = 0
        WHERE isPendingSync = 1 AND updatedAt <= :syncedUpTo
          AND petId IN (SELECT id FROM pets WHERE ownerId = :ownerId)
        """
    )
    suspend fun clearPendingSync(ownerId: Long, syncedUpTo: Long)

    /** How many of this owner's routines are still waiting to be uploaded. */
    @Query(
        """
        SELECT COUNT(*) FROM care_routines
        INNER JOIN pets ON pets.id = care_routines.petId
        WHERE pets.ownerId = :ownerId AND care_routines.isPendingSync = 1
        """
    )
    fun observePendingSyncCount(ownerId: Long): Flow<Int>

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
     *
     * [updatedAt] moves in the same statement. Pausing is a change like any other and has to be
     * visible to the sync layer, and setting it here rather than in a second UPDATE keeps the row
     * from ever existing in a state where its data has changed but its timestamp says otherwise.
     */
    @Query(
        """
        UPDATE care_routines SET isActive = :isActive, updatedAt = :updatedAt, isPendingSync = 1
        WHERE id = :routineId
        """
    )
    suspend fun setActive(routineId: Long, isActive: Boolean, updatedAt: Long)

    /** Observes a routine together with the tasks it has produced — see [CareRoutineWithTasks]. */
    @Transaction
    @Query("SELECT * FROM care_routines WHERE id = :routineId")
    fun observeRoutineWithTasks(routineId: Long): Flow<CareRoutineWithTasks?>
}
