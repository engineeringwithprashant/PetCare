package com.prash.petcare.data.repository

import com.prash.petcare.data.local.dao.VaccinationRecordDao
import com.prash.petcare.data.local.entity.VaccinationRecord
import kotlinx.coroutines.flow.Flow

/** Supplies vaccination records to the ViewModel layer. */
class VaccinationRepository(
    private val vaccinationRecordDao: VaccinationRecordDao
) {

    /** Observes one pet's vaccination history, most recently given first. */
    fun observeRecordsForPet(petId: Long): Flow<List<VaccinationRecord>> =
        vaccinationRecordDao.observeByPet(petId)

    /** Saves a new record and returns the id SQLite assigned it. */
    suspend fun addRecord(record: VaccinationRecord): Long = vaccinationRecordDao.insert(record)

    /** Removes a record by id — used by the undo on the "recorded" Snackbar. */
    suspend fun deleteRecord(recordId: Long) = vaccinationRecordDao.deleteById(recordId)
}
