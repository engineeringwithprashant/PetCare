package com.prash.petcare.ui.expense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import com.prash.petcare.data.local.SettingsStore
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

/** One row in the Money tab's single scrolling list. */
sealed interface ExpenseListItem {

    /** Stable identity for DiffUtil, namespaced by kind. */
    val itemId: String

    /** The dark total card: this month's total, its stacked bar, and the caption beneath. */
    data class Summary(val month: MonthSummary, val currencySymbol: String) : ExpenseListItem {
        override val itemId: String get() = "summary"
    }

    /** One legend row: a category's dot, name, share of the month, and amount. */
    data class CategoryTotal(
        val spend: CategorySpend,
        val currencySymbol: String
    ) : ExpenseListItem {
        override val itemId: String get() = "category-${spend.category.name}"
    }

    /** The "ALL EXPENSES" heading, with the hairline that separates it from the legend. */
    data object AllExpensesHeader : ExpenseListItem {
        override val itemId: String get() = "header-all"
    }

    /** A single recorded expense. */
    data class ExpenseRow(val expense: Expense, val currencySymbol: String) : ExpenseListItem {
        override val itemId: String get() = "expense-${expense.id}"
    }
}

/** Everything the Money tab displays. */
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

    /** An expense was deleted by a swipe, and the user should be offered an undo. */
    data class Deleted(val expense: Expense) : ExpenseEvent
}

/** Backs the Money tab: this month's spending for one pet or all of them, its breakdown by category. */
class ExpenseViewModel(
    private val petRepository: PetRepository,
    private val expenseRepository: ExpenseRepository,
    private val settingsStore: SettingsStore,
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

    /** The screen's state. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ExpensesUiState> = if (showsPetPicker) {
        combine(petRepository.observePetsForOwner(ownerId), selectedPetId) { pets, selected ->
            pets to selected
        }.flatMapLatest { (pets, selected) ->
            // A selected pet that has since been deleted.
            val chosen = pets.firstOrNull { it.id == selected }
            val scope = chosen?.let { listOf(it) } ?: pets

            // The currency preference is folded in here rather than read inside buildState.
            combine(spendingFor(scope), settingsStore.currency) { expenses, currency ->
                buildState(
                    expenses = expenses,
                    pet = null,
                    pets = pets,
                    selectedPetId = chosen?.id ?: ALL_PETS,
                    currencySymbol = currency.symbol
                )
            }
        }
    } else {
        petRepository.observePet(initialPetId).flatMapLatest { pet ->
            combine(spendingFor(listOfNotNull(pet)), settingsStore.currency) { expenses, currency ->
                buildState(
                    expenses = expenses,
                    pet = pet,
                    pets = emptyList(),
                    selectedPetId = initialPetId,
                    currencySymbol = currency.symbol
                )
            }
        }
    }.stateIn(
        scope = viewModelScope,
        // Kept alive for five seconds after the screen stops collecting.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ExpensesUiState()
    )

    /** Every expense for [pets], merged into one list, newest first. */
    private fun spendingFor(pets: List<Pet>): Flow<List<Expense>> {
        if (pets.isEmpty()) return flowOf(emptyList())

        return combine(pets.map { expenseRepository.observeExpensesForPet(it.id) }) { perPet ->
            // Each pet's list is already newest first; merged, they need sorting again as a whole.
            perPet.flatMap { it }.sortedByDescending { it.incurredOn }
        }
    }

    /** Assembles the page: the total card, the legend, the heading, then every expense. */
    private fun buildState(
        expenses: List<Expense>,
        pet: Pet?,
        pets: List<Pet>,
        selectedPetId: Long,
        currencySymbol: String
    ): ExpensesUiState {
        val items = if (expenses.isEmpty()) {
            emptyList()
        } else {
            val month = MonthlySpending.summarise(expenses, System.currentTimeMillis())
            buildList {
                add(ExpenseListItem.Summary(month, currencySymbol))
                addAll(month.breakdown.map { ExpenseListItem.CategoryTotal(it, currencySymbol) })
                add(ExpenseListItem.AllExpensesHeader)
                addAll(expenses.map { ExpenseListItem.ExpenseRow(it, currencySymbol) })
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

    /** Deletes an expense, as the swipe-left gesture does. */
    fun deleteExpense(expense: Expense) {
        viewModelScope.launch {
            expenseRepository.deleteExpense(expense)
            _events.send(ExpenseEvent.Deleted(expense))
        }
    }

    /** Puts back an expense removed by [deleteExpense], for the undo action. */
    fun restoreExpense(expense: Expense) {
        viewModelScope.launch {
            expenseRepository.addExpense(expense)
        }
    }

    companion object {

        /** The pet id meaning "none given", matching the default declared on this destination in nav_graph.xml. */
        const val NO_PET_ID = -1L

        /** The chip meaning "every pet". The same value as [NO_PET_ID] — both mean "no one pet". */
        const val ALL_PETS = NO_PET_ID

        /** Builds the ViewModel. A function because both ids are only known at runtime: the owner from the session. */
        fun factory(ownerId: Long, petId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                ExpenseViewModel(
                    petRepository = application.petRepository,
                    expenseRepository = application.expenseRepository,
                    settingsStore = application.settingsStore,
                    ownerId = ownerId,
                    initialPetId = petId
                )
            }
        }
    }
}
