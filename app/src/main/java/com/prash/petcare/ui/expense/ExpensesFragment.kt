package com.prash.petcare.ui.expense

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.databinding.FragmentExpensesBinding
import com.prash.petcare.ui.common.SwipeActionCallback
import com.prash.petcare.ui.common.bindPets
import com.prash.petcare.ui.common.setToolbarTitle
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * One pet's spending: the total, a breakdown by category, and every recorded expense.
 *
 * As the **View** in MVVM it holds no logic of its own — the totals, the grouping and the ordering
 * are all worked out by [ExpenseViewModel], and this class renders whatever state comes back and
 * forwards taps and swipes. Recording and editing happen on the form this screen leads to.
 *
 * The whole screen is a single RecyclerView rather than a scroll view holding cards and lists. The
 * summary and breakdown are simply the first few rows, which means everything scrolls together
 * while the expenses below still recycle — and a pet's spending history only ever grows.
 */
class ExpensesFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentExpensesBinding? = null
    private val binding get() = _binding!!

    /**
     * Which pet to show, or [ExpenseViewModel.NO_PET_ID] when this screen was opened as the
     * Expenses tab and should offer the pet picker instead.
     *
     * `arguments?` rather than `requireArguments()`: reached from the bottom navigation bar, this
     * destination is navigated to with no argument bundle of its own, so there is genuinely nothing
     * to require. The default is what puts the screen into picker mode.
     */
    private val petId: Long by lazy {
        arguments?.getLong(ARG_PET_ID, ExpenseViewModel.NO_PET_ID) ?: ExpenseViewModel.NO_PET_ID
    }

    /**
     * Which account is signed in, for the pet picker's list.
     *
     * Read from the Activity-wide session rather than from a navigation argument: a bottom
     * navigation bar moves between tabs by destination id and has nowhere to attach one. See
     * [SessionViewModel].
     */
    private val sessionViewModel: SessionViewModel by activityViewModels()

    private val viewModel: ExpenseViewModel by viewModels {
        ExpenseViewModel.factory(
            ownerId = sessionViewModel.userId.value,
            petId = petId
        )
    }

    private val expenseAdapter = ExpenseAdapter(
        onExpenseClick = { expense -> openExpenseForm(expense.id) }
    )

    /**
     * True when the signed-in user has no pets at all, as opposed to a pet with no expenses yet.
     *
     * Held here rather than in [ExpensesUiState] because it belongs to the picker's list rather
     * than to any one pet's spending, and the two arrive on separate flows. The distinction matters
     * only for the wording of the empty state.
     */
    private var hasNoPets: Boolean = false

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
        setUpPetPicker()
        setUpRecyclerView()
        setUpSwipeGestures()
        setUpClickListeners()
        observeViewModel()
    }

    /**
     * Shows or hides the row of pet chips.
     *
     * Only the Expenses tab has one. Opened for a particular animal from its detail page, the pet
     * is already decided and a picker offering to change it would be offering to leave the screen
     * the user just asked for.
     */
    private fun setUpPetPicker() {
        binding.petPicker.root.isVisible = viewModel.showsPetPicker
        binding.petPicker.petPickerLabel.setText(R.string.pet_picker_label_expenses)
    }

    private fun setUpRecyclerView() {
        binding.expensesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = expenseAdapter
            // Deliberately not setHasFixedSize(true): the rows here are different heights and an
            // expense with notes is taller than one without, so the list must re-measure.
        }
    }

    /**
     * Wires up swipe-to-delete on the expense rows.
     *
     * Follows the app's fixed gesture convention, so a swipe never means different things on
     * different screens: **left is destructive**. There is no affirmative right-swipe here, because
     * an expense has no second action worth a gesture — nothing about it can be "completed".
     *
     * [SwipeActionCallback] draws the coloured background and the bin icon and calls back here.
     */
    private fun setUpSwipeGestures() {
        val callback = SwipeActionCallback(
            context = requireContext(),
            allowedDirections = { position ->
                // `currentList` is what the adapter is actually showing, which is what the swiped
                // position refers to. Reading from ViewModel state instead could disagree with it
                // mid-update and delete the wrong row.
                val item = expenseAdapter.currentList.getOrNull(position)

                // Only the expenses themselves can be swiped. The summary, the headings and the
                // breakdown rows are all derived from those expenses, so offering the gesture there
                // would imply something could be deleted that has no existence of its own.
                if (item is ExpenseListItem.ExpenseRow) ItemTouchHelper.LEFT else 0
            },
            onSwipe = { position, _ ->
                val item = expenseAdapter.currentList.getOrNull(position)
                if (item is ExpenseListItem.ExpenseRow) {
                    viewModel.deleteExpense(item.expense)
                }
            }
        )

        ItemTouchHelper(callback).attachToRecyclerView(binding.expensesRecyclerView)
    }

    private fun setUpClickListeners() {
        // The floating action button opens an empty form; passing no expense id is what tells the
        // form it is recording a new expense rather than editing one.
        binding.addExpenseFab.setOnClickListener {
            openExpenseForm(AddEditExpenseViewModel.NO_EXPENSE_ID)
        }
    }

    /**
     * Opens the form, either empty or loaded with an existing expense.
     *
     * The pet comes from the ViewModel's current selection rather than from this screen's navigation
     * argument, because on the Expenses tab there is no such argument — the pet is whichever chip is
     * chosen. Recording an expense with nothing selected would attach it to no animal and violate
     * the foreign key on the expenses table, so the tap is ignored in that case; it is only
     * reachable in the moment before the pet list has loaded.
     */
    private fun openExpenseForm(expenseId: Long) {
        val selectedPetId = viewModel.selectedPetId.value
        if (selectedPetId == ExpenseViewModel.NO_PET_ID) return

        findNavController().navigate(
            R.id.action_expenses_to_add_edit_expense,
            bundleOf(
                AddEditExpenseFragment.ARG_PET_ID to selectedPetId,
                AddEditExpenseFragment.ARG_EXPENSE_ID to expenseId
            )
        )
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state -> render(state) }
                }
                launch {
                    viewModel.events.collect { event -> handleEvent(event) }
                }
                // The chips are a separate flow from the spending because the two change on
                // different rhythms — the pet list moves when an animal is added, the spending
                // every time a receipt is recorded. Combining them would rebuild the chip row on
                // every expense. The pair is combined here because a chip's *selected* state is a
                // function of both the list and the selection.
                launch {
                    combine(
                        viewModel.pets,
                        viewModel.selectedPetId
                    ) { pets, selectedId -> pets to selectedId }
                        .collect { (pets, selectedId) -> renderPets(pets, selectedId) }
                }
            }
        }
    }

    /** Fills the pet picker's chips and marks the chosen one. */
    private fun renderPets(pets: List<Pet>, selectedPetId: Long) {
        if (!viewModel.showsPetPicker) return

        binding.petPicker.bindPets(
            pets = pets,
            selectedPetId = selectedPetId,
            onPetSelected = { petId -> viewModel.selectPet(petId) }
        )

        // A user with no pets at all has nothing to pick from, so the row would be an empty label
        // over an empty strip. The empty state below explains the situation instead.
        binding.petPicker.root.isVisible = pets.isNotEmpty()

        hasNoPets = pets.isEmpty()

        // Recording an expense needs a pet to attach it to, so with none there is nothing the
        // button could do. Disabled rather than hidden: a control that vanishes leaves the user
        // wondering where it went.
        binding.addExpenseFab.isEnabled = pets.isNotEmpty()

        // The empty state's wording depends on this, and the pet list can arrive after the spending
        // state did — so the screen is redrawn rather than waiting for the next emission.
        render(viewModel.uiState.value)
    }

    /** Applies the current state to the views. */
    private fun render(state: ExpensesUiState) {
        renderTitle(state)

        expenseAdapter.submitList(state.items)

        // The list and the empty message occupy the same slot, so exactly one shows. Both stay
        // hidden while loading, which stops "No expenses yet" flashing up before the first result.
        binding.expensesRecyclerView.isVisible = state.items.isNotEmpty()
        binding.emptyExpensesContainer.isVisible = state.hasNoExpenses

        renderEmptyStateWording()
    }

    /**
     * Sets the app bar title.
     *
     * The two ways this screen is reached want different titles. Opened for one animal it is that
     * animal's spending — "Spending on Bella" — which is the whole subject of the screen. As the
     * Expenses tab the pet's name is already on the selected chip an inch below, so the bar keeps
     * the tab's own label and the title does not change under the user every time they tap a
     * different pet.
     */
    private fun renderTitle(state: ExpensesUiState) {
        if (viewModel.showsPetPicker) return

        state.pet?.let { setToolbarTitle(getString(R.string.expenses_title_format, it.name)) }
    }

    /**
     * Chooses which of the two empty states to word.
     *
     * "This pet has no expenses yet" and "you have no pets" both leave the screen with an empty
     * list, but they are entirely different situations — and inviting a brand-new user to record
     * what they spend on a pet they have not added yet would send them looking for a form that
     * cannot exist.
     */
    private fun renderEmptyStateWording() {
        if (hasNoPets) {
            binding.emptyExpensesIcon.setImageResource(R.drawable.ic_pet_placeholder)
            binding.emptyExpensesTitle.setText(R.string.pet_picker_empty_title)
            binding.emptyExpensesMessage.setText(R.string.pet_picker_empty_message)
        } else {
            binding.emptyExpensesIcon.setImageResource(R.drawable.ic_empty_expenses)
            binding.emptyExpensesTitle.setText(R.string.expenses_empty_title)
            binding.emptyExpensesMessage.setText(R.string.expenses_empty_message)
        }
    }

    private fun handleEvent(event: ExpenseEvent) {
        when (event) {
            is ExpenseEvent.Deleted -> showUndoDeleteSnackbar(event)
        }
    }

    /**
     * Offers to undo a swipe-delete.
     *
     * This is what makes the gesture safe to use without a confirmation dialog: the deletion happens
     * immediately, and the Snackbar gives a few seconds to take it back. Anchoring it to the
     * floating action button lifts the bar clear of it, so the undo is never hidden behind the very
     * button that sits in that corner.
     */
    private fun showUndoDeleteSnackbar(event: ExpenseEvent.Deleted) {
        Snackbar.make(
            binding.root,
            getString(R.string.expense_deleted_format, event.expense.title),
            Snackbar.LENGTH_LONG
        )
            .setAnchorView(binding.addExpenseFab)
            .setAction(R.string.action_undo) { viewModel.restoreExpense(event.expense) }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Navigation argument name; must match the one declared in nav_graph.xml. */
        const val ARG_PET_ID = "petId"
    }
}
