package com.prash.petcare.ui.task

import android.os.Bundle
import android.text.format.DateFormat
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.TaskCategory
import com.prash.petcare.databinding.FragmentAddEditTaskBinding
import com.prash.petcare.ui.common.formatTimeOfDay
import com.prash.petcare.ui.common.labelRes
import kotlinx.coroutines.launch

/**
 * Form for adding a care task to a routine, or changing an existing one.
 *
 * Which mode it runs in depends on the `taskId` navigation argument: [CareTaskViewModel.NO_TASK_ID]
 * means add, anything else means edit. The distinction is the ViewModel's to interpret — this
 * fragment renders whatever state comes back.
 *
 * As the **View** in MVVM it owns no logic: validation, persistence and the scheduling arithmetic
 * all live in [CareTaskViewModel]. What it owns are the things that genuinely need a Context or a
 * view — the time picker and the confirmation dialog.
 */
class AddEditTaskFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentAddEditTaskBinding? = null
    private val binding get() = _binding!!

    private val routineId: Long by lazy { requireArguments().getLong(ARG_ROUTINE_ID) }

    private val taskId: Long by lazy {
        requireArguments().getLong(ARG_TASK_ID, CareTaskViewModel.NO_TASK_ID)
    }

    private val viewModel: CareTaskViewModel by viewModels {
        CareTaskViewModel.factory(routineId, taskId)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddEditTaskBinding.inflate(inflater, container, false)
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
     * The labels and [TaskCategory.entries] are built from the same list in the same order, so the
     * tapped position maps straight back to an enum constant. Reading the category back from the
     * displayed text would break the moment the app is translated.
     */
    private fun setUpCategoryDropdown() {
        val labels = TaskCategory.entries.map { getString(it.labelRes()) }.toTypedArray()
        binding.categoryInput.setSimpleItems(labels)

        binding.categoryInput.setOnItemClickListener { _, _, position, _ ->
            viewModel.onCategorySelected(TaskCategory.entries[position])
        }
    }

    private fun setUpClickListeners() {
        binding.timeInput.setOnClickListener { showTimePicker() }

        binding.saveTaskButton.setOnClickListener {
            viewModel.save(
                title = binding.taskTitleInput.text?.toString().orEmpty(),
                requiredSupplies = binding.suppliesInput.text?.toString().orEmpty(),
                notes = binding.notesInput.text?.toString().orEmpty()
            )
        }

        binding.deleteTaskButton.setOnClickListener { showDeleteConfirmation() }

        binding.taskTitleInput.doOnTextChanged { _, _, _, _ -> viewModel.clearTitleError() }
    }

    /** Opens the Material time picker, starting from the time already chosen if there is one. */
    private fun showTimePicker() {
        val existing = viewModel.formState.value.timeOfDayMinutes

        val picker = MaterialTimePicker.Builder()
            .setTitleText(getString(R.string.task_time_picker_title))
            // Matches the device's own 12- or 24-hour setting rather than forcing one on the user.
            .setTimeFormat(
                if (DateFormat.is24HourFormat(requireContext())) {
                    TimeFormat.CLOCK_24H
                } else {
                    TimeFormat.CLOCK_12H
                }
            )
            .setHour((existing ?: DEFAULT_TIME_MINUTES) / MINUTES_PER_HOUR)
            .setMinute((existing ?: DEFAULT_TIME_MINUTES) % MINUTES_PER_HOUR)
            .build()

        picker.addOnPositiveButtonClickListener {
            // The chosen values are read back off the picker itself, which is how its API reports
            // the selection.
            viewModel.onTimeSelected(picker.hour, picker.minute)
        }

        picker.show(childFragmentManager, TIME_PICKER_TAG)
    }

    /**
     * Asks before deleting.
     *
     * Removing a task takes it out of the routine permanently and cannot be undone, so the dialog
     * names the task rather than asking a generic "are you sure?".
     */
    private fun showDeleteConfirmation() {
        val taskTitle = binding.taskTitleInput.text?.toString()?.trim().orEmpty()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_task_dialog_title)
            .setMessage(getString(R.string.delete_task_dialog_message, taskTitle))
            // Cancel is listed first and does nothing, so the safe choice is the easy one.
            .setNegativeButton(R.string.delete_task_dialog_cancel, null)
            .setPositiveButton(R.string.delete_task_dialog_confirm) { _, _ -> viewModel.delete() }
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
    private fun render(state: CareTaskFormState) {
        binding.screenTitleText.setText(
            if (state.isEditMode) R.string.edit_task_title else R.string.add_task_title
        )

        // Only an existing task can be deleted, so the button has no meaning in add mode.
        binding.deleteTaskButton.visibility = if (state.isEditMode) View.VISIBLE else View.GONE

        binding.taskTitleInputLayout.error = state.titleError?.let { getString(it) }
        binding.timeInputLayout.error = state.timeError?.let { getString(it) }

        // `false` stops the dropdown treating this as typed input and filtering its own list.
        binding.categoryInput.setText(getString(state.category.labelRes()), false)

        binding.timeInput.setText(
            state.timeOfDayMinutes?.let { formatTimeOfDay(requireContext(), it) }.orEmpty()
        )

        val isBusy = state.isLoading || state.isSaving
        binding.progressIndicator.visibility = if (isBusy) View.VISIBLE else View.GONE
        binding.saveTaskButton.isEnabled = !isBusy
        binding.deleteTaskButton.isEnabled = !isBusy
    }

    private fun handleEvent(event: CareTaskEvent) {
        when (event) {
            is CareTaskEvent.PrefillForm -> prefillForm(event.task)

            // Both outcomes simply close the screen. The routine detail screen behind it already
            // observes the database, so its task list updates itself.
            is CareTaskEvent.Saved, is CareTaskEvent.Deleted -> findNavController().navigateUp()

            is CareTaskEvent.Error -> {
                Snackbar.make(binding.root, getString(event.messageRes), Snackbar.LENGTH_LONG)
                    .show()
            }
        }
    }

    /**
     * Writes a loaded task's values into the text fields.
     *
     * Deliberately called once, from a one-shot event, rather than on every state update — writing
     * into an EditText the user is typing in would move their cursor and undo their edits. After a
     * rotation these fields restore themselves from Android's own saved instance state, so there is
     * nothing to repeat.
     */
    private fun prefillForm(task: CareTask) {
        binding.taskTitleInput.setText(task.title)
        binding.suppliesInput.setText(task.requiredSupplies.orEmpty())
        binding.notesInput.setText(task.notes.orEmpty())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Navigation argument names; must match those declared in nav_graph.xml. */
        const val ARG_ROUTINE_ID = "routineId"
        const val ARG_TASK_ID = "taskId"

        /** Tag the FragmentManager uses to restore the time picker after a rotation. */
        private const val TIME_PICKER_TAG = "care_task_time_picker"

        private const val MINUTES_PER_HOUR = 60

        /** 08:00 — a sensible opening position for the picker when no time has been chosen. */
        private const val DEFAULT_TIME_MINUTES = 8 * MINUTES_PER_HOUR
    }
}
