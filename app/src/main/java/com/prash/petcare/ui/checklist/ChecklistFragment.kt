package com.prash.petcare.ui.checklist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentChecklistBinding
import kotlinx.coroutines.launch

/**
 * Today's care checklist for one pet, grouped by category, with each task tickable.
 *
 * The list is worked out rather than stored: tasks are repeating templates, so
 * [ChecklistViewModel] decides which of them actually fall due today before grouping them. Ticking
 * a box writes straight back to the database, and the list re-emits from Room as a result — the
 * fragment never updates a row by hand.
 */
class ChecklistFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentChecklistBinding? = null
    private val binding get() = _binding!!

    private val petId: Long by lazy { requireArguments().getLong(ARG_PET_ID) }

    private val viewModel: ChecklistViewModel by viewModels {
        ChecklistViewModel.factory(petId)
    }

    private val checklistAdapter = ChecklistAdapter(
        onTaskChecked = { taskId, isDone -> viewModel.setTaskDone(taskId, isDone) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChecklistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRecyclerView()
        observeViewModel()
    }

    private fun setUpRecyclerView() {
        binding.checklistRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = checklistAdapter
            // Deliberately not setHasFixedSize(true): headers and task rows are different heights,
            // so the list's own size can change as categories appear and disappear.
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: ChecklistUiState) {
        binding.checklistTitleText.text = if (state.petName.isNotEmpty()) {
            getString(R.string.checklist_title_format, state.petName)
        } else {
            // Blank rather than a half-finished title while the name is still being read.
            ""
        }

        binding.checklistProgressText.text = progressText(state)

        checklistAdapter.submitList(state.items)

        // The list and the empty message share the same region, so exactly one is ever shown.
        // Both stay hidden while loading, which stops "Nothing due today" flashing up first.
        binding.checklistRecyclerView.visibility =
            if (state.items.isNotEmpty()) View.VISIBLE else View.GONE
        binding.emptyChecklistContainer.visibility =
            if (state.hasNothingDue) View.VISIBLE else View.GONE
    }

    /** Builds the "2 of 5 tasks done" line, or nothing at all when there is no care due. */
    private fun progressText(state: ChecklistUiState): String =
        if (state.totalCount == 0) {
            ""
        } else {
            // getQuantityString picks the right plural form for the device's language. The first
            // argument selects the form; the two after it fill the placeholders.
            resources.getQuantityString(
                R.plurals.checklist_progress,
                state.totalCount,
                state.doneCount,
                state.totalCount
            )
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
