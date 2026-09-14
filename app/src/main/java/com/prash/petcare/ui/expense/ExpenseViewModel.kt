package com.prash.petcare.ui.expense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.repository.ExpenseRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.util.CategorySpend
import com.prash.petcare.util.MonthSummary
import com.prash.petcare.util.MonthlySpending
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One row in the Money tab's single scrolling list.
 *
 * The screen shows four kinds of content — the dark total card, the category legend, the "ALL
 * EXPENSES" heading and the expense cards — and they scroll together as one page. Modelling them as
 * one sealed hierarchy lets a single RecyclerView draw the lot, which keeps the expense cards
 * recycling however many years of receipts build up.
 *
 * Sealed, so [ExpenseAdapter]'s `when` branches are checked at compile time: a new row type will not
 * build until the adapter can draw it.
 */
sealed interface ExpenseListItem {

    /**
     * Stable identity for DiffUtil, namespaced by kind so an expense id and a category can never
     * collide and make two unrelated rows look like the same one.
     */
    val itemId: String

    /** The dark total card: this month's total, its stacked bar, and the caption beneath. */
    data class Summary(val month: MonthSummary) : ExpenseListItem {
        override val itemId: String get() = "summary"
    }

    /** One legend row: a category's dot, name, share of the month, and amount. */
    data class CategoryTotal(val spend: CategorySpend) : ExpenseListItem {
        override val itemId: String get() = "category-${spend.category.name}"
    }

    /** The "ALL EXPENSES" heading, with the hairline that separates it from the legend. */
    data object AllExpensesHeader : ExpenseListItem {
        override val itemId: String get() = "header-all"
    }

    /** A single recorded expense. */
    data class ExpenseRow(val expense: Expense) : ExpenseListItem {
        override val itemId: String get() = "expense-${expense.id}"
    }
}

/**
 * Everything the Money tab displays.
 *
 * @property pet The one pet this screen was opened for from its detail page, for the toolbar title;
 *   null on the tab, which spans a choice of pets.
 * @property pets The owner's pets, for the chip row. Empty when opened for one pet, where there is
 *   no choice to offer.
 * @property selectedPetId The chosen chip: a pet's id, or [ExpenseViewModel.ALL_PETS].
 * @property items The whole scrolling page as rows — see [ExpenseListItem].
 * @property hasExpenses Whether anything at all has been recorded for the pets in scope. Separate
 *   from [items] being non-empty, because a month with no spending still shows its (empty) card.
 * @property hasNoPets True when the account has no pets, which words the empty state differently.
 * @property isLoading True until the first database results arrive, so the empty state never flashes
 *   up before them.
 */
data class ExpensesUiState(
    val pet: Pet? = null,
    val pets: List<Pet> = emptyList(),
    val selectedPetId: Long = ExpenseViewModel.ALL_PETS,
    val items: List<ExpenseListItem> = emptyList(),
    val hasExpenses: Boolean = false,
    val hasNoPets: Boolean = false,
    val isLoading: Boolean = true
) {
    /** True when the "no expenses yet" message should replace the list. */
    val showsEmptyState: Boolean get() = !isLoading && !hasExpenses
}

/** A one-off occurrence the expenses screen reacts to exactly once. */
sealed interface ExpenseEvent {

    /**
     * An expense was deleted by a swipe, and the user should be offered an undo. The whole row travels
     * with the event, because once deleted there is nothing left in the database to look up.
     */
    data class Deleted(val expense: Expense) : ExpenseEvent
}

/**
 * Backs the Money tab: this month's spending for one pet or all of them, its breakdown by category,
 * and every expense ever recorded.
 *
 * In MVVM this is the **ViewModel**. The fragment observes [uiState] and forwards taps and swipes;
 * every read and write goes out through [PetRepository] and [ExpenseRepository]. The month arithmetic
 * itself is in [MonthlySpending], a plain Kotlin object with its own unit tests.
 *
 * **Two ways in**, as before — see the note at the top of nav_graph.xml:
 *  - **As the tab** ([initialPetId] is [NO_PET_ID]): a chip row offers "All pets" and each pet, and
 *    "All pets" is chosen to begin with, so the headline figure is the household's spending.
 *  - **From a pet's detail page**: that animal only, no chips.
 *
 * **"All pets" without touching the data layer.** The repository has a live query for one pet's
 * expenses but not for a whole account's. Rather than add one, the tab subscribes to each pet's query
 * and merges the lists — [combine] re-emits whenever any of them changes. A household has a handful
 * of pets, so this is a handful of small queries, and it keeps the data layer exactly as it was.
 *
 * **What the card counts, and what the list shows.** The card and the legend describe the *current
 * calendar month*, which is what "TOTAL SPENT · SEPTEMBER" says. The list beneath is every expense,
 * newest first, as its heading "ALL EXPENSES" says — which is also what keeps last month's receipts
 * reachable for editing.
 *
 * @param ownerId Whose pets the chips offer. Ignored when a specific pet was passed in.
 * @param initialPetId The pet to show, or [NO_PET_ID] for the tab.
 */
