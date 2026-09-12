package com.prash.petcare.ui.expense

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentExpensesBinding
import com.prash.petcare.ui.common.SwipeActionCallback
import com.prash.petcare.ui.common.setToolbarTitle
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

    private val petId: Long by lazy { requireArguments().getLong(ARG_PET_ID) }

    private val viewModel: ExpenseViewModel by viewModels {
        ExpenseViewModel.factory(petId)
    }

    private val expenseAdapter = ExpenseAdapter(
        onExpenseClick = { expense -> openExpenseForm(expense.id) }
    )

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
        setUpRecyclerView()
        setUpSwipeGestures()
        setUpClickListeners()
        observeViewModel()
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

    /** Opens the form, either empty or loaded with an existing expense. */
    private fun openExpenseForm(expenseId: Long) {
        findNavController().navigate(
            R.id.action_expenses_to_add_edit_expense,
            bundleOf(
                AddEditExpenseFragment.ARG_PET_ID to petId,
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
            }
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: ExpensesUiState) {
        // Whose spending this is belongs in the app bar rather than being repeated as a heading in
        // the content beneath it.
        state.pet?.let { setToolbarTitle(getString(R.string.expenses_title_format, it.name)) }

        expenseAdapter.submitList(state.items)

        // The list and the empty message occupy the same slot, so exactly one shows. Both stay
        // hidden while loading, which stops "No expenses yet" flashing up before the first result.
        binding.expensesRecyclerView.visibility =
            if (state.items.isNotEmpty()) View.VISIBLE else View.GONE
        binding.emptyExpensesContainer.visibility =
            if (state.hasNoExpenses) View.VISIBLE else View.GONE
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
