package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.prash.petcare.data.local.entity.VaccinationRecord
import kotlinx.coroutines.flow.Flow

/** Database access for the `vaccination_records` table. */
@Dao
interface VaccinationRecordDao {

    @Insert
    suspend fun insert(record: VaccinationRecord): Long

    /** Removes one record by its id. */
    @Query("DELETE FROM vaccination_records WHERE id = :recordId")
    suspend fun deleteById(recordId: Long)

    /** Observes one pet's vaccination history, most recently given first. */
    @Query(
        """
        SELECT * FROM vaccination_records
        WHERE petId = :petId
        ORDER BY dateGiven DESC, id DESC
        """
    )
    fun observeByPet(petId: Long): Flow<List<VaccinationRecord>>
}
