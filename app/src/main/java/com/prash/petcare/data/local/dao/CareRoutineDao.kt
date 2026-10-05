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

/** Database access for the `care_routines` table. */
@Dao
interface CareRoutineDao {

    @Insert
    suspend fun insert(routine: CareRoutine): Long

    @Update
    suspend fun update(routine: CareRoutine)

    /** Deletes a routine permanently. */
    @Delete
    suspend fun delete(routine: CareRoutine)

    /** Applies routines pulled from Firestore — see [PetDao.upsertAll] for why [Upsert]. */
    @Upsert
    suspend fun upsertAll(routines: List<CareRoutine>)

    /** One snapshot of every routine belonging to an owner, for the sync layer. */
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

    /** Marks this owner's routines as uploaded — see [PetDao.clearPendingSync]. */
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

    /** Every active routine across all of an owner's pets. */
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

    /** Pauses or resumes a routine. */
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
