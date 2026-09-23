package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.prash.petcare.data.local.entity.VaccinationRecord
import kotlinx.coroutines.flow.Flow

/**
 * Database access for the `vaccination_records` table.
 *
 * See [UserDao] for the reasoning behind the [Flow] versus `suspend` return types used here: a Flow
 * query re-runs and re-emits by itself whenever the table changes, which is what makes a record
 * saved on the "Record a vaccination" screen appear in the pet's history the moment the user gets
 * back to it — without that screen having to tell this one anything.
 *
 * Small on purpose. The history is read-only in this pass, so there is no update method;
 * [deleteById] exists because a record inserted and immediately regretted has to be removable for
 * the undo on the confirmation Snackbar to mean anything.
 */
@Dao
interface VaccinationRecordDao {

    @Insert
    suspend fun insert(record: VaccinationRecord): Long

    /**
     * Removes one record by its id.
     *
     * A `@Query` rather than Room's `@Delete`, which needs the whole entity. The only caller is the
     * undo on the "recorded" Snackbar, and all that has to hand is the id the insert returned —
     * fetching the row back purely to hand it straight to a delete would be a round trip to learn
     * something the caller already knows.
     */
    @Query("DELETE FROM vaccination_records WHERE id = :recordId")
    suspend fun deleteById(recordId: Long)

    /**
     * Observes one pet's vaccination history, most recently given first.
     *
     * The secondary sort on `id` is not decoration: two shots given on the same day — a booster and
     * a deworming dose at one appointment — would otherwise come back in whatever order SQLite
     * happened to produce, and the list could reshuffle itself between two draws of the same data.
     * Falling back to the id makes the order stable, and puts the more recently entered of the two
     * on top, which is the one the user just typed.
     */
    @Query(
        """
        SELECT * FROM vaccination_records
        WHERE petId = :petId
        ORDER BY dateGiven DESC, id DESC
        """
    )
    fun observeByPet(petId: Long): Flow<List<VaccinationRecord>>
}
