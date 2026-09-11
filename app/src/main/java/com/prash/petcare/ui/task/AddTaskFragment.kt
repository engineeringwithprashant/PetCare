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
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.prash.petcare.data.local.entity.TaskCategory
import com.prash.petcare.databinding.FragmentAddTaskBinding
import com.prash.petcare.ui.common.formatTimeOfDay
import com.prash.petcare.ui.common.labelRes
import kotlinx.coroutines.launch

/**
 * Form for adding a care task to a routine.
 *
 * A task records what to do and at what time of day. It carries no date: the routine it belongs to
 * decides which days it applies to, which is what lets one "Morning meal" row stand for every day
 * rather than needing a row per day.
 */
class AddTaskFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentAddTaskBinding? = null
    private val binding get() = _binding!!

    private val routineId: Long by lazy { requireArguments().getLong(ARG_ROUTINE_ID) }

    private val viewModel: CareTaskViewModel by viewModels {
        CareTaskViewModel.factory(routineId)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddTaskBinding.inflate(inflater, container, false)
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

        binding.taskTitleInput.doOnTextChanged { _, _, _, _ -> viewModel.clearTitleError() }
    }

    /** Opens the Material time picker, starting from the time already chosen if there is one. */
    private fun showTimePicker() {
        val existing = viewModel.formState.value.timeOfDayMinutes

        val picker = MaterialTimePicker.Builder()
            .setTitleText(getString(com.prash.petcare.R.string.task_time_picker_title))
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
    }

    private fun handleEvent(event: CareTaskEvent) {
        when (event) {
            // The routine screen behind this one already observes the database, so it shows the
            // new task as soon as this screen closes.
            is CareTaskEvent.Saved -> findNavController().navigateUp()

            is CareTaskEvent.Error -> {
                Snackbar.make(binding.root, getString(event.messageRes), Snackbar.LENGTH_LONG)
                    .show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Navigation argument name; must match the one declared in nav_graph.xml. */
        const val ARG_ROUTINE_ID = "routineId"

        /** Tag the FragmentManager uses to restore the time picker after a rotation. */
        private const val TIME_PICKER_TAG = "care_task_time_picker"

        private const val MINUTES_PER_HOUR = 60

        /** 08:00 — a sensible opening position for the picker when no time has been chosen. */
        private const val DEFAULT_TIME_MINUTES = 8 * MINUTES_PER_HOUR
    }
}
