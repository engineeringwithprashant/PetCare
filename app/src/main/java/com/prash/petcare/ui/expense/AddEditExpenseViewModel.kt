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

/**
 * The parts of the expense form the ViewModel owns.
 *
 * The typed title, amount and notes stay in their EditTexts, which restore themselves across a
 * rotation for free. Held here are the two values chosen from pickers — which no view can restore
 * on its own — plus the validation errors and progress flags.
 *
 * @property isEditMode True when changing an existing expense, false when recording a new one.
 *   Controls the screen title and whether the delete button appears.
 * @property category What the money went on; the breakdown groups by exactly this value.
 * @property incurredOn The day the money was spent, as epoch milliseconds, or null until chosen.
 * @property isLoading True while an existing expense is being fetched.
 * @property isSaving True while a write is in flight, so the save button can be disabled and a
 *   double tap cannot insert the same expense twice.
 */
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

    /**
     * Carries a loaded expense so the fragment can fill in the text fields.
     *
     * Sent as an event rather than exposed as state precisely because it must happen once. Writing
     * these values on every state emission would overwrite whatever the user was part-way through
     * typing and jump their cursor back to the start.
     */
    data class PrefillForm(val expense: Expense) : AddEditExpenseEvent

    /** The expense was recorded or updated; the screen should close. */
    data object Saved : AddEditExpenseEvent

    /** The expense was deleted; the screen should close. */
    data object Deleted : AddEditExpenseEvent

    /** Something failed that is not tied to a single field. */
    data class Error(@param:StringRes val messageRes: Int) : AddEditExpenseEvent
}

/**
 * Backs the add/edit expense form.
 *
 * One ViewModel serves both modes, distinguished by [expenseId]: [NO_EXPENSE_ID] means "record a
 * new expense", anything else means "edit that one". Splitting this in two would duplicate all the
 * parsing and validation for the sake of one branch at save time.
 *
 * Like every ViewModel in the app it talks only to a [ExpenseRepository], never to the DAO, and
 * holds no Android view or context types — errors are carried as string resource *ids* that the
 * fragment resolves to text.
 *
 * @param expenseRepository Where the expense is written, updated or removed.
 * @param petId The animal the money was spent on. Fixed by the screen the user came from, so it is
 *   never editable here: moving a receipt to a different pet would silently rewrite two pets'
 *   spending histories.
 * @param expenseId Which expense to edit, or [NO_EXPENSE_ID] to record a new one.
 */
