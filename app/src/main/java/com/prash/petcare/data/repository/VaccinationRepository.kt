package com.prash.petcare.data.repository

import com.prash.petcare.data.local.dao.VaccinationRecordDao
import com.prash.petcare.data.local.entity.VaccinationRecord
import kotlinx.coroutines.flow.Flow

/**
 * Supplies vaccination records to the ViewModel layer.
 *
 * See [UserRepository] for why this layer exists and how it fits the MVVM chain — in short, it is
 * the only layer allowed to touch a DAO, so a ViewModel asks for "this pet's vaccinations" without
 * knowing that a SQL table exists at all. That boundary is what would let the history later come
 * from a vet's API as well as from Room without a single screen changing.
 *
 * Thinner than most of the app's repositories because this table is read-only in the UI: records are
 * written once from the record screen, read on the pet detail card, and otherwise left alone.
 *
 * It maintains **no** `updatedAt` stamp or deletion tombstone, unlike [PetRepository] and its
 * neighbours. That is not an omission — see [VaccinationRecord] for why this table is deliberately
 * outside the Firestore sync in this pass.
 *
 * @property vaccinationRecordDao Injected through the constructor so tests can substitute a fake.
 */
class VaccinationRepository(
    private val vaccinationRecordDao: VaccinationRecordDao
) {

    /** Observes one pet's vaccination history, most recently given first. */
    fun observeRecordsForPet(petId: Long): Flow<List<VaccinationRecord>> =
        vaccinationRecordDao.observeByPet(petId)

    /**
     * Saves a new record and returns the id SQLite assigned it.
     *
     * The id is handed back rather than swallowed because the screen that saves offers an undo, and
     * undoing needs to be able to name the exact row that was just inserted.
     */
    suspend fun addRecord(record: VaccinationRecord): Long = vaccinationRecordDao.insert(record)

    /** Removes a record by id — used by the undo on the "recorded" Snackbar. */
    suspend fun deleteRecord(recordId: Long) = vaccinationRecordDao.deleteById(recordId)
}
