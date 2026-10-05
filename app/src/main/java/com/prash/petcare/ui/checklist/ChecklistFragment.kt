package com.prash.petcare.ui.checklist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
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
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.TaskCategory
import com.prash.petcare.databinding.FragmentChecklistBinding
import com.prash.petcare.databinding.ItemWeekDayBinding
import com.prash.petcare.ui.common.ChipOption
import com.prash.petcare.ui.common.SwipeActionCallback
import com.prash.petcare.ui.common.anchorAboveNavBar
import com.prash.petcare.ui.common.bindHeaderFromSession
import com.prash.petcare.ui.common.bindOptions
import com.prash.petcare.ui.common.emojiLabel
import com.prash.petcare.ui.common.openProfile
import com.prash.petcare.ui.common.setToolbarTitle
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.session.SessionViewModel
import com.prash.petcare.ui.task.AddEditTaskFragment
import com.prash.petcare.ui.task.CareTaskViewModel
import com.prash.petcare.util.ContactLookup
import com.prash.petcare.util.ContactReader
import com.prash.petcare.util.EmailComposer
import com.prash.petcare.util.ShakeDetector
import com.prash.petcare.util.SmsSender
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** The ways a checklist can be handed over, in the order they are offered in the chooser. */
private enum class DelegationChannel(@param:StringRes val labelRes: Int) {
    SMS(R.string.delegate_channel_sms),
    EMAIL(R.string.delegate_channel_email)
}

