package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.ExpenseCategory
import kotlinx.coroutines.flow.Flow

/** Database access for the `expenses` table. */
@Dao
interface ExpenseDao {

    @Insert
    suspend fun insert(expense: Expense): Long

    @Update
    suspend fun update(expense: Expense)

    @Delete
    suspend fun delete(expense: Expense)

    /** Applies expenses pulled from Firestore — see [PetDao.upsertAll] for why [Upsert]. */
    @Upsert
    suspend fun upsertAll(expenses: List<Expense>)

    /** One snapshot of every expense belonging to an owner, for the sync layer. */
    @Query(
        """
        SELECT expenses.* FROM expenses
        INNER JOIN pets ON pets.id = expenses.petId
        WHERE pets.ownerId = :ownerId
        """
    )
    suspend fun getByOwner(ownerId: Long): List<Expense>

    /** The sync layer's change signal for expenses — see [PetDao.observeLatestUpdateForOwner]. */
    @Query(
        """
        SELECT MAX(expenses.updatedAt) FROM expenses
        INNER JOIN pets ON pets.id = expenses.petId
        WHERE pets.ownerId = :ownerId
        """
    )
    fun observeLatestUpdateForOwner(ownerId: Long): Flow<Long?>

    /** Expenses belonging to another account on this device — see [PetDao.getIdsNotOwnedBy]. */
    @Query(
        """
        SELECT expenses.id FROM expenses
        INNER JOIN pets ON pets.id = expenses.petId
        WHERE pets.ownerId != :ownerId
        """
    )
    suspend fun getIdsNotOwnedBy(ownerId: Long): List<Long>

    /** Marks this owner's expenses as uploaded — see [PetDao.clearPendingSync]. */
    @Query(
        """
        UPDATE expenses SET isPendingSync = 0
        WHERE isPendingSync = 1 AND updatedAt <= :syncedUpTo
          AND petId IN (SELECT id FROM pets WHERE ownerId = :ownerId)
        """
    )
    suspend fun clearPendingSync(ownerId: Long, syncedUpTo: Long)

    /** How many of this owner's expenses are still waiting to be uploaded. */
    @Query(
        """
        SELECT COUNT(*) FROM expenses
        INNER JOIN pets ON pets.id = expenses.petId
        WHERE pets.ownerId = :ownerId AND expenses.isPendingSync = 1
        """
    )
    fun observePendingSyncCount(ownerId: Long): Flow<Int>

    /** Observes a pet's spending history, most recently incurred first. */
    @Query("SELECT * FROM expenses WHERE petId = :petId ORDER BY incurredOn DESC")
    fun observeByPet(petId: Long): Flow<List<Expense>>

    /** Observes a pet's spending filtered to one category, for the breakdown screen. */
    @Query("SELECT * FROM expenses WHERE petId = :petId AND category = :category ORDER BY incurredOn DESC")
    fun observeByPetAndCategory(petId: Long, category: ExpenseCategory): Flow<List<Expense>>

    /** Expenses for a pet within a date range, e.g. one month of the spending report. */
    @Query(
        """
        SELECT * FROM expenses
        WHERE petId = :petId AND incurredOn BETWEEN :fromMillis AND :toMillis
        ORDER BY incurredOn DESC
        """
    )
    fun observeByPetBetween(petId: Long, fromMillis: Long, toMillis: Long): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE id = :expenseId")
    fun observeById(expenseId: Long): Flow<Expense?>

    @Query("SELECT * FROM expenses WHERE id = :expenseId")
    suspend fun getById(expenseId: Long): Expense?

    /** Total spent on one pet. */
    @Query("SELECT SUM(amount) FROM expenses WHERE petId = :petId")
    fun observeTotalForPet(petId: Long): Flow<Double?>

    /** Total spent on one pet within a date range — the figure shown on the monthly report. */
    @Query("SELECT SUM(amount) FROM expenses WHERE petId = :petId AND incurredOn BETWEEN :fromMillis AND :toMillis")
    fun observeTotalForPetBetween(petId: Long, fromMillis: Long, toMillis: Long): Flow<Double?>

    /** Total spent across every pet an owner has. */
    @Query(
        """
        SELECT SUM(expenses.amount) FROM expenses
        INNER JOIN pets ON pets.id = expenses.petId
        WHERE pets.ownerId = :ownerId
        """
    )
    fun observeTotalForOwner(ownerId: Long): Flow<Double?>
}
