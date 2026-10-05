package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.prash.petcare.data.local.entity.CareCompletion
import kotlinx.coroutines.flow.Flow

/** Database access for the `care_completions` table — the history of which task was done on which day. */
@Dao
interface CareCompletionDao {

    /** Records that a task was done on a day. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(completion: CareCompletion)

    /** Records several completions, keeping any row already there. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfAbsent(completions: List<CareCompletion>)

    /** Removes a task's completion for one day — the history side of un-ticking it. */
    @Query("DELETE FROM care_completions WHERE taskId = :taskId AND dayStart = :dayStart")
    suspend fun deleteForTaskOnDay(taskId: Long, dayStart: Long)

    /** Removes every completion a pet recorded within a time window, matching [CareTaskDao.clearCompletionsForPet]. */
    @Query(
        """
        DELETE FROM care_completions
        WHERE petId = :petId AND completedAt BETWEEN :fromMillis AND :toMillis
        """
    )
    suspend fun deleteForPetBetween(petId: Long, fromMillis: Long, toMillis: Long)

    /** Observes an owner's completions from a moment onwards. */
    @Query(
        """
        SELECT care_completions.* FROM care_completions
        INNER JOIN pets ON pets.id = care_completions.petId
        WHERE pets.ownerId = :ownerId AND care_completions.completedAt >= :fromMillis
        """
    )
    fun observeForOwnerSince(ownerId: Long, fromMillis: Long): Flow<List<CareCompletion>>
}
