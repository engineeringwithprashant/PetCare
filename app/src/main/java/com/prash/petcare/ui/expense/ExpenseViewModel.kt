package com.prash.petcare.ui.expense

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.ExpenseCategory
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.repository.ExpenseRepository
import com.prash.petcare.data.repository.PetRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One row in the expenses screen's single list.
 *
 * The screen shows four different kinds of content — a total, a breakdown, section headings and the
 * expenses themselves — and they all scroll together as one page. Modelling them as one sealed
 * hierarchy lets a single RecyclerView render the lot, which is what keeps the expense list
 * recycling properly however many years of receipts build up. Stacking separate views inside a
 * scroll view instead would inflate every expense at once.
 *
 * Sealed, so [ExpenseAdapter]'s `when` branches are checked for exhaustiveness at compile time: a
 * new row type will not build until the adapter has been taught to draw it.
 */
sealed interface ExpenseListItem {

    /**
     * Stable identity for [androidx.recyclerview.widget.DiffUtil].
     *
     * Diffing compares by this rather than by list position, so a row that moves as expenses are
     * added is recognised as a move instead of a delete plus an insert. The values are namespaced
     * by kind (the string prefixes below) because an expense id and a category could otherwise
     * collide and make two unrelated rows look like the same one.
     */
    val itemId: String

    /** The headline figures: everything spent on this pet, and how many records make it up. */
    data class Summary(val total: Double, val expenseCount: Int) : ExpenseListItem {
        override val itemId: String get() = "summary"
    }

    /** A heading introducing a group of rows, e.g. "Where the money went". */
    data class SectionHeader(@param:StringRes val titleRes: Int) : ExpenseListItem {
        override val itemId: String get() = "header-$titleRes"
    }

    /**
     * One category's share of the total.
     *
     * @property amount Everything spent in this category.
     * @property share This category's fraction of the pet's total, between 0 and 1. Precomputed
     *   here rather than in the adapter so the row has no arithmetic to do at bind time, which is
     *   the hot path while scrolling.
     */
    data class CategoryTotal(
        val category: ExpenseCategory,
        val amount: Double,
        val share: Float
    ) : ExpenseListItem {
        override val itemId: String get() = "category-${category.name}"
    }

    /** A single recorded expense. */
    data class ExpenseRow(val expense: Expense) : ExpenseListItem {
        override val itemId: String get() = "expense-${expense.id}"
    }
}

/**
 * Everything the expenses screen displays.
 *
 * @property pet The animal whose spending this is, or null while loading. Only its name is used,
 *   for the app bar title.
 * @property items The whole screen as one list of rows — see [ExpenseListItem].
 * @property isLoading True until the first database results arrive, so the screen can tell "still
 *   loading" apart from "genuinely has no expenses".
 */
data class ExpensesUiState(
    val pet: Pet? = null,
    val items: List<ExpenseListItem> = emptyList(),
    val isLoading: Boolean = true
) {
    /** True when the "no expenses yet" message should replace the list. */
    val hasNoExpenses: Boolean get() = !isLoading && items.isEmpty()
}

/** A one-off occurrence the expenses screen reacts to exactly once. */
sealed interface ExpenseEvent {

    /**
     * An expense was deleted by a swipe, and the user should be offered an undo.
     *
     * The whole row travels with the event rather than just its id, because by the time the user
     * taps Undo it no longer exists in the database and there would be nothing left to look up.
     */
    data class Deleted(val expense: Expense) : ExpenseEvent
}

/**
 * Supplies the expenses screen with one pet's spending, its total, and the breakdown by category.
 *
 * Sits in the middle of the MVVM chain: the fragment observes [uiState] and forwards taps and
 * swipes here, and every database call goes out through [ExpenseRepository] rather than touching
 * the DAO directly. Nothing in this class references a Context, a View or a resource beyond string
 * ids, which is what lets it survive a rotation and be tested without an emulator.
 *
 * Recording and editing an expense belong to the form the user moves on to; what lives here is
 * reading the spending back and the swipe-to-delete shortcut on the list.
 *
 * @param petRepository Source of the pet, for the screen's title.
 * @param expenseRepository Source of the spending, and where a swipe-delete is written.
 * @param petId Which pet's expenses to show, passed in as a navigation argument.
 */
