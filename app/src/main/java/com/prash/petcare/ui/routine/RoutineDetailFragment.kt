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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentRoutineDetailBinding
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.task.AddEditTaskFragment
import com.prash.petcare.ui.task.CareTaskViewModel
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

    private val taskAdapter = RoutineTaskAdapter(
        onTaskClick = { task -> openTaskForm(task.id) }
    )

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
        // No task id means the form opens ready to create one.
        binding.addTaskFab.setOnClickListener { openTaskForm(CareTaskViewModel.NO_TASK_ID) }

        // The switch's own listener is attached in render() instead, because it has to be detached
        // and reattached around each state update to avoid writing the value straight back.
        binding.deleteRoutineButton.setOnClickListener { showDeleteConfirmation() }
    }

    /**
     * Opens the add/edit task form.
     *
     * The same destination covers both cases: [CareTaskViewModel.NO_TASK_ID] opens an empty form,
     * and a real id opens that task with its fields already filled in.
     */
    private fun openTaskForm(taskId: Long) {
        findNavController().navigate(
            R.id.action_routine_detail_to_add_edit_task,
            bundleOf(
                AddEditTaskFragment.ARG_ROUTINE_ID to routineId,
                AddEditTaskFragment.ARG_TASK_ID to taskId
            )
        )
    }

    /**
     * Asks before deleting.
     *
     * Deleting a routine also removes every task inside it and cannot be undone, so the dialog
     * spells out the consequence rather than just asking "are you sure?" — and points at pausing as
     * the reversible alternative, since that is what most people reaching for delete actually want.
     */
    private fun showDeleteConfirmation() {
        val routineName = viewModel.uiState.value.routine?.title ?: return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_routine_dialog_title)
            .setMessage(getString(R.string.delete_routine_dialog_message, routineName))
            // Cancel is listed first and does nothing, so the safe choice is the easy one.
            .setNegativeButton(R.string.delete_routine_dialog_cancel, null)
            .setPositiveButton(R.string.delete_routine_dialog_confirm) { _, _ ->
                viewModel.delete()
            }
            .show()
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

    private fun handleEvent(event: RoutineDetailEvent) {
        when (event) {
            // There is nothing left to show once the routine is gone. The pet detail screen behind
            // this one already observes the database, so its list updates by itself.
            is RoutineDetailEvent.Deleted -> findNavController().navigateUp()
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

            // The listener is detached before the state is applied and reattached afterwards.
            // Without that, reflecting the database's value back onto the switch would fire the
            // listener and write it straight back again — a write for every read.
            binding.activeSwitch.setOnCheckedChangeListener(null)
            binding.activeSwitch.isChecked = routine.isActive
            binding.activeSwitch.setOnCheckedChangeListener { _, isChecked ->
                viewModel.setActive(isChecked)
            }
        }

        // Nothing to pause or delete until the routine has actually loaded.
        val hasRoutine = state.routine != null
        binding.activeSwitch.isEnabled = hasRoutine
        binding.deleteRoutineButton.isEnabled = hasRoutine

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
