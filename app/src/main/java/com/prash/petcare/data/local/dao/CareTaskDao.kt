package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.relation.TaskReminder
import kotlinx.coroutines.flow.Flow

/** Database access for the `care_tasks` table. */
@Dao
interface CareTaskDao {

    @Insert
    suspend fun insert(task: CareTask): Long

    /** Inserts many tasks at once — used. */
    @Insert
    suspend fun insertAll(tasks: List<CareTask>): List<Long>

    @Update
    suspend fun update(task: CareTask)

    /** Applies tasks pulled from Firestore — see [PetDao.upsertAll] for why [Upsert]. */
    @Upsert
    suspend fun upsertAll(tasks: List<CareTask>)

    /** One snapshot of every task belonging to an owner, for the sync layer. */
    @Query(
        """
        SELECT care_tasks.* FROM care_tasks
        INNER JOIN pets ON pets.id = care_tasks.petId
        WHERE pets.ownerId = :ownerId
        """
    )
    suspend fun getByOwner(ownerId: Long): List<CareTask>

    /** The sync layer's change signal for tasks — see [PetDao.observeLatestUpdateForOwner]. */
    @Query(
        """
        SELECT MAX(care_tasks.updatedAt) FROM care_tasks
        INNER JOIN pets ON pets.id = care_tasks.petId
        WHERE pets.ownerId = :ownerId
        """
    )
    fun observeLatestUpdateForOwner(ownerId: Long): Flow<Long?>

    /** Tasks belonging to another account on this device — see [PetDao.getIdsNotOwnedBy]. */
    @Query(
        """
        SELECT care_tasks.id FROM care_tasks
        INNER JOIN pets ON pets.id = care_tasks.petId
        WHERE pets.ownerId != :ownerId
        """
    )
    suspend fun getIdsNotOwnedBy(ownerId: Long): List<Long>

    /** Marks this owner's tasks as uploaded — see [PetDao.clearPendingSync]. */
    @Query(
        """
        UPDATE care_tasks SET isPendingSync = 0
        WHERE isPendingSync = 1 AND updatedAt <= :syncedUpTo
          AND petId IN (SELECT id FROM pets WHERE ownerId = :ownerId)
        """
    )
    suspend fun clearPendingSync(ownerId: Long, syncedUpTo: Long)

    /** How many of this owner's tasks are still waiting to be uploaded. */
    @Query(
        """
        SELECT COUNT(*) FROM care_tasks
        INNER JOIN pets ON pets.id = care_tasks.petId
        WHERE pets.ownerId = :ownerId AND care_tasks.isPendingSync = 1
        """
    )
    fun observePendingSyncCount(ownerId: Long): Flow<Int>

    @Delete
    suspend fun delete(task: CareTask)

    /** The ids of every task in a routine. */
    @Query("SELECT id FROM care_tasks WHERE routineId = :routineId")
    suspend fun getIdsByRoutine(routineId: Long): List<Long>

    /** Removes every task belonging to one routine. */
    @Query("DELETE FROM care_tasks WHERE routineId = :routineId")
    suspend fun deleteByRoutine(routineId: Long)

    /** Observes every task for one pet, soonest due first. */
    @Query("SELECT * FROM care_tasks WHERE petId = :petId ORDER BY dueAt ASC")
    fun observeByPet(petId: Long): Flow<List<CareTask>>

    /** The outstanding to-do list for one pet. `isCompleted = 0` is SQLite's false. */
    @Query("SELECT * FROM care_tasks WHERE petId = :petId AND isCompleted = 0 ORDER BY dueAt ASC")
    fun observePendingByPet(petId: Long): Flow<List<CareTask>>

    /** Every task across all of an owner's pets, whatever its date or state. */
    @Query(
        """
        SELECT care_tasks.* FROM care_tasks
        INNER JOIN pets ON pets.id = care_tasks.petId
        WHERE pets.ownerId = :ownerId
        ORDER BY care_tasks.dueAt ASC
        """
    )
    fun observeByOwner(ownerId: Long): Flow<List<CareTask>>

