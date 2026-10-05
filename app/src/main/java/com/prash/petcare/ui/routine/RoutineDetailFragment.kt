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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.databinding.FragmentRoutineDetailBinding
import com.prash.petcare.ui.common.SwipeActionCallback
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.setToolbarTitle
import com.prash.petcare.ui.task.AddEditTaskFragment
import com.prash.petcare.ui.task.CareTaskViewModel
import kotlinx.coroutines.launch

/** Shows one routine and the care tasks inside it, in the order they happen through the day. */
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
        setUpSwipeGestures()
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

    /** Attaches swipe-to-delete to the task list. */
    private fun setUpSwipeGestures() {
        val callback = SwipeActionCallback(
            context = requireContext(),
            // Every row here is a task, so all of them are swipeable — unlike the checklist.
            allowedDirections = { ItemTouchHelper.LEFT },
            onSwipe = { position, _ ->
                // `currentList` is what the adapter is actually showing, which is what the swiped position refers to.
                taskAdapter.currentList.getOrNull(position)?.let { task -> onSwipedToDelete(task) }
            }
        )

        ItemTouchHelper(callback).attachToRecyclerView(binding.tasksRecyclerView)
    }

    /** Deletes a swiped task, offering an undo rather than a confirmation. */
    private fun onSwipedToDelete(task: CareTask) {
        viewModel.deleteTask(task)

        Snackbar.make(
            binding.root,
            getString(R.string.swipe_task_deleted, task.title),
            Snackbar.LENGTH_LONG
        )
            // The whole task object is captured, so undo puts it back exactly.
            .setAction(R.string.action_undo) { viewModel.restoreTask(task) }
            .show()
    }

    private fun setUpClickListeners() {
        // No task id means the form opens ready to create one.
        binding.addTaskFab.setOnClickListener { openTaskForm(CareTaskViewModel.NO_TASK_ID) }

        // The switch's own listener is attached in render() instead.
        binding.deleteRoutineButton.setOnClickListener { showDeleteConfirmation() }
    }

    /** Opens the add/edit task form. */
    private fun openTaskForm(taskId: Long) {
        findNavController().navigate(
            R.id.action_routine_detail_to_add_edit_task,
            bundleOf(
                AddEditTaskFragment.ARG_ROUTINE_ID to routineId,
                AddEditTaskFragment.ARG_TASK_ID to taskId
            )
        )
    }

    /** Asks before deleting. */
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
            // There is nothing left to show once the routine is gone.
            is RoutineDetailEvent.Deleted -> findNavController().navigateUp()
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: RoutineDetailUiState) {
        state.routine?.let { routine ->
            // The routine's name is the screen's title, so it goes in the app bar.
            setToolbarTitle(routine.title)

            val scheduleLabel = getString(routine.repeatUnit.labelRes())
            binding.routineScheduleText.text = if (routine.isActive) {
                scheduleLabel
            } else {
                getString(R.string.routine_paused_format, scheduleLabel)
            }

            // The listener is detached before the state is applied and reattached afterwards.
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
