package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.prash.petcare.data.local.entity.CareTask
import kotlinx.coroutines.flow.Flow

/**
 * Database access for the `care_tasks` table.
 *
 * This is the busiest DAO in the app: the home screen, the pet detail screen and the reminder
 * logic all read from it. See [UserDao] for the reasoning behind the [Flow] versus `suspend`
 * return types.
 */
@Dao
interface CareTaskDao {

    @Insert
    suspend fun insert(task: CareTask): Long

    /**
     * Inserts many tasks at once — used when a [com.prash.petcare.data.local.entity.CareRoutine]
     * generates its next batch of occurrences. Room wraps a list insert in a single transaction,
     * so this is both faster than looping and all-or-nothing.
     */
    @Insert
    suspend fun insertAll(tasks: List<CareTask>): List<Long>

    @Update
    suspend fun update(task: CareTask)

    @Delete
    suspend fun delete(task: CareTask)

    /** Observes every task for one pet, soonest due first. */
    @Query("SELECT * FROM care_tasks WHERE petId = :petId ORDER BY dueAt ASC")
    fun observeByPet(petId: Long): Flow<List<CareTask>>

    /** The outstanding to-do list for one pet. `isCompleted = 0` is SQLite's false. */
    @Query("SELECT * FROM care_tasks WHERE petId = :petId AND isCompleted = 0 ORDER BY dueAt ASC")
    fun observePendingByPet(petId: Long): Flow<List<CareTask>>

    /**
     * Every outstanding task across all of an owner's pets, due within a time window.
     *
     * The home screen calls this with the start and end of today to build its "due today" list;
     * passing a window rather than hard-coding "today" in SQL keeps the date arithmetic in
     * Kotlin, where it can respect the device's time zone.
     */
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

    /**
     * Every task for an owner inside a time window, completed or not.
     *
     * Separate from [observePendingForOwnerBetween] because the home screen's daily summary needs
     * to report progress ("2 of 5 done"), which requires the completed tasks as well — filtering
     * them out in SQL would make the total impossible to calculate.
     */
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

    /**
     * Tasks that are still not done and whose due time has already passed.
     *
     * Feeds the overdue banner, which is why it is a separate query from the "due today" one —
     * an overdue task should keep showing up long after its own day has ended.
     */
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

    /**
     * The tasks making up one routine, in the order they happen through the day.
     *
     * Ordered by time of day rather than by creation, because a routine reads as a running order —
     * morning feed before evening walk — regardless of which the user typed in first. Tasks with no
     * time recorded sort last, which is what `IS NULL` ordering achieves here.
     */
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

    /**
     * Ticks a task off, or un-ticks it.
     *
     * Both columns move together in one statement so they can never disagree: a completed task
     * always has a timestamp, and an outstanding one always has null. The caller passes null as
     * [completedAt] when un-ticking.
     */
    @Query("UPDATE care_tasks SET isCompleted = :isCompleted, completedAt = :completedAt WHERE id = :taskId")
    suspend fun setCompleted(taskId: Long, isCompleted: Boolean, completedAt: Long?)

    /** Count of outstanding tasks for a pet, for the badge on the pet list. */
    @Query("SELECT COUNT(*) FROM care_tasks WHERE petId = :petId AND isCompleted = 0")
    fun observePendingCountByPet(petId: Long): Flow<Int>
}