class AddEditExpenseViewModel(
    private val expenseRepository: ExpenseRepository,
    private val petId: Long,
    private val expenseId: Long
) : ViewModel() {

    /** True when changing an existing record rather than creating one. */
    private val isEditMode: Boolean = expenseId != NO_EXPENSE_ID

    /**
     * The expense as it was loaded, kept so a save can preserve the fields the form does not show —
     * the id, the pet, and when the record was originally entered.
     */
    private var loadedExpense: Expense? = null

    private val _formState = MutableStateFlow(ExpenseFormState(isEditMode = isEditMode))
    val formState: StateFlow<ExpenseFormState> = _formState.asStateFlow()

    private val _events = Channel<AddEditExpenseEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        load()
    }

    /**
     * Fetches the expense being edited, or sets up the defaults for a new one.
     *
     * A new expense opens with today's date already filled in, because a receipt is nearly always
     * entered on the day it was paid. Pre-filling the common case saves a picker interaction
     * without taking the choice away.
     */
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

            // Picker-chosen values become state, because the views holding them cannot restore
            // themselves the way an EditText can.
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

    /**
     * Validates the form and writes the expense.
     *
     * The amount arrives as raw text because that is what the user typed; parsing it is this
     * layer's job, not the view's. Everything is validated before anything is written, so a form
     * with two problems shows both at once rather than making the user fix them one save at a time.
     */
    fun save(title: String, amountText: String, notes: String) {
        val trimmedTitle = title.trim()
        val state = _formState.value

        val amount = parseAmount(amountText)

        val titleError = if (trimmedTitle.isEmpty()) R.string.error_expense_title_required else null
        val amountError = when {
            amountText.isBlank() -> R.string.error_expense_amount_required
            // Covers both unparseable text and zero or negative figures. An expense of nothing is
            // not a record worth keeping, and a negative one would quietly subtract from the pet's
            // total — if money came back, that is a refund, which this app does not model.
            amount == null || amount <= 0.0 -> R.string.error_expense_amount_invalid
            else -> null
        }
        // A date is required because the whole point of storing `incurredOn` separately from
        // `createdAt` is to report spending by when it happened. Defaulting a missing one to today
        // would silently file an old receipt under the wrong month.
        val dateError = if (state.incurredOn == null) R.string.error_expense_date_required else null

        if (titleError != null || amountError != null || dateError != null) {
            _formState.value = state.copy(
                titleError = titleError,
                amountError = amountError,
                dateError = dateError
            )
            return
        }

        // Safe: the branches above return unless all three are valid, but the compiler cannot see
        // that through the nullable state, so the values are unwrapped explicitly.
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

            // `copy` on the loaded expense leaves id, petId and createdAt untouched. createdAt in
            // particular must survive an edit: it records when the receipt was entered, which
            // correcting a typo in the amount does not change.
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

    /**
     * Deletes the expense being edited.
     *
     * The confirmation dialog is the fragment's responsibility; by the time this runs the user has
     * already agreed. There is no reversible alternative offered here, unlike pausing a routine —
     * an expense either happened or it did not.
     */
    fun delete() {
        val expense = loadedExpense ?: return

        viewModelScope.launch {
            _formState.value = _formState.value.copy(isSaving = true)

            expenseRepository.deleteExpense(expense)

            _events.send(AddEditExpenseEvent.Deleted)
        }
    }

    /**
     * Reads a typed amount into a number, or null if it is not one.
     *
     * [String.toDoubleOrNull] only ever accepts a full stop as the decimal mark, but the
     * `numberDecimal` keyboard shows whichever separator the device's locale uses — a comma across
     * most of Europe. Without the swap below, a German user typing "24,50" would be told their
     * perfectly valid amount is invalid. Grouping separators are stripped for the same reason:
     * "1,200.50" is a natural thing to type and unparseable as it stands.
     *
     * The stripping is deliberately crude — the app never shows a pre-formatted number in this
     * field, so the only text here is what the user just typed.
     */
    private fun parseAmount(text: String): Double? {
        val cleaned = text.trim().replace(" ", "")

        // Whichever separator comes last is the decimal mark: in "1,200.50" it is the full stop,
        // in "1.200,50" the comma. Everything before it can only be grouping.
        val lastComma = cleaned.lastIndexOf(',')
        val lastDot = cleaned.lastIndexOf('.')

        val normalised = if (lastComma > lastDot) {
            // Comma is the decimal mark. The grouping full stops go first, which shifts the
            // comma's position, so the decimal mark is located again on the shortened string
            // rather than reusing the index found above.
            val withoutDots = cleaned.replace(".", "")
            val decimalMark = withoutDots.lastIndexOf(',')

            withoutDots.substring(0, decimalMark).replace(",", "") +
                "." + withoutDots.substring(decimalMark + 1)
        } else {
            // Full stop is the decimal mark, or there is no decimal part at all. Either way any
            // comma present can only be grouping.
            cleaned.replace(",", "")
        }

        return normalised.toDoubleOrNull()
    }

    /**
     * Today as midnight UTC.
     *
     * Dates are stored the way Material's date picker reports them — midnight **UTC** for the
     * chosen day — so the default has to be built the same way. Using the device's local midnight
     * instead would store a value hours off from any date the user later picks, and the two would
     * format as different days for anyone outside Greenwich.
     */
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