class ExpenseViewModel(
    petRepository: PetRepository,
    private val expenseRepository: ExpenseRepository,
    petId: Long
) : ViewModel() {

    /**
     * The pet, its expenses and its total as one combined state.
     *
     * Three sources are combined so that any of them changing — renaming the pet, adding a receipt —
     * refreshes the screen, and the values always describe the same moment rather than arriving
     * piecemeal.
     *
     * The total comes from the repository's SUM query rather than being added up from the list.
     * That is the point of [ExpenseRepository.observeTotalForPet]: SQLite adds the column without
     * the rows ever leaving the database, and it keeps working if the list is later paged or
     * filtered, when summing what happens to be loaded would quietly report the wrong figure.
     *
     * [stateIn] caches the latest value, so a rotation re-attaches to what is already there instead
     * of re-querying and flashing the empty state.
     */
    val uiState: StateFlow<ExpensesUiState> = combine(
        petRepository.observePet(petId),
        expenseRepository.observeExpensesForPet(petId),
        expenseRepository.observeTotalForPet(petId)
    ) { pet, expenses, total ->
        ExpensesUiState(
            pet = pet,
            items = buildItems(expenses, total),
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ExpensesUiState()
    )

    private val _events = Channel<ExpenseEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * Assembles the whole screen: the total, then the category breakdown, then the expenses.
     *
     * Building the rows here rather than in the fragment keeps the ordering and grouping rules
     * testable, and leaves the fragment with nothing to decide beyond which view to inflate.
     *
     * An empty list returns no rows at all — not even the total — so the screen shows its empty
     * state rather than a card proudly announcing a total of zero.
     */
    private fun buildItems(expenses: List<Expense>, total: Double): List<ExpenseListItem> {
        if (expenses.isEmpty()) return emptyList()

        return buildList {
            add(ExpenseListItem.Summary(total = total, expenseCount = expenses.size))

            add(ExpenseListItem.SectionHeader(R.string.expenses_breakdown_title))
            addAll(buildBreakdown(expenses))

            add(ExpenseListItem.SectionHeader(R.string.expenses_all_title))
            // Already ordered most recently incurred first by the DAO's ORDER BY, so there is
            // nothing to sort here — letting SQLite do it uses the index on `incurredOn`.
            addAll(expenses.map { ExpenseListItem.ExpenseRow(it) })
        }
    }

    /**
     * Works out how much went on each category, largest first.
     *
     * Only categories that were actually used appear. Listing all nine with zeroes beside most of
     * them would bury the two or three that matter, and the breakdown exists precisely to make
     * those stand out.
     *
     * Largest-first rather than enum order, because the question this answers is "where is the
     * money going?" — and that puts the answer at the top where it is seen without scrolling.
     *
     * The grouping is done in Kotlin over the list already loaded for the rows below, rather than
     * as a second `GROUP BY` query. The rows are in memory either way, so a second trip to the
     * database would re-read the same data the list is built from.
     *
     * The percentages are divided by the sum of *this list*, deliberately not by the headline total
     * from the SUM query. The two arrive on separate flows, so for a single emission after a new
     * expense is added one can be ahead of the other — and dividing by the stale figure would print
     * a share above 100%. Using the list's own sum keeps the breakdown internally consistent, and
     * the two figures agree again as soon as both queries have re-run.
     */
    private fun buildBreakdown(expenses: List<Expense>): List<ExpenseListItem.CategoryTotal> {
        val breakdownTotal = expenses.sumOf { it.amount }

        return expenses
            .groupBy { it.category }
            .map { (category, categoryExpenses) ->
                val amount = categoryExpenses.sumOf { it.amount }

                ExpenseListItem.CategoryTotal(
                    category = category,
                    amount = amount,
                    // Guarded against a zero total, which is reachable: every expense could have
                    // been recorded as 0.00, and dividing by it would yield NaN or Infinity and
                    // render as a nonsense percentage rather than failing anywhere it could be
                    // noticed.
                    share = if (breakdownTotal > 0.0) (amount / breakdownTotal).toFloat() else 0f
                )
            }
            .sortedByDescending { it.amount }
    }

    /**
     * Deletes an expense, as the swipe-left gesture does.
     *
     * No confirmation dialog here: the caller offers an undo instead, which is the app's convention
     * for swipe actions. A swipe is easy to trigger by accident, but a dialog on every one would
     * make the shortcut slower than opening the expense and using its delete button — which remains
     * the deliberate, screen-reader-reachable route.
     */
    fun deleteExpense(expense: Expense) {
        viewModelScope.launch {
            expenseRepository.deleteExpense(expense)

            _events.send(ExpenseEvent.Deleted(expense))
        }
    }

    /**
     * Puts back an expense removed by [deleteExpense], for the undo action.
     *
     * Re-inserting the original object restores its id as well as its contents, because Room only
     * generates a new primary key when the one it is given is zero. Keeping the id matters: the row
     * returns as itself rather than as a lookalike copy with a new identity, and anything holding
     * a reference to it still refers to the same record.
     */
    fun restoreExpense(expense: Expense) {
        viewModelScope.launch {
            expenseRepository.addExpense(expense)
        }
    }

    companion object {
        fun factory(petId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                ExpenseViewModel(
                    petRepository = application.petRepository,
                    expenseRepository = application.expenseRepository,
                    petId = petId
                )
            }
        }
    }
}
