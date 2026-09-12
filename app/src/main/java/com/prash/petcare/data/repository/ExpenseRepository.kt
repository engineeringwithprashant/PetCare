package com.prash.petcare.data.repository

import com.prash.petcare.data.local.dao.ExpenseDao
import com.prash.petcare.data.local.dao.SyncDeletionDao
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.ExpenseCategory
import com.prash.petcare.data.local.entity.SyncDeletion
import com.prash.petcare.data.local.entity.SyncEntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Supplies spending records to the ViewModel layer.
 *
 * See [UserRepository] for why this layer exists and how it fits the MVVM chain.
 *
 * As well as passing queries through, this repository normalises the SUM results: SQL returns
 * NULL for a total over zero rows, which would force every screen to handle a null. Mapping it
 * to `0.0` here means "no expenses yet" arrives at the UI as a number it can format directly.
 *
 * See [PetRepository] for why the sync timestamps and tombstones are maintained in this layer.
 *
 * @property expenseDao Injected through the constructor so tests can substitute a fake.
 * @property syncDeletionDao Where deletions are recorded for the sync layer to send on.
 */
class ExpenseRepository(
    private val expenseDao: ExpenseDao,
    private val syncDeletionDao: SyncDeletionDao
) {

    /** Observes a pet's spending history, most recent first. */
    fun observeExpensesForPet(petId: Long): Flow<List<Expense>> = expenseDao.observeByPet(petId)

    /** Observes a pet's spending in one category. */
    fun observeExpensesForPetByCategory(
        petId: Long,
        category: ExpenseCategory
    ): Flow<List<Expense>> = expenseDao.observeByPetAndCategory(petId, category)

    /** Observes a pet's spending inside a date range, e.g. one month of the report. */
    fun observeExpensesForPetBetween(
        petId: Long,
        fromMillis: Long,
        toMillis: Long
    ): Flow<List<Expense>> = expenseDao.observeByPetBetween(petId, fromMillis, toMillis)

    fun observeExpense(expenseId: Long): Flow<Expense?> = expenseDao.observeById(expenseId)

    /** Observes the total spent on one pet, reported as 0.0 when there are no expenses. */
    fun observeTotalForPet(petId: Long): Flow<Double> =
        expenseDao.observeTotalForPet(petId).map { it ?: 0.0 }

    /** Observes the total spent on one pet inside a date range, 0.0 when nothing matches. */
    fun observeTotalForPetBetween(
        petId: Long,
        fromMillis: Long,
        toMillis: Long
    ): Flow<Double> = expenseDao.observeTotalForPetBetween(petId, fromMillis, toMillis).map { it ?: 0.0 }

    /** Observes the total spent across all of an owner's pets, 0.0 when there are none. */
    fun observeTotalForOwner(ownerId: Long): Flow<Double> =
        expenseDao.observeTotalForOwner(ownerId).map { it ?: 0.0 }

    suspend fun getExpense(expenseId: Long): Expense? = expenseDao.getById(expenseId)

    suspend fun addExpense(expense: Expense): Long = expenseDao.insert(
        expense.copy(updatedAt = System.currentTimeMillis(), isPendingSync = true)
    )

    suspend fun updateExpense(expense: Expense) = expenseDao.update(
        expense.copy(updatedAt = System.currentTimeMillis(), isPendingSync = true)
    )

    /** Deletes an expense, recording a tombstone first so the deletion can be synced. */
    suspend fun deleteExpense(expense: Expense) {
        syncDeletionDao.insert(
            SyncDeletion(entityType = SyncEntityType.EXPENSE, entityId = expense.id)
        )
        expenseDao.delete(expense)
    }

    // -------------------------------------------------------------------------------------------
    // Used by the sync layer — see the equivalent section of PetRepository.
    // -------------------------------------------------------------------------------------------

    /** Every expense belonging to an owner, as a one-off read. */
    suspend fun getExpensesForOwner(ownerId: Long): List<Expense> = expenseDao.getByOwner(ownerId)

    /** The sync layer's change signal — see [PetDao.observeLatestUpdateForOwner]. */
    fun observeLatestExpenseUpdate(ownerId: Long): Flow<Long?> =
        expenseDao.observeLatestUpdateForOwner(ownerId)

    /** Expense ids another account on this device owns — see [PetDao.getIdsNotOwnedBy]. */
    suspend fun getExpenseIdsNotOwnedBy(ownerId: Long): Set<Long> =
        expenseDao.getIdsNotOwnedBy(ownerId).toSet()

    /** Marks expenses as uploaded once Firestore has them — see [PetDao.clearPendingSync]. */
    suspend fun clearPendingExpenseSync(ownerId: Long, syncedUpTo: Long) =
        expenseDao.clearPendingSync(ownerId, syncedUpTo)

    /** How many expenses are still waiting to be uploaded, for the sync indicator. */
    fun observePendingExpenseCount(ownerId: Long): Flow<Int> =
        expenseDao.observePendingSyncCount(ownerId)

    /** Writes expenses pulled from Firestore into Room, keeping the timestamps they arrived with. */
    suspend fun upsertExpensesFromSync(expenses: List<Expense>) = expenseDao.upsertAll(expenses)
}
