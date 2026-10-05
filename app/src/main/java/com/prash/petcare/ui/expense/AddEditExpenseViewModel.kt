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
import com.prash.petcare.data.repository.ExpenseRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

/** The parts of the expense form the ViewModel owns. */
data class ExpenseFormState(
    val isEditMode: Boolean = false,
    val category: ExpenseCategory = ExpenseCategory.FOOD,
    val incurredOn: Long? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    @param:StringRes val titleError: Int? = null,
    @param:StringRes val amountError: Int? = null,
    @param:StringRes val dateError: Int? = null
)

/** A one-off occurrence the expense form reacts to exactly once. */
sealed interface AddEditExpenseEvent {

    /** Carries a loaded expense so the fragment can fill in the text fields. */
    data class PrefillForm(val expense: Expense) : AddEditExpenseEvent

    /** The expense was recorded or updated; the screen should close. */
    data object Saved : AddEditExpenseEvent

    /** The expense was deleted; the screen should close. */
    data object Deleted : AddEditExpenseEvent

    /** Something failed that is not tied to a single field. */
    data class Error(@param:StringRes val messageRes: Int) : AddEditExpenseEvent
}

/** Backs the add/edit expense form. */
class AddEditExpenseViewModel(
    private val expenseRepository: ExpenseRepository,
    private val petId: Long,
    private val expenseId: Long
) : ViewModel() {

    /** True when changing an existing record rather than creating one. */
    private val isEditMode: Boolean = expenseId != NO_EXPENSE_ID

    /** The expense as it was loaded, kept so a save can preserve the fields the form does not show — the id. */
    private var loadedExpense: Expense? = null

    private val _formState = MutableStateFlow(ExpenseFormState(isEditMode = isEditMode))
    val formState: StateFlow<ExpenseFormState> = _formState.asStateFlow()

    private val _events = Channel<AddEditExpenseEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        load()
    }

    /** Fetches the expense being edited, or sets up the defaults for a new one. */
    private fun load() {
        viewModelScope.launch {
            if (!isEditMode) {
                _formState.value = _formState.value.copy(
                    isLoading = false,
                    incurredOn = todayInUtcMillis()
                )
                return@launch
            }

            val expense = expenseRepository.getExpense(expenseId)

            if (expense == null) {
                // The expense was deleted elsewhere while this screen was opening.
                _formState.value = _formState.value.copy(isLoading = false)
                _events.send(AddEditExpenseEvent.Error(R.string.error_expense_not_found))
                return@launch
            }

            loadedExpense = expense

            // Picker-chosen values become state.
            _formState.value = _formState.value.copy(
                isLoading = false,
                category = expense.category,
                incurredOn = expense.incurredOn
            )

            // The typed fields are filled once, via an event.
            _events.send(AddEditExpenseEvent.PrefillForm(expense))
        }
    }

    /** Records the category chosen in the dropdown. */
    fun onCategorySelected(category: ExpenseCategory) {
        _formState.value = _formState.value.copy(category = category)
    }

    /** Records the date chosen in the date picker. */
    fun onDateSelected(millis: Long) {
        _formState.value = _formState.value.copy(incurredOn = millis, dateError = null)
    }

    /** Clears the title error as the user starts correcting it. */
    fun clearTitleError() {
        _formState.value = _formState.value.copy(titleError = null)
    }

    /** Clears the amount error as the user starts correcting it. */
    fun clearAmountError() {
        _formState.value = _formState.value.copy(amountError = null)
    }

    /** Validates the form and writes the expense. */
    fun save(title: String, amountText: String, notes: String) {
        val trimmedTitle = title.trim()
        val state = _formState.value

        val amount = parseAmount(amountText)

        val titleError = if (trimmedTitle.isEmpty()) R.string.error_expense_title_required else null
        val amountError = when {
            amountText.isBlank() -> R.string.error_expense_amount_required
            // Covers both unparseable text and zero or negative figures.
            amount == null || amount <= 0.0 -> R.string.error_expense_amount_invalid
            else -> null
        }
        // A date is required.
        val dateError = if (state.incurredOn == null) R.string.error_expense_date_required else null

        if (titleError != null || amountError != null || dateError != null) {
            _formState.value = state.copy(
                titleError = titleError,
                amountError = amountError,
                dateError = dateError
            )
            return
        }

        // Safe: the branches above return unless all three are valid.
        val validAmount = amount ?: return
        val incurredOn = state.incurredOn ?: return

        viewModelScope.launch {
            _formState.value = state.copy(
                isSaving = true,
                titleError = null,
                amountError = null,
                dateError = null
            )

            val existing = loadedExpense

            // `copy` on the loaded expense leaves id, petId and createdAt untouched.
            val expense = existing?.copy(
                title = trimmedTitle,
                amount = validAmount,
                category = state.category,
                incurredOn = incurredOn,
                notes = notes.trimToNull()
            ) ?: Expense(
                petId = petId,
                title = trimmedTitle,
                amount = validAmount,
                category = state.category,
                incurredOn = incurredOn,
                notes = notes.trimToNull()
            )

            if (existing == null) {
                expenseRepository.addExpense(expense)
            } else {
                expenseRepository.updateExpense(expense)
            }

            _events.send(AddEditExpenseEvent.Saved)
        }
    }

    /** Deletes the expense being edited. */
    fun delete() {
        val expense = loadedExpense ?: return

        viewModelScope.launch {
            _formState.value = _formState.value.copy(isSaving = true)

            expenseRepository.deleteExpense(expense)

            _events.send(AddEditExpenseEvent.Deleted)
        }
    }

    /** Reads a typed amount into a number, or null if it is not one. */
    private fun parseAmount(text: String): Double? {
        val cleaned = text.trim().replace(" ", "")

        // Whichever separator comes last is the decimal mark: in "1,200.50" it is the full stop.
        val lastComma = cleaned.lastIndexOf(',')
        val lastDot = cleaned.lastIndexOf('.')

        val normalised = if (lastComma > lastDot) {
            // Comma is the decimal mark. The grouping full stops go first, which shifts the comma's position.
            val withoutDots = cleaned.replace(".", "")
            val decimalMark = withoutDots.lastIndexOf(',')

            withoutDots.substring(0, decimalMark).replace(",", "") +
                "." + withoutDots.substring(decimalMark + 1)
        } else {
            // Full stop is the decimal mark, or there is no decimal part at all.
            cleaned.replace(",", "")
        }

        return normalised.toDoubleOrNull()
    }

    /** Today as midnight UTC. */
    private fun todayInUtcMillis(): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    /** Blank input and "not provided" mean the same thing, so both are stored as null. */
    private fun String.trimToNull(): String? = trim().takeIf { it.isNotEmpty() }

    companion object {
        /** [expenseId] value meaning "record a new expense". Matches the default in the nav graph. */
        const val NO_EXPENSE_ID = -1L

        fun factory(petId: Long, expenseId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                AddEditExpenseViewModel(
                    expenseRepository = application.expenseRepository,
                    petId = petId,
                    expenseId = expenseId
                )
            }
        }
    }
}
