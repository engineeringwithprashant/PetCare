package com.prash.petcare.ui.routine

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
import androidx.recyclerview.widget.LinearLayoutManager
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentRoutineDetailBinding
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.task.AddTaskFragment
import kotlinx.coroutines.launch

/**
 * Shows one routine and the care tasks inside it, in the order they happen through the day.
 *
 * Tasks are not tickable here. This screen shows the template — what the routine consists of, on
 * every day it runs — whereas ticking things off belongs to a particular day and happens on the
 * checklist screen.
 */
class RoutineDetailFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentRoutineDetailBinding? = null
    private val binding get() = _binding!!

    private val routineId: Long by lazy { requireArguments().getLong(ARG_ROUTINE_ID) }

    private val viewModel: RoutineDetailViewModel by viewModels {
        RoutineDetailViewModel.factory(routineId)
    }

    private val taskAdapter = RoutineTaskAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRoutineDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRecyclerView()
        setUpClickListeners()
        observeViewModel()
    }

    private fun setUpRecyclerView() {
        binding.tasksRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = taskAdapter
            setHasFixedSize(true)
        }
    }

    private fun setUpClickListeners() {
        binding.addTaskFab.setOnClickListener {
            findNavController().navigate(
                R.id.action_routine_detail_to_add_task,
                bundleOf(AddTaskFragment.ARG_ROUTINE_ID to routineId)
            )
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
    private fun render(state: RoutineDetailUiState) {
        state.routine?.let { routine ->
            binding.routineTitleText.text = routine.title

            val scheduleLabel = getString(routine.repeatUnit.labelRes())
            binding.routineScheduleText.text = if (routine.isActive) {
                scheduleLabel
            } else {
                getString(R.string.routine_paused_format, scheduleLabel)
            }
        }

        taskAdapter.submitList(state.tasks)

        // The list and the empty message share the same region, so exactly one is ever shown.
        binding.tasksRecyclerView.visibility =
            if (state.tasks.isNotEmpty()) View.VISIBLE else View.GONE
        binding.emptyTasksContainer.visibility =
            if (state.hasNoTasks) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Navigation argument name; must match the one declared in nav_graph.xml. */
        const val ARG_ROUTINE_ID = "routineId"
    }
}
