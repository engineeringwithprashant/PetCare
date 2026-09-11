package com.prash.petcare.data.repository

import com.prash.petcare.data.local.dao.PetDao
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.relation.PetWithCareTasks
import com.prash.petcare.data.local.relation.PetWithExpenses
import kotlinx.coroutines.flow.Flow

/**
 * Supplies pet data to the ViewModel layer.
 *
 * See [UserRepository] for why this layer exists and how it fits the MVVM chain.
 *
 * @property petDao Injected through the constructor so tests can substitute a fake.
 */
class PetRepository(private val petDao: PetDao) {

    /** Observes every pet belonging to one owner — the home screen's list. */
    fun observePetsForOwner(ownerId: Long): Flow<List<Pet>> = petDao.observeByOwner(ownerId)

    /** Observes how many pets an owner has, for showing the empty state. */
    fun observePetCountForOwner(ownerId: Long): Flow<Int> = petDao.observeCountForOwner(ownerId)

    fun observePet(petId: Long): Flow<Pet?> = petDao.observeById(petId)

    /** Observes a pet alongside its care tasks, for the pet detail screen. */
    fun observePetWithCareTasks(petId: Long): Flow<PetWithCareTasks?> =
        petDao.observePetWithCareTasks(petId)

    /** Observes a pet alongside its expenses, for the spending screen. */
    fun observePetWithExpenses(petId: Long): Flow<PetWithExpenses?> =
        petDao.observePetWithExpenses(petId)

    suspend fun getPet(petId: Long): Pet? = petDao.getById(petId)

    suspend fun addPet(pet: Pet): Long = petDao.insert(pet)

    suspend fun updatePet(pet: Pet) = petDao.update(pet)

    /**
     * Deletes a pet along with its tasks, routines and expenses.
     *
     * The cascade happens inside SQLite through the foreign keys declared on those entities, so
     * there is nothing to delete by hand here — and no risk of leaving orphaned rows behind if
     * this method were ever changed.
     */
    suspend fun deletePet(pet: Pet) = petDao.delete(pet)
}
