package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.relation.PetWithCareTasks
import com.prash.petcare.data.local.relation.PetWithExpenses
import kotlinx.coroutines.flow.Flow

/** Database access for the `pets` table. */
@Dao
interface PetDao {

    @Insert
    suspend fun insert(pet: Pet): Long

    @Update
    suspend fun update(pet: Pet)

    /** Deletes a pet. The CASCADE rules declared on [com.prash.petcare.data.local.entity.CareTask]. */
    @Delete
    suspend fun delete(pet: Pet)

    /** Inserts rows that are new and overwrites ones already present, matching on primary key. */
    @Upsert
    suspend fun upsertAll(pets: List<Pet>)

    /** Observes every pet belonging to one owner, newest first, for the home screen list. */
    @Query("SELECT * FROM pets WHERE ownerId = :ownerId ORDER BY createdAt DESC")
    fun observeByOwner(ownerId: Long): Flow<List<Pet>>

    /** One snapshot of an owner's pets, for the sync layer. */
    @Query("SELECT * FROM pets WHERE ownerId = :ownerId")
    suspend fun getByOwner(ownerId: Long): List<Pet>

    /** The most recent change to any of this owner's pets, or null when they have none. */
    @Query("SELECT MAX(updatedAt) FROM pets WHERE ownerId = :ownerId")
    fun observeLatestUpdateForOwner(ownerId: Long): Flow<Long?>

    /** The ids of pets belonging to somebody else on this device. */
    @Query("SELECT id FROM pets WHERE ownerId != :ownerId")
    suspend fun getIdsNotOwnedBy(ownerId: Long): List<Long>

    /** Marks this owner's pets as uploaded, once Firestore has accepted them. */
    @Query(
        """
        UPDATE pets SET isPendingSync = 0
        WHERE ownerId = :ownerId AND isPendingSync = 1 AND updatedAt <= :syncedUpTo
        """
    )
    suspend fun clearPendingSync(ownerId: Long, syncedUpTo: Long)

    /** How many of this owner's pets are still waiting to be uploaded. */
    @Query("SELECT COUNT(*) FROM pets WHERE ownerId = :ownerId AND isPendingSync = 1")
    fun observePendingSyncCount(ownerId: Long): Flow<Int>

    @Query("SELECT * FROM pets WHERE id = :petId")
    fun observeById(petId: Long): Flow<Pet?>

    @Query("SELECT * FROM pets WHERE id = :petId")
    suspend fun getById(petId: Long): Pet?

    /** Drives the "you have no pets yet" empty state without loading the whole list. */
    @Query("SELECT COUNT(*) FROM pets WHERE ownerId = :ownerId")
    fun observeCountForOwner(ownerId: Long): Flow<Int>

    /** Observes a pet with its full task list — see [PetWithCareTasks]. */
    @Transaction
    @Query("SELECT * FROM pets WHERE id = :petId")
    fun observePetWithCareTasks(petId: Long): Flow<PetWithCareTasks?>

    /** Observes a pet with its full spending history — see [PetWithExpenses]. */
    @Transaction
    @Query("SELECT * FROM pets WHERE id = :petId")
    fun observePetWithExpenses(petId: Long): Flow<PetWithExpenses?>
}
