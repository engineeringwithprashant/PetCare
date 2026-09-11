package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.relation.PetWithCareTasks
import com.prash.petcare.data.local.relation.PetWithExpenses
import kotlinx.coroutines.flow.Flow

/**
 * Database access for the `pets` table.
 *
 * See [UserDao] for the reasoning behind the [Flow] versus `suspend` return types used here.
 */
@Dao
interface PetDao {

    @Insert
    suspend fun insert(pet: Pet): Long

    @Update
    suspend fun update(pet: Pet)

    /**
     * Deletes a pet. The CASCADE rules declared on [com.prash.petcare.data.local.entity.CareTask],
     * [com.prash.petcare.data.local.entity.CareRoutine] and
     * [com.prash.petcare.data.local.entity.Expense] mean SQLite removes that pet's tasks,
     * routines and expenses too — no manual clean-up needed here.
     */
    @Delete
    suspend fun delete(pet: Pet)

    /** Observes every pet belonging to one owner, newest first, for the home screen list. */
    @Query("SELECT * FROM pets WHERE ownerId = :ownerId ORDER BY createdAt DESC")
    fun observeByOwner(ownerId: Long): Flow<List<Pet>>

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
