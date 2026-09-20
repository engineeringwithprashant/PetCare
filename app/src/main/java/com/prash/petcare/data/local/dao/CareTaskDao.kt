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

    /**
     * The ids of every task in a routine.
     *
     * Read before [deleteByRoutine] runs, so each of those rows can have a tombstone recorded for
     * it — once they are gone there is nothing left to say what was deleted.
     */
    @Query("SELECT id FROM care_tasks WHERE routineId = :routineId")
    suspend fun getIdsByRoutine(routineId: Long): List<Long>

    /**
     * Removes every task belonging to one routine.
     *
     * Needed because the foreign key on `routineId` uses SET_NULL rather than CASCADE, so deleting
     * a routine would otherwise leave its tasks behind with a null link. Under the current model
     * that makes them unreachable: a task's days come from its routine, so one with no routine can
     * never appear on a checklist again — it would just sit in the database invisibly.
     *
     * A single DELETE rather than removing rows one at a time, so the work is one statement.
     */
    @Query("DELETE FROM care_tasks WHERE routineId = :routineId")
    suspend fun deleteByRoutine(routineId: Long)

    /** Observes every task for one pet, soonest due first. */
    @Query("SELECT * FROM care_tasks WHERE petId = :petId ORDER BY dueAt ASC")
    fun observeByPet(petId: Long): Flow<List<CareTask>>

    /** The outstanding to-do list for one pet. `isCompleted = 0` is SQLite's false. */
    @Query("SELECT * FROM care_tasks WHERE petId = :petId AND isCompleted = 0 ORDER BY dueAt ASC")
    fun observePendingByPet(petId: Long): Flow<List<CareTask>>

    /**
     * Every task across all of an owner's pets, whatever its date or state.
     *
     * **Why an unfiltered query, when the two below filter by date in SQL.** A repeating task has one
     * row, not one row per occurrence: its `dueAt` is the date it was first set for, and whether it
     * falls due *today* is worked out from the routine that owns it — see
     * [com.prash.petcare.util.CareSchedule.tasksDueOn]. So a task repeating daily since last week has
     * a `dueAt` well outside today and is still due today, and a WHERE clause on the date would
     * quietly drop it. Anything asking "what is due today?" has to project the schedule in Kotlin,
     * which means starting from all of the rows.
     *
     * The windowed queries below are not wrong — they answer a different question, "what was set for
     * this stretch of time?", which is what a diary of upcoming one-offs needs.
     *
     * The Flow twin of [getByOwner], which the sync layer uses for the same rows as a one-off read.
     */
    @Query(
        """
        SELECT care_tasks.* FROM care_tasks
        INNER JOIN pets ON pets.id = care_tasks.petId
        WHERE pets.ownerId = :ownerId
        ORDER BY care_tasks.dueAt ASC
        """
    )
    fun observeByOwner(ownerId: Long): Flow<List<CareTask>>

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
     *
     * [updatedAt] moves with them so the sync layer sees the tick — see
     * [CareRoutineDao.setActive] for why it belongs in this statement rather than a second one.
     * It is a separate parameter from [completedAt] because un-ticking sets that to null while
     * still being a change that happened just now.
     */
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

    /**
     * Un-ticks every task a pet completed within a time window.
     *
     * Used to reset a day's checklist in one go. A single UPDATE rather than a write per task, so
     * the whole reset either happens or does not — clearing half a checklist would be worse than
     * clearing none of it.
     *
     * Matching on `completedAt` rather than `isCompleted` is what scopes this to the chosen day: a
     * task ticked off last week stays as it was.
     */
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

    /**
     * Every task that can carry a reminder, with the pet and schedule details a notification needs.
     *
     * Returns a flat [TaskReminder] projection rather than entities: Room matches the column
     * aliases below to that class's properties. See [TaskReminder] for why the reminder code reads
     * eight columns instead of three whole rows.
     *
     * Two conditions decide what can be reminded about, and both are applied in SQL so the work
     * never leaves the database:
     *  - the routine must be active, because a paused routine should go quiet rather than keep
     *    buzzing;
     *  - the task must have a time of day, because a reminder with no time has no moment to fire
     *    at. This is also what lets [TaskReminder.timeOfDayMinutes] be non-null.
     *
     * The INNER JOIN on `care_routines` quietly enforces a third: a task orphaned by its routine
     * being deleted has a null `routineId`, matches nothing, and so drops out — which is right,
     * since without a routine there is no schedule to say when it next comes round.
     *
     * Exposed as a [Flow] rather than a one-shot read so the scheduler can re-plan by itself
     * whenever the underlying data changes. Room re-runs the query on any write to the three tables
     * involved, which means adding a task, editing its time, pausing a routine, renaming a pet or
     * deleting any of them all reach the alarms with nothing having to remember to call anything.
     * A caller wanting a single snapshot takes the first emission — see
     * [com.prash.petcare.data.repository.CareTaskRepository.getReminders].
     */
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
