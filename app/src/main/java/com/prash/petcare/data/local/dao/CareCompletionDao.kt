package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.prash.petcare.data.local.entity.CareCompletion
import kotlinx.coroutines.flow.Flow

/**
 * Database access for the `care_completions` table — the history of which task was done on which day.
 *
 * See [UserDao] for the reasoning behind the [Flow] versus `suspend` return types used here, and
 * [CareCompletion] for why this history is kept apart from the tasks themselves.
 */
@Dao
interface CareCompletionDao {

    /**
     * Records that a task was done on a day.
     *
     * REPLACE because of the unique (taskId, dayStart) index: ticking, un-ticking and re-ticking the
     * same task on the same day should leave exactly one row carrying the latest moment, not throw.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(completion: CareCompletion)

    /**
     * Records several completions, keeping any row already there.
     *
     * IGNORE rather than REPLACE: this is used for ticks arriving from the sync, which may describe a
     * day this device already recorded itself — the local row is just as true and there is no reason
     * to rewrite it.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfAbsent(completions: List<CareCompletion>)

    /** Removes a task's completion for one day — the history side of un-ticking it. */
    @Query("DELETE FROM care_completions WHERE taskId = :taskId AND dayStart = :dayStart")
    suspend fun deleteForTaskOnDay(taskId: Long, dayStart: Long)

    /**
     * Removes every completion a pet recorded within a time window, matching
     * [CareTaskDao.clearCompletionsForPet] so a reset day leaves no history behind either.
     */
    @Query(
        """
        DELETE FROM care_completions
        WHERE petId = :petId AND completedAt BETWEEN :fromMillis AND :toMillis
        """
    )
    suspend fun deleteForPetBetween(petId: Long, fromMillis: Long, toMillis: Long)

    /**
     * Observes an owner's completions from a moment onwards.
     *
     * Joined through `pets` to scope it to the signed-in account, the same way the task queries are —
     * another account on the same device has its own pets and so its own rows. A Flow, so the
     * dashboard's streak redraws the instant a task is ticked on any screen.
     */
    @Query(
        """
        SELECT care_completions.* FROM care_completions
        INNER JOIN pets ON pets.id = care_completions.petId
        WHERE pets.ownerId = :ownerId AND care_completions.completedAt >= :fromMillis
        """
    )
    fun observeForOwnerSince(ownerId: Long, fromMillis: Long): Flow<List<CareCompletion>>
}