    /** Every outstanding task across all of an owner's pets, due within a time window. */
    @Query(
        """
        SELECT care_tasks.* FROM care_tasks
        INNER JOIN pets ON pets.id = care_tasks.petId
        WHERE pets.ownerId = :ownerId
          AND care_tasks.isCompleted = 0
          AND care_tasks.dueAt BETWEEN :fromMillis AND :toMillis
        ORDER BY care_tasks.dueAt ASC
        """
    )
    fun observePendingForOwnerBetween(
        ownerId: Long,
        fromMillis: Long,
        toMillis: Long
    ): Flow<List<CareTask>>

    /** Every task for an owner inside a time window, completed or not. */
    @Query(
        """
        SELECT care_tasks.* FROM care_tasks
        INNER JOIN pets ON pets.id = care_tasks.petId
        WHERE pets.ownerId = :ownerId
          AND care_tasks.dueAt BETWEEN :fromMillis AND :toMillis
        ORDER BY care_tasks.dueAt ASC
        """
    )
    fun observeForOwnerBetween(
        ownerId: Long,
        fromMillis: Long,
        toMillis: Long
    ): Flow<List<CareTask>>

    /** Tasks that are still not done and whose due time has already passed. */
    @Query(
        """
        SELECT care_tasks.* FROM care_tasks
        INNER JOIN pets ON pets.id = care_tasks.petId
        WHERE pets.ownerId = :ownerId
          AND care_tasks.isCompleted = 0
          AND care_tasks.dueAt < :nowMillis
        ORDER BY care_tasks.dueAt ASC
        """
    )
    fun observeOverdueForOwner(ownerId: Long, nowMillis: Long): Flow<List<CareTask>>

    /** The tasks making up one routine, in the order they happen through the day. */
    @Query(
        """
        SELECT * FROM care_tasks
        WHERE routineId = :routineId
        ORDER BY timeOfDayMinutes IS NULL, timeOfDayMinutes ASC, title ASC
        """
    )
    fun observeByRoutine(routineId: Long): Flow<List<CareTask>>

    @Query("SELECT * FROM care_tasks WHERE id = :taskId")
    fun observeById(taskId: Long): Flow<CareTask?>

    @Query("SELECT * FROM care_tasks WHERE id = :taskId")
    suspend fun getById(taskId: Long): CareTask?

    /** Ticks a task off, or un-ticks it. */
    @Query(
        """
        UPDATE care_tasks SET isCompleted = :isCompleted, completedAt = :completedAt,
            updatedAt = :updatedAt, isPendingSync = 1
        WHERE id = :taskId
        """
    )
    suspend fun setCompleted(
        taskId: Long,
        isCompleted: Boolean,
        completedAt: Long?,
        updatedAt: Long
    )

    /** Un-ticks every task a pet completed within a time window. */
    @Query(
        """
        UPDATE care_tasks SET isCompleted = 0, completedAt = NULL, updatedAt = :updatedAt,
            isPendingSync = 1
        WHERE petId = :petId AND completedAt BETWEEN :fromMillis AND :toMillis
        """
    )
    suspend fun clearCompletionsForPet(
        petId: Long,
        fromMillis: Long,
        toMillis: Long,
        updatedAt: Long
    )

    /** Every task that can carry a reminder, with the pet and schedule details a notification needs. */
    @Query(
        """
        SELECT care_tasks.id AS taskId,
               care_tasks.title AS taskTitle,
               care_tasks.timeOfDayMinutes AS timeOfDayMinutes,
               pets.id AS petId,
               pets.name AS petName,
               care_routines.startDate AS routineStartDate,
               care_routines.repeatInterval AS repeatInterval,
               care_routines.repeatUnit AS repeatUnit
        FROM care_tasks
        INNER JOIN pets ON pets.id = care_tasks.petId
        INNER JOIN care_routines ON care_routines.id = care_tasks.routineId
        WHERE care_routines.isActive = 1
          AND care_tasks.timeOfDayMinutes IS NOT NULL
        ORDER BY care_tasks.id ASC
        """
    )
    fun observeReminders(): Flow<List<TaskReminder>>

    /** Count of outstanding tasks for a pet, for the badge on the pet list. */
    @Query("SELECT COUNT(*) FROM care_tasks WHERE petId = :petId AND isCompleted = 0")
    fun observePendingCountByPet(petId: Long): Flow<Int>
}