class ExpenseViewModel(
    private val petRepository: PetRepository,
    private val expenseRepository: ExpenseRepository,
    ownerId: Long,
    initialPetId: Long
) : ViewModel() {

    /** True on the tab, where the user chooses between "All pets" and each pet. */
    val showsPetPicker: Boolean = initialPetId == NO_PET_ID

    /** The chosen chip. On the tab it starts at "All pets"; for one pet it is that pet and never moves. */
    private val selectedPetId = MutableStateFlow(if (showsPetPicker) ALL_PETS else initialPetId)

    /** Records the chip the user tapped: a pet's id, or [ALL_PETS]. */
    fun selectPet(petId: Long) {
        selectedPetId.value = petId
    }

    /**
     * The screen's state.
     *
     * [flatMapLatest] swaps the expense subscriptions whenever the pets in scope change — a chip is
     * tapped, or a pet is added or deleted — cancelling the old ones, so moving between chips never
     * leaves a query running for a pet no longer shown.
     *
     * The owner's pets are read with the repository's own flow rather than a cached StateFlow with an
     * empty starting value. The difference is visible: an empty starting list would briefly look like
     * "you have no pets" and flash that empty state before Room had answered.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ExpensesUiState> = if (showsPetPicker) {
        combine(petRepository.observePetsForOwner(ownerId), selectedPetId) { pets, selected ->
            pets to selected
        }.flatMapLatest { (pets, selected) ->
            // A selected pet that has since been deleted (here, or by a sync from another device)
            // falls back to "All pets" rather than showing the spending of an animal that is gone.
            val chosen = pets.firstOrNull { it.id == selected }
            val scope = chosen?.let { listOf(it) } ?: pets

            spendingFor(scope).map { expenses ->
                buildState(
                    expenses = expenses,
                    pet = null,
                    pets = pets,
                    selectedPetId = chosen?.id ?: ALL_PETS
                )
            }
        }
    } else {
        petRepository.observePet(initialPetId).flatMapLatest { pet ->
            spendingFor(listOfNotNull(pet)).map { expenses ->
                buildState(expenses = expenses, pet = pet, pets = emptyList(), selectedPetId = initialPetId)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        // Kept alive for five seconds after the screen stops collecting, so a rotation re-attaches
        // to the live queries instead of re-running them and flashing the loading state.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ExpensesUiState()
    )

    /**
     * Every expense for [pets], merged into one list, newest first.
     *
     * With no pets there is nothing to query, and a flow that never emitted would leave the screen on
     * its loading state forever — so an empty list is emitted instead.
     */
    private fun spendingFor(pets: List<Pet>): Flow<List<Expense>> {
        if (pets.isEmpty()) return flowOf(emptyList())

        return combine(pets.map { expenseRepository.observeExpensesForPet(it.id) }) { perPet ->
            // Each pet's list is already newest first; merged, they need sorting again as a whole.
            perPet.flatMap { it }.sortedByDescending { it.incurredOn }
        }
    }

    /**
     * Assembles the page: the total card, the legend, the heading, then every expense.
     *
     * Nothing at all is built when there are no expenses, so the screen shows its empty state
     * rather than a card announcing "Rs 0.00". A month with no spending *among* existing expenses is
     * different: the card still appears, reporting the empty month against the last one.
     *
     * The month is read from the clock at the moment of each emission. If the screen sits open across
     * midnight on the last day of a month it moves on at the next change to the data, which is an
     * acceptable lag for a spending summary.
     */
    private fun buildState(
        expenses: List<Expense>,
        pet: Pet?,
        pets: List<Pet>,
        selectedPetId: Long
    ): ExpensesUiState {
        val items = if (expenses.isEmpty()) {
            emptyList()
        } else {
            val month = MonthlySpending.summarise(expenses, System.currentTimeMillis())
            buildList {
                add(ExpenseListItem.Summary(month))
                addAll(month.breakdown.map { ExpenseListItem.CategoryTotal(it) })
                add(ExpenseListItem.AllExpensesHeader)
                addAll(expenses.map { ExpenseListItem.ExpenseRow(it) })
            }
        }

        return ExpensesUiState(
            pet = pet,
            pets = pets,
            selectedPetId = selectedPetId,
            items = items,
            hasExpenses = expenses.isNotEmpty(),
            hasNoPets = showsPetPicker && pets.isEmpty(),
            isLoading = false
        )
    }

    private val _events = Channel<ExpenseEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * Deletes an expense, as the swipe-left gesture does. No confirmation: the fragment offers an undo
     * instead, the app's convention for swipe actions, and the edit screen's delete button remains the
     * deliberate, screen-reader-reachable route.
     */
    fun deleteExpense(expense: Expense) {
        viewModelScope.launch {
            expenseRepository.deleteExpense(expense)
            _events.send(ExpenseEvent.Deleted(expense))
        }
    }

    /**
     * Puts back an expense removed by [deleteExpense], for the undo action. Re-inserting the original
     * object restores its id too, because Room only generates a key when given zero.
     */
    fun restoreExpense(expense: Expense) {
        viewModelScope.launch {
            expenseRepository.addExpense(expense)
        }
    }

    companion object {

        /**
         * The pet id meaning "none given", matching the default declared on this destination in
         * nav_graph.xml. Its presence puts the screen into tab mode.
         */
        const val NO_PET_ID = -1L

        /**
         * The chip meaning "every pet". The same value as [NO_PET_ID] — both mean "no one pet" — but
         * named separately because they answer different questions: how the screen was opened, and
         * which chip is chosen. Mirrors ChecklistViewModel.ALL_PETS.
         */
        const val ALL_PETS = NO_PET_ID

        /**
         * Builds the ViewModel. A function because both ids are only known at runtime: the owner from
         * the session, the pet from the navigation argument.
         */
        fun factory(ownerId: Long, petId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                ExpenseViewModel(
                    petRepository = application.petRepository,
                    expenseRepository = application.expenseRepository,
                    ownerId = ownerId,
                    initialPetId = petId
                )
            }
        }
    }
}
