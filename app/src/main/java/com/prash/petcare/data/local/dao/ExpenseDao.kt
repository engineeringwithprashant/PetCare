package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.ExpenseCategory
import kotlinx.coroutines.flow.Flow

/**
 * Database access for the `expenses` table.
 *
 * As well as ordinary CRUD, this DAO exposes SUM queries so spending totals are calculated by
 * SQLite rather than by loading every row into memory and adding them up in Kotlin.
 *
 * See [UserDao] for the reasoning behind the [Flow] versus `suspend` return types used here.
 */
@Dao
interface ExpenseDao {

    @Insert
    suspend fun insert(expense: Expense): Long

    @Update
    suspend fun update(expense: Expense)

    @Delete
    suspend fun delete(expense: Expense)

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

    /**
     * Total spent on one pet.
     *
     * The return type is nullable because SQL's `SUM` yields NULL, not 0, when no rows match.
     * Callers coerce that to zero; the repository does it so the UI never has to.
     */
    @Query("SELECT SUM(amount) FROM expenses WHERE petId = :petId")
    fun observeTotalForPet(petId: Long): Flow<Double?>

    /** Total spent on one pet within a date range — the figure shown on the monthly report. */
    @Query("SELECT SUM(amount) FROM expenses WHERE petId = :petId AND incurredOn BETWEEN :fromMillis AND :toMillis")
    fun observeTotalForPetBetween(petId: Long, fromMillis: Long, toMillis: Long): Flow<Double?>

    /**
     * Total spent across every pet an owner has.
     *
     * Joins through `pets` because an expense records which animal it was for, not which
     * account — the owner is reached via the pet, keeping that fact stored in exactly one place.
     */
    @Query(
        """
        SELECT SUM(expenses.amount) FROM expenses
        INNER JOIN pets ON pets.id = expenses.petId
        WHERE pets.ownerId = :ownerId
        """
    )
    fun observeTotalForOwner(ownerId: Long): Flow<Double?>
}
