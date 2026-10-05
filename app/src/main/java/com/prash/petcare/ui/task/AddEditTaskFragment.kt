package com.prash.petcare.ui.task

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import com.prash.petcare.ui.common.emojiLabel
import com.prash.petcare.ui.common.setToolbarTitle
import kotlinx.coroutines.launch

/** Form for adding a care task to a routine, or changing an existing one. */
class AddEditTaskFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentAddEditTaskBinding? = null
    private val binding get() = _binding!!

    private val routineId: Long by lazy { requireArguments().getLong(ARG_ROUTINE_ID) }

    private val taskId: Long by lazy {
        requireArguments().getLong(ARG_TASK_ID, CareTaskViewModel.NO_TASK_ID)
    }

    /** Suggested starting values, when the form was opened by "Book a visit" on the Places tab. */
    private val prefill: CareTaskPrefill? by lazy {
        val args = requireArguments()
        val title = args.getString(ARG_PREFILL_TITLE)
        val notes = args.getString(ARG_PREFILL_NOTES)
        val category = args.getString(ARG_PREFILL_CATEGORY)
            ?.let { name -> TaskCategory.entries.firstOrNull { it.name == name } }

        if (title == null && notes == null && category == null) null
        else CareTaskPrefill(title, notes, category)
    }

    private val viewModel: CareTaskViewModel by viewModels {
        CareTaskViewModel.factory(routineId, taskId, prefill)
    }

    /** Whether the notification permission has already been raised on this screen. */
    private var hasOfferedReminders = false

    /** Handles the result of the notification permission request. */
    private val requestNotificationPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            // Granted needs no acknowledgement — the next reminder simply arrives.
            if (!isGranted) onNotificationPermissionDenied()
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

    /** Fills the category dropdown. */
    private fun setUpCategoryDropdown() {
        // "💊 Medication" — the same emoji the Tasks tab's filter chips carry.
        val labels = TaskCategory.entries
            .map { it.emojiLabel(requireContext()) }
            .toTypedArray()
        binding.categoryInput.setSimpleItems(labels)

        binding.categoryInput.setOnItemClickListener { _, _, position, _ ->
            viewModel.onCategorySelected(TaskCategory.entries[position])
        }
    }

    private fun setUpClickListeners() {
        // Focusable so it can be reached by keyboard, D-pad or switch access, with the soft keyboard suppressed.
        binding.timeInput.showSoftInputOnFocus = false
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
            // The chosen values are read back off the picker itself, which is how its API reports the selection.
            viewModel.onTimeSelected(picker.hour, picker.minute)

            // The task now has a moment to be reminded at, so this is where asking to notify makes sense to the user.
            offerReminderNotifications()
        }

        picker.show(childFragmentManager, TIME_PICKER_TAG)
    }

    // Reminder notification permission.

    /** Offers to turn reminder notifications on, if that is still an open question. */
    private fun offerReminderNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasOfferedReminders) return
        if (hasNotificationPermission()) return

        hasOfferedReminders = true
        showReminderRationale()
    }

    /** Explains what the notification is for, then asks. */
    private fun showReminderRationale() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.permission_notifications_title)
            .setMessage(R.string.permission_notifications_message)
            // "Not now" does nothing at all, so declining costs the user nothing and the task still saves normally.
            .setNegativeButton(R.string.permission_not_now, null)
            .setPositiveButton(R.string.permission_continue) { _, _ ->
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .show()
    }

    /** Says what a refusal means, and offers a way back if it was permanent. */
    private fun onNotificationPermissionDenied() {
        // A permission result can arrive after the view has gone — the system prompt is another window.
        val root = _binding?.root ?: return

        val snackbar = Snackbar.make(
            root,
            getString(R.string.permission_notifications_denied),
            Snackbar.LENGTH_LONG
        )

        if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            snackbar.setAction(R.string.permission_open_settings) { openAppSettings() }
        }

        snackbar.show()
    }

    private fun hasNotificationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    /** Opens this app's entry in system settings, where a refused permission can be granted. */
    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", requireContext().packageName, null)
        )
        startActivity(intent)
    }

    /** Asks before deleting. */
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
        // Which mode the form is in is the screen's title, so it goes in the app bar.
        setToolbarTitle(
            getString(if (state.isEditMode) R.string.edit_task_title else R.string.add_task_title)
        )

        // Only an existing task can be deleted, so the button has no meaning in add mode.
        binding.deleteTaskButton.visibility = if (state.isEditMode) View.VISIBLE else View.GONE

        binding.taskTitleInputLayout.error = state.titleError?.let { getString(it) }
        binding.timeInputLayout.error = state.timeError?.let { getString(it) }

        // `false` stops the dropdown treating this as typed input and filtering its own list.
        binding.categoryInput.setText(state.category.emojiLabel(requireContext()), false)

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

            // Only text fields the user has not already started typing in are filled.
            is CareTaskEvent.PrefillDraft -> {
                if (binding.taskTitleInput.text.isNullOrEmpty()) binding.taskTitleInput.setText(event.title)
                if (binding.notesInput.text.isNullOrEmpty()) binding.notesInput.setText(event.notes)
            }

            // Both outcomes simply close the screen. The routine detail screen behind it already observes the database.
            is CareTaskEvent.Saved, is CareTaskEvent.Deleted -> findNavController().navigateUp()

            is CareTaskEvent.Error -> {
                Snackbar.make(binding.root, getString(event.messageRes), Snackbar.LENGTH_LONG)
                    .show()
            }
        }
    }

    /** Writes a loaded task's values into the text fields. */
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
        const val ARG_PREFILL_TITLE = "prefillTitle"
        const val ARG_PREFILL_NOTES = "prefillNotes"
        const val ARG_PREFILL_CATEGORY = "prefillCategory"

        /** Tag the FragmentManager uses to restore the time picker after a rotation. */
        private const val TIME_PICKER_TAG = "care_task_time_picker"

        private const val MINUTES_PER_HOUR = 60

        /** 08:00 — a sensible opening position for the picker when no time has been chosen. */
        private const val DEFAULT_TIME_MINUTES = 8 * MINUTES_PER_HOUR
    }
}
