package com.prash.petcare.data.repository

import com.prash.petcare.data.local.dao.PetDao
import com.prash.petcare.data.local.dao.SyncDeletionDao
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.SyncDeletion
import com.prash.petcare.data.local.entity.SyncEntityType
import com.prash.petcare.data.local.relation.PetWithCareTasks
import com.prash.petcare.data.local.relation.PetWithExpenses
import kotlinx.coroutines.flow.Flow

/**
 * Supplies pet data to the ViewModel layer.
 *
 * See [UserRepository] for why this layer exists and how it fits the MVVM chain.
 *
 * **This is also where a row's sync bookkeeping happens**, and deliberately so. Every write to the
 * `pets` table passes through here, which makes it the one place that can guarantee two things
 * without any screen having to remember them: that [Pet.updatedAt] moves whenever the row changes,
 * and that deleting a pet leaves a [SyncDeletion] behind saying so. Putting that in the ViewModels
 * would mean every future screen that saves a pet is a chance to forget it.
 *
 * @property petDao Injected through the constructor so tests can substitute a fake.
 * @property syncDeletionDao Where deletions are recorded for the sync layer to send on.
 */
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

    /**
     * Adds a pet and returns its new id.
     *
     * The timestamp is stamped here rather than trusted from the caller so that a row's
     * "last changed" always means the moment it reached the database. A screen could have built
     * the object minutes earlier while the user was still typing.
     *
     * The row is marked pending in the same breath. Marking it here, as part of the write itself,
     * is what guarantees a change can never exist without being queued for upload — there is no
     * moment in between, and no path to the database that could skip it.
     */
    suspend fun addPet(pet: Pet): Long = petDao.insert(
        pet.copy(updatedAt = System.currentTimeMillis(), isPendingSync = true)
    )

    suspend fun updatePet(pet: Pet) = petDao.update(
        pet.copy(updatedAt = System.currentTimeMillis(), isPendingSync = true)
    )

    /**
     * Deletes a pet along with its tasks, routines and expenses.
     *
     * The cascade happens inside SQLite through the foreign keys declared on those entities, so
     * there is nothing to delete by hand here — and no risk of leaving orphaned rows behind if
     * this method were ever changed.
     *
     * Only the pet gets a tombstone, and the cascaded children deliberately do not. Recording one
     * per child would mean reading every task, routine and expense first purely to write a row
     * saying it is gone; instead the sync layer reproduces the cascade at the other end, removing
     * a deleted pet's documents the same way SQLite removes its rows. The tombstone is written
     * *before* the delete, because afterwards there is no pet left to take the id from.
     */
    suspend fun deletePet(pet: Pet) {
        syncDeletionDao.insert(SyncDeletion(entityType = SyncEntityType.PET, entityId = pet.id))
        petDao.delete(pet)
    }

    // -------------------------------------------------------------------------------------------
    // Used by the sync layer
    //
    // These exist so com.prash.petcare.data.sync.SyncManager can reach Room through a repository
    // like everything else in the app, rather than reaching past this layer to a DAO.
    // -------------------------------------------------------------------------------------------

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

    /**
     * Writes pets pulled from Firestore into Room.
     *
     * No timestamp is stamped here, unlike the methods above: these rows arrive carrying the
     * [Pet.updatedAt] they were saved with elsewhere, and overwriting it with "now" would destroy
     * the very information last-write-wins depends on.
     */
    suspend fun upsertPetsFromSync(pets: List<Pet>) = petDao.upsertAll(pets)
}
