package com.prash.petcare.ui.expense

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentExpensesBinding
import com.prash.petcare.ui.common.ChipOption
import com.prash.petcare.ui.common.SwipeActionCallback
import com.prash.petcare.ui.common.bindHeaderFromSession
import com.prash.petcare.ui.common.bindOptions
import com.prash.petcare.ui.common.openProfile
import com.prash.petcare.ui.common.setToolbarTitle
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/** The Money tab: this month's spending as a dark total card with a stacked category bar. */
class ExpensesFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentExpensesBinding? = null
    private val binding get() = _binding!!

    /** Which pet to show, or [ExpenseViewModel.NO_PET_ID] on the tab. */
    private val petId: Long by lazy {
        arguments?.getLong(ARG_PET_ID, ExpenseViewModel.NO_PET_ID) ?: ExpenseViewModel.NO_PET_ID
    }

    /** Which account is signed in — shared by every tab, see [SessionViewModel]. */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: ExpenseViewModel by viewModels {
        ExpenseViewModel.factory(ownerId = sessionViewModel.userId.value, petId = petId)
    }

    /** Tapping a card opens that expense for editing, with the pet the expense belongs. */
    private val expenseAdapter = ExpenseAdapter(
        onExpenseClick = { expense -> openExpenseForm(expense.petId, expense.id) }
    )

    /** The "which pet?" chooser for the add button, held so it can be dismissed with the view. */
    private var petDialog: AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExpensesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpModeSpecificChrome()
        setUpRecyclerView()
        setUpSwipeGestures()
        binding.addExpenseFab.setOnClickListener { startAddExpense() }
        observeViewModel()
    }

    /** Shows the header and chips on the tab, and neither for one pet. */
    private fun setUpModeSpecificChrome() {
        val isTab = viewModel.showsPetPicker
        binding.header.root.isVisible = isTab
        binding.petChipScroll.isVisible = isTab

        if (isTab) {
            // The badge opens the notifications screen, as on every other screen that draws this header.
            binding.header.setUp(
                title = R.string.expenses_header_title,
                onBadgeClick = { findNavController().navigate(R.id.notificationsFragment) },
                // The monogram opens the profile screen, as on every other screen that draws it.
                onMonogramClick = ::openProfile
            )

            // Keeps the header's mark showing the user's profile photo, falling back to the app's monogram.
            bindHeaderFromSession(binding.header, sessionViewModel)
        }
    }

    private fun setUpRecyclerView() {
        binding.expensesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = expenseAdapter
            // Not setHasFixedSize(true): the card, legend rows and expense cards are all different heights.
        }
    }

    /** Swipe-to-delete on the expense cards, following the app-wide convention: left is destructive, with an undo. */
    private fun setUpSwipeGestures() {
        val callback = SwipeActionCallback(
            context = requireContext(),
            allowedDirections = { position ->
                // `currentList` is what the adapter is actually showing, which is what the swiped position refers.
                val item = expenseAdapter.currentList.getOrNull(position)
                if (item is ExpenseListItem.ExpenseRow) ItemTouchHelper.LEFT else 0
            },
            onSwipe = { position, _ ->
                val item = expenseAdapter.currentList.getOrNull(position)
                if (item is ExpenseListItem.ExpenseRow) viewModel.deleteExpense(item.expense)
            }
        )
        ItemTouchHelper(callback).attachToRecyclerView(binding.expensesRecyclerView)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Separate coroutines: collect() never returns, so in sequence only the first would run.
                launch { viewModel.uiState.collect { render(it) } }
                launch { viewModel.events.collect { handleEvent(it) } }
            }
        }
    }

    // Rendering.

    private fun render(state: ExpensesUiState) {
        if (viewModel.showsPetPicker) {
            renderPetChips(state)
        } else {
            // "Spending on Bella" in the toolbar. Until the pet loads, the destination's own label stays.
            state.pet?.let { setToolbarTitle(getString(R.string.expenses_title_format, it.name)) }
        }

        expenseAdapter.submitList(state.items)

        // The list and the empty state share one slot and exactly one shows — neither while loading.
        binding.expensesRecyclerView.isVisible = !state.isLoading && state.hasExpenses
        binding.emptyExpensesContainer.isVisible = state.showsEmptyState
        renderEmptyStateWording(state)

        // An expense needs a pet to belong to; with none, the button has nothing it could do.
        binding.addExpenseFab.isEnabled = !viewModel.showsPetPicker || state.pets.isNotEmpty()
    }

    /** Fills the chip row — "All pets", then each pet — and marks the chosen one. */
    private fun renderPetChips(state: ExpensesUiState) {
        binding.petChipScroll.isVisible = state.pets.isNotEmpty()

        val options = listOf(ChipOption(KEY_ALL, getString(R.string.expenses_filter_all_pets))) +
            state.pets.map { ChipOption(it.id.toString(), it.name) }

        binding.petChipGroup.bindOptions(
            options = options,
            selectedKey = if (state.selectedPetId == ExpenseViewModel.ALL_PETS) KEY_ALL
            else state.selectedPetId.toString(),
            chipLayout = R.layout.item_pet_filter_chip,
            onSelected = { key -> viewModel.selectPet(key.toLongOrNull() ?: ExpenseViewModel.ALL_PETS) }
        )
    }

    /** Words the empty state. "No expenses yet" and "you have no pets" both leave nothing to draw. */
    private fun renderEmptyStateWording(state: ExpensesUiState) {
        if (state.hasNoPets) {
            binding.emptyExpensesIcon.setImageResource(R.drawable.ic_pet_placeholder)
            binding.emptyExpensesTitle.setText(R.string.pet_picker_empty_title)
            binding.emptyExpensesMessage.setText(R.string.pet_picker_empty_message)
        } else {
            binding.emptyExpensesIcon.setImageResource(R.drawable.ic_empty_expenses)
            binding.emptyExpensesTitle.setText(R.string.expenses_empty_title)
            binding.emptyExpensesMessage.setText(R.string.expenses_empty_message)
        }
    }

    // Adding and editing.

    /** The add button: open the expense form for the right pet. */
    private fun startAddExpense() {
        val state = viewModel.uiState.value

        val knownPetId = when {
            !viewModel.showsPetPicker -> petId
            state.selectedPetId != ExpenseViewModel.ALL_PETS -> state.selectedPetId
            state.pets.size == 1 -> state.pets.single().id
            else -> null
        }

        if (knownPetId != null) {
            openExpenseForm(knownPetId, AddEditExpenseViewModel.NO_EXPENSE_ID)
            return
        }

        // No pets at all: the button is disabled, but the state may have changed since it was drawn.
        if (state.pets.isEmpty()) return

        petDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.expenses_pick_pet_title)
            .setItems(state.pets.map { it.name }.toTypedArray()) { _, index ->
                openExpenseForm(state.pets[index].id, AddEditExpenseViewModel.NO_EXPENSE_ID)
            }
            .setNegativeButton(R.string.delegate_cancel, null)
            .setOnDismissListener { petDialog = null }
            .show()
    }

    /** Opens the existing add/edit expense form. [AddEditExpenseViewModel.NO_EXPENSE_ID] records a new expense. */
    private fun openExpenseForm(forPetId: Long, expenseId: Long) {
        findNavController().navigate(
            R.id.action_expenses_to_add_edit_expense,
            bundleOf(
                AddEditExpenseFragment.ARG_PET_ID to forPetId,
                AddEditExpenseFragment.ARG_EXPENSE_ID to expenseId
            )
        )
    }

    // Undo.

    private fun handleEvent(event: ExpenseEvent) {
        when (event) {
            is ExpenseEvent.Deleted -> showUndoDeleteSnackbar(event)
        }
    }

    /** Offers to undo a swipe-delete — which is what makes the gesture safe without a confirmation. */
    private fun showUndoDeleteSnackbar(event: ExpenseEvent.Deleted) {
        val root = _binding?.root ?: return
        Snackbar.make(root, getString(R.string.expense_deleted_format, event.expense.title), Snackbar.LENGTH_LONG)
            .setAnchorView(binding.addExpenseFab)
            .setAction(R.string.action_undo) { viewModel.restoreExpense(event.expense) }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        petDialog?.dismiss()
        petDialog = null
        // The adapter outlives the view as a fragment property.
        binding.expensesRecyclerView.adapter = null
        _binding = null
    }

    companion object {
        /** Navigation argument name; must match the one declared in nav_graph.xml. */
        const val ARG_PET_ID = "petId"

        /** The chip key for "All pets" — never a pet's id. */
        private const val KEY_ALL = "all"
    }
}