/** The Tasks tab: a week strip to pick a day, a progress card for that day, category and pet filters. */
class ChecklistFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentChecklistBinding? = null
    private val binding get() = _binding!!

    /** Which pet to show, or [ChecklistViewModel.NO_PET_ID] when this screen is the Tasks tab. */
    private val petId: Long by lazy {
        arguments?.getLong(ARG_PET_ID, ChecklistViewModel.NO_PET_ID)
            ?: ChecklistViewModel.NO_PET_ID
    }

    /** Which account is signed in. Read from the Activity-wide session. */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: ChecklistViewModel by viewModels {
        ChecklistViewModel.factory(
            ownerId = sessionViewModel.userId.value,
            petId = petId
        )
    }

    private val checklistAdapter = ChecklistAdapter(
        onTaskChecked = { taskId, isDone -> viewModel.setTaskDone(taskId, isDone) }
    )

    /** The seven week-strip cells, in order. Filled once the binding exists, and read on every render. */
    private var dayCells: List<ItemWeekDayBinding> = emptyList()

    /** Formatters for the week strip and the progress card's date, created with the view. */
    private val shortWeekdayFormat by lazy {
        android.icu.text.SimpleDateFormat("EEEEEE", Locale.getDefault())
    }
    private val dayOfMonthFormat by lazy {
        android.icu.text.SimpleDateFormat("d", Locale.getDefault())
    }
    private val fullDateFormat by lazy {
        // "Thursday 11 September" in the UK, "Thursday, September 11" in the US.
        val pattern = android.text.format.DateFormat
            .getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
        android.icu.text.SimpleDateFormat(pattern, Locale.getDefault())
    }

    /** Open dialogs, held so they can be dismissed if the view is destroyed. */
    private var previewDialog: AlertDialog? = null
    private var channelDialog: AlertDialog? = null
    private var resetDialog: AlertDialog? = null
    private var routineDialog: AlertDialog? = null

    /** Watches the accelerometer for a shake. Started in [onResume] and stopped in [onPause]. */
    private val shakeDetector = ShakeDetector { onShakeDetected() }

    /** Launchers for the trips out of this screen. Registered as properties. */
    private val requestContactsPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                launchContactPicker()
            } else {
                onPermissionDenied(
                    Manifest.permission.READ_CONTACTS,
                    R.string.permission_contacts_denied
                )
            }
        }

    private val requestSmsPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                sendPendingChecklist()
            } else {
                // The flow ends here, and the chosen recipient is discarded so the user restarts cleanly.
                viewModel.onDelegationFinished()
                onPermissionDenied(Manifest.permission.SEND_SMS, R.string.permission_sms_denied)
            }
        }

    private val pickContactLauncher =
        registerForActivityResult(ActivityResultContracts.PickContact()) { contactUri ->
            // Null when the user backed out of the picker without choosing anyone.
            if (contactUri != null) {
                loadChosenContact(contactUri)
            }
        }

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
        setUpModeSpecificChrome()
        setUpRecyclerView()
        setUpSwipeGestures()
        setUpClickListeners()
        observeViewModel()
        startPrefilledDelegation(savedInstanceState)
    }

    /** Picks up a recipient chosen on the profile screen and goes straight to the confirmation preview. */
    private fun startPrefilledDelegation(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return

        val name = arguments?.getString(ARG_DELEGATE_NAME) ?: return
        val phone = arguments?.getString(ARG_DELEGATE_PHONE) ?: return

        // Nothing outstanding means nothing to hand.
        if (viewModel.uiState.value.outstandingCount == 0) {
            showMessage(getString(R.string.profile_delegate_nothing))
            return
        }

        viewModel.onRecipientChosen(name, phone)
    }

    override fun onResume() {
        super.onResume()
        shakeDetector.start(requireContext())
    }

    override fun onPause() {
        super.onPause()
        shakeDetector.stop()
    }

    /** Shows the parts that exist only on the tab, or only for one pet. */
    private fun setUpModeSpecificChrome() {
        val isTab = viewModel.showsPetPicker

        binding.header.root.isVisible = isTab
        binding.petChipScroll.isVisible = isTab

        if (isTab) {
            // The badge opens the notifications screen, as on every other screen that draws this header.
            binding.header.setUp(
                title = R.string.nav_tasks,
                onBadgeClick = { findNavController().navigate(R.id.notificationsFragment) },
                // The monogram opens the profile screen, as on every other screen that draws it.
                onMonogramClick = ::openProfile
            )

            // Keeps the header's mark showing the user's profile photo, falling back to the app's monogram.
            bindHeaderFromSession(binding.header, sessionViewModel)
        }

        dayCells = listOf(
            binding.day0, binding.day1, binding.day2, binding.day3,
            binding.day4, binding.day5, binding.day6
        )
    }

    private fun setUpRecyclerView() {
        binding.checklistRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = checklistAdapter
            // Not setHasFixedSize(true): the list is wrap_content inside the page's scroll view.
        }
    }

    /** Attaches the swipe gestures to the task cards. */
    private fun setUpSwipeGestures() {
        val callback = SwipeActionCallback(
            context = requireContext(),
            allowedDirections = { position ->
                // `currentList` is what the adapter is actually showing, which is what the swiped position refers to.
                val row = checklistAdapter.currentList.getOrNull(position)

                if (row is ChecklistItem.TaskRow) {
                    val canComplete = viewModel.uiState.value.isSelectedDayToday && !row.isDone
                    ItemTouchHelper.LEFT or (if (canComplete) ItemTouchHelper.RIGHT else 0)
                } else {
                    0
                }
            },
            onSwipe = { position, direction ->
                val row = checklistAdapter.currentList.getOrNull(position)
                if (row is ChecklistItem.TaskRow) {
                    if (direction == ItemTouchHelper.RIGHT) {
                        onSwipedToComplete(position, row)
                    } else {
                        onSwipedToDelete(row.task)
                    }
                }
            }
        )

        ItemTouchHelper(callback).attachToRecyclerView(binding.checklistRecyclerView)
    }

    /** Swiped right: mark the task done. */
    private fun onSwipedToComplete(position: Int, row: ChecklistItem.TaskRow) {
        viewModel.setTaskDone(row.task.id, true)
        checklistAdapter.notifyItemChanged(position)
    }

    /** Swiped left: delete the task, offering an undo rather than a confirmation. */
    private fun onSwipedToDelete(task: CareTask) {
        viewModel.deleteTask(task)

        Snackbar.make(
            binding.root,
            getString(R.string.swipe_task_deleted, task.title),
            Snackbar.LENGTH_LONG
        )
            .setAction(R.string.action_undo) { viewModel.restoreTask(task) }
            // Without this the bar drawn over this screen would cover "Undo" — see anchorAboveNavBar.
            .anchorAboveNavBar(this)
            .show()
    }

    private fun setUpClickListeners() {
        binding.delegateButton.setOnClickListener { startDelegation() }
        binding.addTaskButton.setOnClickListener { startAddTask() }
        binding.resetDayButton.setOnClickListener { requestReset() }
    }

    // Reset.

    /** Reacts to the phone being shaken — by asking, never by resetting on its own. */
    private fun onShakeDetected() {
        requestReset()
    }

    /** The shared entry point for the "Reset day" button and a shake: confirm, then reset. */
    private fun requestReset() {
        if (resetDialog != null) return

        val state = viewModel.uiState.value
        if (!state.isSelectedDayToday || state.doneCount == 0) return

        showResetConfirmation(joinNames(state.donePetNames))
    }

    /** Confirms before clearing the ticks. [petLabel] names whose ticks they are. */
    private fun showResetConfirmation(petLabel: String) {
        resetDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reset_dialog_title)
            .setMessage(getString(R.string.reset_dialog_message, petLabel))
            // Cancel does nothing, so the safe choice is the easy one.
            .setNegativeButton(R.string.reset_dialog_cancel, null)
            .setPositiveButton(R.string.reset_dialog_confirm) { _, _ ->
                viewModel.resetDay()
                showMessage(getString(R.string.reset_done))
            }
            // Fires however the dialog closes, so the reference is always released and a later shake can ask again.
            .setOnDismissListener { resetDialog = null }
            .show()
    }

    // Add task.

    /** "+ Add task": opens the existing task form in the right routine. */
    private fun startAddTask() {
        val options = viewModel.uiState.value.routineOptions

        when (options.size) {
            0 -> showMessage(getString(R.string.tasks_add_task_no_routine))

            1 -> openNewTaskForm(options.single().routine.id)

            else -> {
                val labels = options.map {
                    getString(R.string.tasks_add_task_routine_option, it.routine.title, it.petName)
                }.toTypedArray()

                routineDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.tasks_add_task_pick_routine)
                    .setItems(labels) { _, index -> openNewTaskForm(options[index].routine.id) }
                    .setNegativeButton(R.string.delegate_cancel, null)
                    .setOnDismissListener { routineDialog = null }
                    .show()
            }
        }
    }

    /** Opens an empty task form for [routineId]. [CareTaskViewModel.NO_TASK_ID] means "create". */
    private fun openNewTaskForm(routineId: Long) {
        findNavController().navigate(
            R.id.action_checklist_to_add_edit_task,
            bundleOf(
                AddEditTaskFragment.ARG_ROUTINE_ID to routineId,
                AddEditTaskFragment.ARG_TASK_ID to CareTaskViewModel.NO_TASK_ID
            )
        )
    }

    // Rendering.

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Independent collectors, each in its own coroutine: collect() never returns.
                launch {
                    viewModel.uiState.collect { state -> render(state) }
                }
                launch {
                    viewModel.delegationStage.collect { stage -> renderDelegation(stage) }
                }
            }
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: ChecklistUiState) {
        if (viewModel.showsPetPicker) {
        } else {
            renderTitle(state)
        }

        // Before the first results there is nothing sensible to draw; after them.
        val showContent = !state.isLoading && !state.hasNoPets
        binding.weekStrip.isVisible = showContent
        binding.progressCard.isVisible = showContent
        binding.categoryChipScroll.isVisible = showContent
        binding.petChipScroll.isVisible = showContent && viewModel.showsPetPicker
        binding.actionsRow.isVisible = showContent

        if (!state.isLoading) {
            renderWeekStrip(state)
            renderProgressCard(state)
            renderFilters(state)
        }

        checklistAdapter.canCheckOff = state.isSelectedDayToday
        checklistAdapter.submitList(state.items)

        binding.checklistRecyclerView.isVisible = state.items.isNotEmpty()
        binding.emptyChecklistContainer.isVisible = state.hasNothingDue || state.hasNoPets
        renderEmptyStateWording(state)

        // Delegating means telling someone what is left, so with nothing outstanding the button is disabled.
        binding.delegateButton.isEnabled = state.outstandingCount > 0
    }

    /** Sets the toolbar title for a single pet's checklist, e.g. "Tasks for Bella". */
    private fun renderTitle(state: ChecklistUiState) {
        if (state.singlePetName.isEmpty()) return
        setToolbarTitle(getString(R.string.checklist_title_format, state.singlePetName))
    }

    /** Fills the seven day cells. */
    private fun renderWeekStrip(state: ChecklistUiState) {
        dayCells.forEachIndexed { index, cell ->
            val day = state.weekDays.getOrNull(index)
            cell.root.isVisible = day != null
            if (day == null) return@forEachIndexed

            val date = Date(day.dayStart)
            val fullDate = fullDateFormat.format(date)

            cell.weekdayText.text = shortWeekdayFormat.format(date)
            cell.dateText.text = dayOfMonthFormat.format(date)
            cell.dotView.visibility = if (day.hasTasks) View.VISIBLE else View.INVISIBLE
            cell.root.isSelected = day.isSelected

            // One spoken label for the whole cell — "Thursday 11 September, care scheduled" — in place of "TH".
            cell.root.contentDescription = getString(
                if (day.hasTasks) R.string.cd_week_day_has_tasks else R.string.cd_week_day,
                fullDate
            )
            cell.root.setOnClickListener { viewModel.selectDay(day.dayStart) }
        }
    }

    /** Fills the progress card: date, count, reset button, segmented bar and gesture guide. */
    private fun renderProgressCard(state: ChecklistUiState) {
        binding.progressDateText.text = fullDateFormat.format(Date(state.selectedDay))

        val hasTasks = state.totalCount > 0

        // "0 of 0 done" would read as a score; a day with nothing on it says so instead.
        binding.progressCountText.text = if (hasTasks) {
            resources.getQuantityString(
                R.plurals.tasks_progress_count,
                state.totalCount,
                state.doneCount,
                state.totalCount
            )
        } else {
            getString(R.string.tasks_progress_none)
        }

        // The bar is a drawing TalkBack cannot read, so the count carries the full sentence for both.
        binding.progressCountText.contentDescription = if (hasTasks) {
            resources.getQuantityString(
                R.plurals.cd_tasks_progress,
                state.totalCount,
                state.doneCount,
                state.totalCount
            )
        } else {
            null
        }

        binding.progressSegments.isVisible = hasTasks
        binding.progressSegments.setSegments(state.segments)

        // Offered only when there is something to clear, and only on today — see requestReset.
        binding.resetDayButton.isVisible = hasTasks
        binding.resetDayButton.isEnabled = state.isSelectedDayToday && state.doneCount > 0

        binding.progressHelpText.isVisible = hasTasks
        binding.progressHelpText.setText(
            if (state.isSelectedDayToday) R.string.tasks_progress_help
            else R.string.tasks_progress_help_other_day
        )
    }

    /** Fills both chip rows and marks the chosen chip in each. */
    private fun renderFilters(state: ChecklistUiState) {
        // Each category chip leads with its emoji — "🍖 Feeding", "🐾 Walk" — so the row is picked out by symbol.
        val categoryOptions = listOf(ChipOption(KEY_ALL, getString(R.string.tasks_filter_all_categories))) +
            TaskCategory.entries.map { ChipOption(it.name, it.emojiLabel(requireContext())) }

        binding.categoryChipGroup.bindOptions(
            options = categoryOptions,
            selectedKey = state.selectedCategory?.name ?: KEY_ALL,
            chipLayout = R.layout.item_category_chip,
            onSelected = { key ->
                viewModel.selectCategory(TaskCategory.entries.firstOrNull { it.name == key })
            }
        )

        if (!viewModel.showsPetPicker) return

        val petOptions = listOf(ChipOption(KEY_ALL, getString(R.string.tasks_filter_all_pets))) +
            state.pets.map { ChipOption(it.id.toString(), it.name) }

        binding.petChipGroup.bindOptions(
            options = petOptions,
            selectedKey = if (state.selectedPetId == ChecklistViewModel.ALL_PETS) KEY_ALL
            else state.selectedPetId.toString(),
            chipLayout = R.layout.item_pet_filter_chip,
            onSelected = { key ->
                viewModel.selectPet(key.toLongOrNull() ?: ChecklistViewModel.ALL_PETS)
            }
        )
    }

    /** Words the empty state. "You have no pets" and "nothing matches this day" both leave the list empty. */
    private fun renderEmptyStateWording(state: ChecklistUiState) {
        if (state.hasNoPets) {
            binding.emptyChecklistIcon.setImageResource(R.drawable.ic_pet_placeholder)
            binding.emptyChecklistTitle.setText(R.string.pet_picker_empty_title)
            binding.emptyChecklistMessage.setText(R.string.pet_picker_empty_message)
        } else {
            binding.emptyChecklistIcon.setImageResource(R.drawable.ic_empty_tasks)
            binding.emptyChecklistTitle.setText(R.string.tasks_empty_title)
            binding.emptyChecklistMessage.setText(R.string.tasks_empty_message)
        }
    }

    /** Joins pet names into one phrase — "Krishala, Ddjw" — with the locale's list separator. */
    private fun joinNames(names: List<String>): String =
        names.joinToString(getString(R.string.list_separator))

    // Delegation flow.

    /** The user tapped Delegate: check there is something to hand over, then ask how to send it. */
    private fun startDelegation() {
        val state = viewModel.uiState.value

        // The button is already disabled in this case.
        if (state.outstandingCount == 0) {
            showMessage(
                getString(
                    if (state.isAllDone) R.string.checklist_all_done
                    else R.string.delegate_nothing_to_send
                )
            )
            return
        }

        showChannelChooser()
    }

    /** Asks whether to send the checklist as a text message or as an email. */
    private fun showChannelChooser() {
        val channels = DelegationChannel.entries
        val labels = channels.map { getString(it.labelRes) }.toTypedArray()

        channelDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delegate_channel_title)
            .setItems(labels) { _, index ->
                when (channels[index]) {
                    DelegationChannel.SMS -> startSmsDelegation()
                    DelegationChannel.EMAIL -> startEmailDelegation()
                }
            }
            .setNegativeButton(R.string.delegate_cancel, null)
            .setOnDismissListener { channelDialog = null }
            .show()
    }

    /** SMS branch, step 1: make sure the device can text at all, then ask for contacts access. */
    private fun startSmsDelegation() {
        if (!SmsSender.isSmsSupported(requireContext())) {
            showMessage(getString(R.string.delegate_no_sms_support))
            return
        }

        if (hasPermission(Manifest.permission.READ_CONTACTS)) {
            launchContactPicker()
        } else {
            showRationaleThenRequest(
                permission = Manifest.permission.READ_CONTACTS,
                titleRes = R.string.permission_contacts_title,
                messageRes = R.string.permission_contacts_message,
                launcher = requestContactsPermissionLauncher
            )
        }
    }

    /** The email branch, step 1: show the exact message for confirmation before leaving the app. */
    private fun startEmailDelegation() {
        val state = viewModel.uiState.value
        val subject = buildDelegationSubject(requireContext(), joinNames(state.outstandingPetNames))
        val message = outstandingMessage(state)

        // "4 remaining tasks for Max" — a sanity check of the scope without reading the whole body.
        val summary = resources.getQuantityString(
            R.plurals.delegate_outstanding_summary,
            state.outstandingCount,
            state.outstandingCount,
            joinNames(state.outstandingPetNames)
        )

        val subjectLine = getString(R.string.delegate_email_preview_subject, subject)
        val note = getString(R.string.delegate_email_preview_note)

        previewDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delegate_email_preview_title)
            // The subject as well as the body.
            .setMessage("$summary\n\n$subjectLine\n\n$message\n\n$note")
            .setNegativeButton(R.string.delegate_cancel, null)
            .setPositiveButton(R.string.delegate_email_continue) { _, _ ->
                openEmailDraft(subject, message)
            }
            .setOnDismissListener { previewDialog = null }
            .show()
    }

    /** The email branch, step 2: hand the draft to the user's mail app. */
    private fun openEmailDraft(subject: String, message: String) {
        val wasOpened = EmailComposer.compose(
            context = requireContext(),
            subject = subject,
            body = message,
            chooserTitle = getString(R.string.delegate_email_chooser_title)
        )

        // No mail app set up is a situation to explain, not an error: the text channel is still there.
        if (!wasOpened) {
            showMessage(getString(R.string.delegate_no_email_app))
        }
    }

    /** Explains why a permission is needed, then requests it. */
    private fun showRationaleThenRequest(
        permission: String,
        @StringRes titleRes: Int,
        @StringRes messageRes: Int,
        launcher: ActivityResultLauncher<String>
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setNegativeButton(R.string.permission_not_now, null)
            .setPositiveButton(R.string.permission_continue) { _, _ -> launcher.launch(permission) }
            .show()
    }

    /** SMS branch, step 2: open the system contact picker. */
    private fun launchContactPicker() {
        pickContactLauncher.launch(null)
    }

    /** SMS branch, step 2b: read the chosen contact's name and number. */
    private fun loadChosenContact(contactUri: Uri) {
        val appContext = requireContext().applicationContext

        // The fragment's own lifecycleScope, not the view's.
        lifecycleScope.launch {
            when (val lookup = ContactReader.read(appContext, contactUri)) {
                is ContactLookup.Found ->
                    viewModel.onRecipientChosen(lookup.name, lookup.phoneNumber)

                is ContactLookup.NoPhoneNumber ->
                    showMessage(getString(R.string.delegate_contact_no_number, lookup.name))

                is ContactLookup.Unreadable ->
                    showMessage(getString(R.string.delegate_contact_unreadable))
            }
        }
    }

    /** Reacts to the delegation stage. Driven entirely by ViewModel state. */
    private fun renderDelegation(stage: DelegationStage) {
        when (stage) {
            is DelegationStage.Preview -> {
                // Guarded so re-collecting the same state does not stack a second dialog.
                if (previewDialog == null) {
                    showPreviewDialog(stage.recipient)
                }
            }

            is DelegationStage.Sending, is DelegationStage.Idle -> Unit
        }
    }

    /** SMS branch, step 3: show the exact message for confirmation before anything is sent. */
    private fun showPreviewDialog(recipient: DelegationRecipient) {
        val state = viewModel.uiState.value
        val message = outstandingMessage(state)

        // "4 remaining tasks for Max" — a sanity check of the scope without reading the whole body.
        val summary = resources.getQuantityString(
            R.plurals.delegate_outstanding_summary,
            state.outstandingCount,
            state.outstandingCount,
            joinNames(state.outstandingPetNames)
        )

        val partCount = SmsSender.partCount(requireContext(), message)
        val partNote = resources.getQuantityString(R.plurals.delegate_part_count, partCount, partCount)

        // The full message verbatim: the user is about to send this exact text to a real person.
        previewDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delegate_preview_title, recipient.name))
            .setMessage("$summary\n\n$message\n\n$partNote")
            .setNegativeButton(R.string.delegate_cancel) { _, _ -> viewModel.onDelegationFinished() }
            .setPositiveButton(R.string.delegate_send) { _, _ -> onSendConfirmed() }
            // Back or a tap outside counts as cancelling, so the flow does not sit half-done.
            .setOnCancelListener { viewModel.onDelegationFinished() }
            // Fires however the dialog closes, including in onDestroyView.
            .setOnDismissListener { previewDialog = null }
            .show()
    }

    /** SMS branch, step 4: the user confirmed; make sure there is permission to send. */
    private fun onSendConfirmed() {
        viewModel.onSendConfirmed()

        if (hasPermission(Manifest.permission.SEND_SMS)) {
            sendPendingChecklist()
        } else {
            showRationaleThenRequest(
                permission = Manifest.permission.SEND_SMS,
                titleRes = R.string.permission_sms_title,
                messageRes = R.string.permission_sms_message,
                launcher = requestSmsPermissionLauncher
            )
        }
    }

    /** SMS branch, step 5: send. */
    private fun sendPendingChecklist() {
        val recipient = (viewModel.delegationStage.value as? DelegationStage.Sending)?.recipient
            ?: return

        val message = outstandingMessage(viewModel.uiState.value)
        val wasSent = SmsSender.send(requireContext(), recipient.phoneNumber, message)

        showMessage(
            if (wasSent) getString(R.string.delegate_sent, recipient.name)
            else getString(R.string.delegate_failed)
        )

        viewModel.onDelegationFinished()
    }

    /** Builds the message body from the outstanding tasks on the chosen day. */
    private fun outstandingMessage(state: ChecklistUiState): String =
        buildDelegationMessage(
            context = requireContext(),
            petLabel = joinNames(state.outstandingPetNames),
            dayStart = state.selectedDay,
            items = state.outstandingItems,
            showPetNames = state.outstandingPetNames.size > 1
        )

    // Permission helpers.

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), permission) ==
            PackageManager.PERMISSION_GRANTED

    /** Explains what a refusal means, and offers a way back if it was permanent. */
    private fun onPermissionDenied(permission: String, @StringRes messageRes: Int) {
        val deniedPermanently = !shouldShowRequestPermissionRationale(permission)

        val snackbar = Snackbar.make(binding.root, getString(messageRes), Snackbar.LENGTH_LONG)
            .anchorAboveNavBar(this)
        if (deniedPermanently) {
            snackbar.setAction(R.string.permission_open_settings) { openAppSettings() }
        }
        snackbar.show()
    }

    /** Opens this app's entry in system settings, where a refused permission can be granted. */
    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", requireContext().packageName, null)
        )
        startActivity(intent)
    }

    /** Shows a brief message. Safe to call after the view is gone, when it simply does nothing. */
    private fun showMessage(text: String) {
        val view = _binding?.root ?: return
        Snackbar.make(view, text, Snackbar.LENGTH_LONG).anchorAboveNavBar(this).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Detach the dialogs from the window being destroyed.
        previewDialog?.dismiss()
        previewDialog = null
        channelDialog?.dismiss()
        channelDialog = null
        resetDialog?.dismiss()
        resetDialog = null
        routineDialog?.dismiss()
        routineDialog = null

        // The adapter is a property of the fragment, which outlives this view.
        binding.checklistRecyclerView.adapter = null
        dayCells = emptyList()
        _binding = null
    }

    companion object {
        /** Navigation argument name; must match the one declared in nav_graph.xml. */
        const val ARG_PET_ID = "petId"

        /** The delegation prefill arguments, sent by the profile screen when a saved contact is tapped. */
        const val ARG_DELEGATE_NAME = "delegateToName"
        const val ARG_DELEGATE_PHONE = "delegateToPhone"

        /** The chip key for "All" in either filter row — never a category name or a pet id. */
        private const val KEY_ALL = "all"
    }
}
