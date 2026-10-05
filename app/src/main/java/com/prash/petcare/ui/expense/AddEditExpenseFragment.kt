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

/** Form for recording a new expense against a pet, or changing an existing one. */
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

    /** Fills the category dropdown. */
    private fun setUpCategoryDropdown() {
        // Each row is "💊 Medication": the emoji is what the expense's badge will show once this is saved.
        val labels = ExpenseCategory.entries
            .map { it.emojiLabel(requireContext()) }
            .toTypedArray()
        binding.categoryInput.setSimpleItems(labels)

        binding.categoryInput.setOnItemClickListener { _, _, position, _ ->
            viewModel.onCategorySelected(ExpenseCategory.entries[position])
        }
    }

    private fun setUpClickListeners() {
        // Focusable so it can be reached by keyboard, D-pad or switch access, with the soft keyboard suppressed.
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

        // Clearing an error as soon as the user starts correcting.
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

    /** Asks before deleting. */
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

        // The same "💊 Medication" pair the dropdown rows show, so the closed field reads as the row that was picked.
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

            // Both outcomes simply close the screen. The expenses screen behind it already observes the database.
            is AddEditExpenseEvent.Saved,
            is AddEditExpenseEvent.Deleted -> findNavController().navigateUp()

            is AddEditExpenseEvent.Error -> {
                Snackbar.make(binding.root, getString(event.messageRes), Snackbar.LENGTH_LONG)
                    .show()
            }
        }
    }

    /** Writes a loaded expense's values into the text fields. */
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
