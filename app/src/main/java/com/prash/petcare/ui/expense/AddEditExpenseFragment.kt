package com.prash.petcare.ui.expense

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.ExpenseCategory
import com.prash.petcare.databinding.FragmentAddEditExpenseBinding
import com.prash.petcare.ui.common.emojiLabel
import com.prash.petcare.ui.common.formatAmountForInput
import com.prash.petcare.ui.common.formatStoredDate
import com.prash.petcare.ui.common.setToolbarTitle
import kotlinx.coroutines.launch

/**
 * Form for recording a new expense against a pet, or changing an existing one.
 *
 * Which mode it runs in depends on the `expenseId` navigation argument:
 * [AddEditExpenseViewModel.NO_EXPENSE_ID] means add, anything else means edit. The distinction is
 * the ViewModel's to interpret — this fragment renders whatever state comes back.
 *
 * As the **View** in MVVM it owns no logic: parsing the amount, validation and persistence all live
 * in [AddEditExpenseViewModel]. What it owns are the things that genuinely need a Context or a view
 * — the date picker and the confirmation dialog.
 */
class AddEditExpenseFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentAddEditExpenseBinding? = null
    private val binding get() = _binding!!

    private val petId: Long by lazy { requireArguments().getLong(ARG_PET_ID) }

    private val expenseId: Long by lazy {
        requireArguments().getLong(ARG_EXPENSE_ID, AddEditExpenseViewModel.NO_EXPENSE_ID)
    }

    private val viewModel: AddEditExpenseViewModel by viewModels {
        AddEditExpenseViewModel.factory(petId, expenseId)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddEditExpenseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpCategoryDropdown()
        setUpClickListeners()
        observeViewModel()
    }

    /**
     * Fills the category dropdown.
     *
     * The labels and [ExpenseCategory.entries] are built from the same list in the same order, so
     * the tapped position maps straight back to an enum constant. Reading the category back from
     * the displayed text would break the moment the app is translated.
     */
    private fun setUpCategoryDropdown() {
        // Each row is "💊  Medication": the emoji is what the expense's badge will show once this is
        // saved, so the user sees the symbol they are choosing at the moment they choose it rather
        // than meeting it for the first time on the card afterwards.
        val labels = ExpenseCategory.entries
            .map { it.emojiLabel(requireContext()) }
            .toTypedArray()
        binding.categoryInput.setSimpleItems(labels)

        binding.categoryInput.setOnItemClickListener { _, _, position, _ ->
            viewModel.onCategorySelected(ExpenseCategory.entries[position])
        }
    }

    private fun setUpClickListeners() {
        // Focusable so it can be reached by keyboard, D-pad or switch access, with the soft keyboard
        // suppressed so focusing does not pop one up for a field nobody types into. The same
        // treatment the pet form gives its date of birth field.
        binding.dateInput.showSoftInputOnFocus = false
        binding.dateInput.setOnClickListener { showDatePicker() }

        binding.saveExpenseButton.setOnClickListener {
            viewModel.save(
                title = binding.expenseTitleInput.text?.toString().orEmpty(),
                amountText = binding.amountInput.text?.toString().orEmpty(),
                notes = binding.notesInput.text?.toString().orEmpty()
            )
        }

        binding.deleteExpenseButton.setOnClickListener { showDeleteConfirmation() }

        // Clearing an error as soon as the user starts correcting it, rather than making them save
        // again to find out whether they have fixed it.
        binding.expenseTitleInput.doOnTextChanged { _, _, _, _ -> viewModel.clearTitleError() }
        binding.amountInput.doOnTextChanged { _, _, _, _ -> viewModel.clearAmountError() }
    }

    /** Opens the Material date picker, starting from the date already chosen if there is one. */
    private fun showDatePicker() {
        val existingSelection = viewModel.formState.value.incurredOn

        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.expense_date_picker_title)
            .setSelection(existingSelection ?: MaterialDatePicker.todayInUtcMilliseconds())
            .build()

        picker.addOnPositiveButtonClickListener { selectionMillis ->
            viewModel.onDateSelected(selectionMillis)
        }

        picker.show(childFragmentManager, DATE_PICKER_TAG)
    }

    /**
     * Asks before deleting.
     *
     * Removing an expense changes the pet's total and its breakdown, and cannot be undone from this
     * screen, so the dialog names the expense rather than asking a generic "are you sure?".
     */
    private fun showDeleteConfirmation() {
        val title = binding.expenseTitleInput.text?.toString()?.trim().orEmpty()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_expense_dialog_title)
            .setMessage(getString(R.string.delete_expense_dialog_message, title))
            // Cancel is listed first and does nothing, so the safe choice is the easy one.
            .setNegativeButton(R.string.delete_expense_dialog_cancel, null)
            .setPositiveButton(R.string.delete_expense_dialog_confirm) { _, _ -> viewModel.delete() }
            .show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.formState.collect { state -> render(state) }
                }
                launch {
                    viewModel.events.collect { event -> handleEvent(event) }
                }
            }
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: ExpenseFormState) {
        // Which mode the form is in is the screen's title, so it goes in the app bar.
        setToolbarTitle(
            getString(
                if (state.isEditMode) R.string.edit_expense_title else R.string.add_expense_title
            )
        )

        // Only an existing expense can be deleted, so the button has no meaning in add mode.
        binding.deleteExpenseButton.visibility = if (state.isEditMode) View.VISIBLE else View.GONE

        binding.expenseTitleInputLayout.error = state.titleError?.let { getString(it) }
        binding.amountInputLayout.error = state.amountError?.let { getString(it) }
        binding.dateInputLayout.error = state.dateError?.let { getString(it) }

        // The same "💊  Medication" pair the dropdown rows show, so the closed field reads as the row
        // that was picked. `false` stops the dropdown treating this as typed input and filtering its
        // own list.
        binding.categoryInput.setText(state.category.emojiLabel(requireContext()), false)

        binding.dateInput.setText(state.incurredOn?.let { formatStoredDate(it) }.orEmpty())

        val isBusy = state.isLoading || state.isSaving
        binding.progressIndicator.visibility = if (isBusy) View.VISIBLE else View.GONE
        // Disabled while a write is in flight, so a double tap cannot record the same expense twice.
        binding.saveExpenseButton.isEnabled = !isBusy
        binding.deleteExpenseButton.isEnabled = !isBusy
    }

    private fun handleEvent(event: AddEditExpenseEvent) {
        when (event) {
            is AddEditExpenseEvent.PrefillForm -> prefillForm(event.expense)

            // Both outcomes simply close the screen. The expenses screen behind it already observes
            // the database, so its list, total and breakdown all update themselves.
            is AddEditExpenseEvent.Saved,
            is AddEditExpenseEvent.Deleted -> findNavController().navigateUp()

            is AddEditExpenseEvent.Error -> {
                Snackbar.make(binding.root, getString(event.messageRes), Snackbar.LENGTH_LONG)
                    .show()
            }
        }
    }

    /**
     * Writes a loaded expense's values into the text fields.
     *
     * Deliberately called once, from a one-shot event, rather than on every state update — writing
     * into an EditText the user is typing in would move their cursor and undo their edits. After a
     * rotation these fields restore themselves from Android's own saved instance state, so there is
     * nothing to repeat.
     *
     * The amount goes through [formatAmountForInput] rather than the app's currency formatter:
     * this is an editable field, and a currency symbol and grouping separators would only be text
     * the user has to delete before they can correct the figure.
     */
    private fun prefillForm(expense: Expense) {
        binding.expenseTitleInput.setText(expense.title)
        binding.amountInput.setText(formatAmountForInput(expense.amount))
        binding.notesInput.setText(expense.notes.orEmpty())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Navigation argument names; must match those declared in nav_graph.xml. */
        const val ARG_PET_ID = "petId"
        const val ARG_EXPENSE_ID = "expenseId"

        /** Tag the FragmentManager uses to restore the date picker after a rotation. */
        private const val DATE_PICKER_TAG = "expense_date_picker"
    }
}
