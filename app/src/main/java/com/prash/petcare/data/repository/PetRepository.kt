package com.prash.petcare.data.repository

import com.prash.petcare.data.local.dao.PetDao
import com.prash.petcare.data.local.dao.SyncDeletionDao
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.SyncDeletion
import com.prash.petcare.data.local.entity.SyncEntityType
import com.prash.petcare.data.local.relation.PetWithCareTasks
import com.prash.petcare.data.local.relation.PetWithExpenses
import kotlinx.coroutines.flow.Flow

/** Supplies pet data to the ViewModel layer. */
class PetRepository(
    private val petDao: PetDao,
    private val syncDeletionDao: SyncDeletionDao
) {

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

    /** Adds a pet and returns its new id. */
    suspend fun addPet(pet: Pet): Long = petDao.insert(
        pet.copy(updatedAt = System.currentTimeMillis(), isPendingSync = true)
    )

    suspend fun updatePet(pet: Pet) = petDao.update(
        pet.copy(updatedAt = System.currentTimeMillis(), isPendingSync = true)
    )

    /** Deletes a pet along with its tasks, routines and expenses. */
    suspend fun deletePet(pet: Pet) {
        syncDeletionDao.insert(SyncDeletion(entityType = SyncEntityType.PET, entityId = pet.id))
        petDao.delete(pet)
    }

    // Used by the sync layer.

    /** Every pet an owner has, as a one-off read. */
    suspend fun getPetsForOwner(ownerId: Long): List<Pet> = petDao.getByOwner(ownerId)

    /** The sync layer's change signal — see [PetDao.observeLatestUpdateForOwner]. */
    fun observeLatestPetUpdate(ownerId: Long): Flow<Long?> =
        petDao.observeLatestUpdateForOwner(ownerId)

    /** Pet ids another account on this device owns — see [PetDao.getIdsNotOwnedBy]. */
    suspend fun getPetIdsNotOwnedBy(ownerId: Long): Set<Long> =
        petDao.getIdsNotOwnedBy(ownerId).toSet()

    /** Marks pets as uploaded once Firestore has them — see [PetDao.clearPendingSync]. */
    suspend fun clearPendingPetSync(ownerId: Long, syncedUpTo: Long) =
        petDao.clearPendingSync(ownerId, syncedUpTo)

    /** How many pets are still waiting to be uploaded, for the sync indicator. */
    fun observePendingPetCount(ownerId: Long): Flow<Int> =
        petDao.observePendingSyncCount(ownerId)

    /** Writes pets pulled from Firestore into Room. */
    suspend fun upsertPetsFromSync(pets: List<Pet>) = petDao.upsertAll(pets)
}
