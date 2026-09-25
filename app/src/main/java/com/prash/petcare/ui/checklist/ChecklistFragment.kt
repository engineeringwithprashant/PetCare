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

/**
 * The ways a checklist can be handed over, in the order they are offered in the chooser.
 *
 * An enum rather than two bare positions in a string array: the dialog's list and the branch that
 * acts on what the user tapped are both built from these entries, so they cannot drift out of step.
 *
 * Private to this file because it is a piece of this screen's presentation. Nothing below the UI
 * layer knows or cares how a checklist travels: the message itself is identical either way.
 *
 * @property labelRes What the user sees for this channel.
 */
private enum class DelegationChannel(@param:StringRes val labelRes: Int) {
    SMS(R.string.delegate_channel_sms),
    EMAIL(R.string.delegate_channel_email)
}

/**
 * The Tasks tab: a week strip to pick a day, a progress card for that day, category and pet filters,
 * and the day's care grouped into morning, afternoon and evening — each task tickable, swipeable and
 * delegable.
 *
 * In MVVM this is the **View**. [ChecklistViewModel] works out which tasks fall on the chosen day,
 * applies the filters and groups the result; this class draws that state and forwards what the user
 * does — a day tapped, a chip chosen, a circle ticked, a card swiped, the phone shaken. It formats
 * dates, counts and names (which need a Context), but decides nothing about the data.
 *
 * **Also one pet's checklist.** Opened from a pet's detail page with a `petId`, the same screen shows
 * that animal alone: the shared header and the pet chips are hidden, and MainActivity shows the
 * normal toolbar with a back arrow. See the note at the top of nav_graph.xml.
 *
 * **Gestures**, all shortcuts to something also reachable by a button TalkBack can find:
 *  - swipe a card **right** to complete it (today only);
 *  - swipe **left** to delete it, with an undo;
 *  - **shake** the phone, or press "Reset day", to clear the ticks shown — after a confirmation.
 *
 * **The delegation flow**, which is the longest part of this class, starts by asking which channel
 * to use. Both channels now show the exact message before anything leaves the app — the user is about
 * to put this text in front of a real person, and being able to read it first is the point.
 *
 * *Text message*: the app sends it itself, so every step is explicit and can stop cleanly — a
 * rationale then the contacts permission, the system contact picker, a preview of the message and its
 * cost in messages, a rationale then the SMS permission, and only then the send.
 *
 * *Email*: no permission and no recipient, because the checklist is handed to the user's own mail app
 * as a draft which they address and send themselves — but the preview comes first all the same, since
 * otherwise the first sight of the message is in another app with a send button beside it.
 */
class ChecklistFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentChecklistBinding? = null
    private val binding get() = _binding!!

    /**
     * Which pet to show, or [ChecklistViewModel.NO_PET_ID] when this screen is the Tasks tab.
     *
     * `arguments?` rather than `requireArguments()`: reached from the bottom navigation bar, this
     * destination arrives with no argument bundle at all, and the default is what selects tab mode.
     */
    private val petId: Long by lazy {
        arguments?.getLong(ARG_PET_ID, ChecklistViewModel.NO_PET_ID)
            ?: ChecklistViewModel.NO_PET_ID
    }

    /**
     * Which account is signed in. Read from the Activity-wide session, because a bottom navigation
     * bar moves between tabs by destination id and has nowhere to attach an argument.
     */
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

    /**
     * The seven week-strip cells, in order. Filled once the binding exists, and read on every render
     * so the cell at index `i` always shows `state.weekDays[i]`.
     */
    private var dayCells: List<ItemWeekDayBinding> = emptyList()

    /**
     * Formatters for the week strip and the progress card's date, created with the view so they use
     * the locale in force at that moment. A change of language recreates the Activity, and with it
     * these.
     *
     * `android.icu` rather than `java.text` for the weekday because only ICU understands the "EEEEEE"
     * pattern — the locale's own two-letter weekday ("Mo", "Tu"), which a `take(2)` on the full name
     * would get wrong in plenty of languages. ICU is part of the platform from API 24, this app's
     * minimum.
     */
    private val shortWeekdayFormat by lazy {
        android.icu.text.SimpleDateFormat("EEEEEE", Locale.getDefault())
    }
    private val dayOfMonthFormat by lazy {
        android.icu.text.SimpleDateFormat("d", Locale.getDefault())
    }
    private val fullDateFormat by lazy {
        // "Thursday 11 September" in the UK, "Thursday, September 11" in the US: the best pattern for
        // those fields is asked of the locale rather than spelled out here.
        val pattern = android.text.format.DateFormat
            .getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
        android.icu.text.SimpleDateFormat(pattern, Locale.getDefault())
    }

    /**
     * Open dialogs, held so they can be dismissed if the view is destroyed — a dialog belongs to the
     * window it was shown in, and one left attached to a destroyed Activity leaks it.
     *
     *  - [previewDialog] holds whichever message confirmation is up. The text branch's is backed by
     *    ViewModel state (the SMS delegation stage), so after a rotation it is simply shown again from
     *    that state; the email branch's is not, for the reason given on [startEmailDelegation].
     *  - [channelDialog], [resetDialog] and [routineDialog] are answered in one tap without leaving the
     *    app; a rotation closes them and the user asks again, having lost nothing. [resetDialog] also
     *    stops a continued shake stacking a second confirmation on the first.
     */
    private var previewDialog: AlertDialog? = null
    private var channelDialog: AlertDialog? = null
    private var resetDialog: AlertDialog? = null
    private var routineDialog: AlertDialog? = null

    /**
     * Watches the accelerometer for a shake. Started in [onResume] and stopped in [onPause]: a
     * registered sensor listener keeps hardware powered, so leaving it running in the background
     * would drain the battery for readings nobody is waiting on.
     */
    private val shakeDetector = ShakeDetector { onShakeDetected() }

    /**
     * Launchers for the trips out of this screen. Registered as properties because
     * `registerForActivityResult` must be called while the fragment is being created, so the callback
     * is in place before a restored fragment can be handed a pending result.
     */
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
                // The flow ends here, and the chosen recipient is discarded so the user restarts
                // cleanly rather than finding a stale confirmation waiting.
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

    /**
     * Picks up a recipient chosen on the profile screen and goes straight to the confirmation preview.
     *
     * Tapping a saved delegation contact navigates here with that person's name and number attached —
     * see the `delegateToName` and `delegateToPhone` arguments in nav_graph.xml. All this does is hand
     * them to the ViewModel exactly as the system contact picker would, which moves the flow to its
     * Preview stage and makes the existing renderer put up the confirmation dialog.
     *
     * **Nothing is skipped that matters.** The channel dialog and the contact picker are bypassed
     * because the user has already answered both questions; the message is still built from the tasks
     * actually outstanding, the SEND_SMS permission is still requested with its rationale, and the
     * exact text is still shown for confirmation before a single message goes out. A second path that
     * skipped any of *those* would be the dangerous one.
     *
     * `savedInstanceState == null` is what keeps this to the first arrival. Without it a rotation
     * would re-run the whole navigation argument and reopen the preview after the user had dismissed
     * it — the arguments stay attached to the destination for as long as it is on the back stack.
     */
    private fun startPrefilledDelegation(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return

        val name = arguments?.getString(ARG_DELEGATE_NAME) ?: return
        val phone = arguments?.getString(ARG_DELEGATE_PHONE) ?: return

        // Nothing outstanding means nothing to hand over, and a preview of an empty checklist would be
        // a confusing thing to confirm. The same guard startDelegation applies, for the same reason.
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

    /**
     * Shows the parts that exist only on the tab, or only for one pet.
     *
     * On the tab: the shared header (the app bar is hidden by MainActivity) and the pet chips. For one
     * pet: neither — the toolbar carries that pet's name and a back arrow, and there is no other pet to
     * choose.
     */
    private fun setUpModeSpecificChrome() {
        val isTab = viewModel.showsPetPicker

        binding.header.root.isVisible = isTab
        binding.petChipScroll.isVisible = isTab

        if (isTab) {
            // The badge opens the notifications screen, as on every other screen that draws this
            // header.
            binding.header.setUp(
                title = R.string.nav_tasks,
                onBadgeClick = { findNavController().navigate(R.id.notificationsFragment) },
                // The monogram opens the profile screen, as on every other screen that draws it.
                onMonogramClick = ::openProfile
            )

            // Keeps the header's mark showing the user's profile photo, falling back to the app's
            // monogram when there is none. See TabHeader.bindHeaderFromSession.
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
            // Not setHasFixedSize(true): the list is wrap_content inside the page's scroll view, so
            // its own height changes as sections appear and disappear.
        }
    }

    /**
     * Attaches the swipe gestures to the task cards.
     *
     * [ItemTouchHelper] detects the drag and animates the card; [SwipeActionCallback] draws what is
     * revealed behind it (a tick on yellow to the right, a bin on red to the left) — the same
     * convention the routine screen uses, so a gesture means the same thing everywhere in the app.
     *
     * Section headers cannot be swiped. Swipe-right is only offered on today and on a task not yet
     * done, since offering it elsewhere would imply something would happen.
     */
    private fun setUpSwipeGestures() {
        val callback = SwipeActionCallback(
            context = requireContext(),
            allowedDirections = { position ->
                // `currentList` is what the adapter is actually showing, which is what the swiped
                // position refers to. Reading ViewModel state instead could disagree mid-update and act
                // on the wrong card.
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

    /**
     * Swiped right: mark the task done.
     *
     * The card stays in the list, now ticked, so its swiped-away view has to be put back:
     * ItemTouchHelper leaves a swiped row translated off-screen on the assumption it is being removed,
     * and rebinding the position resets it.
     */
    private fun onSwipedToComplete(position: Int, row: ChecklistItem.TaskRow) {
        viewModel.setTaskDone(row.task.id, true)
        checklistAdapter.notifyItemChanged(position)
    }

    /**
     * Swiped left: delete the task, offering an undo rather than a confirmation.
     *
     * A confirmation on every swipe would make the shortcut slower than the long way round; an undo
     * keeps it fast and still rescues an accidental swipe. The whole task object is captured, so undo
     * puts it back exactly as it was, same id included.
     *
     * Unlike [onSwipedToComplete] the card is *not* rebound: it is leaving the list, and Room re-emits
     * without it a moment later, at which point DiffUtil removes the row. Rebinding first would snap
     * the card back into place only for it to vanish again — the same approach the routine screen's
     * swipe-to-delete takes.
     */
    private fun onSwipedToDelete(task: CareTask) {
        viewModel.deleteTask(task)

        Snackbar.make(
            binding.root,
            getString(R.string.swipe_task_deleted, task.title),
            Snackbar.LENGTH_LONG
        )
            .setAction(R.string.action_undo) { viewModel.restoreTask(task) }
            .show()
    }

    private fun setUpClickListeners() {
        binding.delegateButton.setOnClickListener { startDelegation() }
        binding.addTaskButton.setOnClickListener { startAddTask() }
        binding.resetDayButton.setOnClickListener { requestReset() }
    }

    // ---------------------------------------------------------------------------------------
    // Reset
    // ---------------------------------------------------------------------------------------

    /**
     * Reacts to the phone being shaken — by asking, never by resetting on its own. A shake can happen
     * by accident in a pocket, and silently wiping a morning's ticks would be no small thing to redo.
     */
    private fun onShakeDetected() {
        requestReset()
    }

    /**
     * The shared entry point for the "Reset day" button and a shake: confirm, then reset.
     *
     * Does nothing when there is nothing to reset — no ticks shown, or a day other than today — and
     * does not stack a second dialog over one already open.
     */
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
            // Fires however the dialog closes, so the reference is always released and a later shake
            // can ask again.
            .setOnDismissListener { resetDialog = null }
            .show()
    }

    // ---------------------------------------------------------------------------------------
    // Add task
    // ---------------------------------------------------------------------------------------

    /**
     * "+ Add task": opens the existing task form in the right routine.
     *
     * A task always belongs to a routine — the routine is what says which days it runs — so the form
     * needs one. With no routines the user is pointed to a pet's page, which is where routines are
     * created; with exactly one the form opens straight away; with several, a short list asks which.
     * The routines offered follow the pet filter, so choosing a pet chip first narrows the question.
     */
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

    // ---------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Independent collectors, each in its own coroutine: collect() never returns, so
                // calling them one after the other would mean only the first ever ran.
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

        // Before the first results there is nothing sensible to draw; after them, an account with no
        // pets gets only the empty state, since a week, filters and actions for nobody would be noise.
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

        // Delegating means telling someone what is left, so with nothing outstanding the button is
        // disabled — not hidden, which would leave the user wondering where it went.
        binding.delegateButton.isEnabled = state.outstandingCount > 0
    }

    /**
     * Sets the toolbar title for a single pet's checklist, e.g. "Tasks for Bella". Only in that mode:
     * the tab has its own header and never touches the toolbar.
     *
     * Until the pet's name has loaded the toolbar keeps the destination's own label, which reads
     * sensibly on its own — better than blanking the title and having it appear a moment later.
     */
    private fun renderTitle(state: ChecklistUiState) {
        if (state.singlePetName.isEmpty()) return
        setToolbarTitle(getString(R.string.checklist_title_format, state.singlePetName))
    }

    /**
     * Fills the seven day cells.
     *
     * Selection is expressed with a single `isSelected` on each cell; the cell's drawable and text
     * colours respond to that state by themselves (see item_week_day.xml). The dot is INVISIBLE rather
     * than GONE when a day has no care, so every cell keeps the same layout.
     */
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

            // One spoken label for the whole cell — "Thursday 11 September, care scheduled" — in place
            // of "TH", "11" read as two fragments. TalkBack adds "selected" from the selected state.
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

    /**
     * Fills both chip rows and marks the chosen chip in each.
     *
     * The rows are keyed by strings so one helper serves both — see ui/common/FilterChips.kt. "All"
     * uses a key no category or pet id can produce.
     */
    private fun renderFilters(state: ChecklistUiState) {
        // Each category chip leads with its emoji — "🍖  Feeding", "🐾  Walk" — so the row is picked
        // out by symbol rather than read word by word. "All" carries none: it is not a category but
        // the absence of a filter, and giving it one would make it look like a ninth option.
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

    /**
     * Words the empty state. "You have no pets" and "nothing matches this day" both leave the list
     * empty, but only one of them is about the day — telling a brand-new user that nothing is
     * scheduled would be a small mystery on their first visit.
     */
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

    // ---------------------------------------------------------------------------------------
    // Delegation flow
    // ---------------------------------------------------------------------------------------

    /** The user tapped Delegate: check there is something to hand over, then ask how to send it. */
    private fun startDelegation() {
        val state = viewModel.uiState.value

        // The button is already disabled in this case; this guards against the state changing between
        // the tap and this running — a task ticked off on another screen, say.
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

    /**
     * Asks whether to send the checklist as a text message or as an email.
     *
     * Both options are always offered, and whether a channel is usable is checked once chosen —
     * filtering beforehand would leave a tablet user with one unexplained option, whereas choosing
     * "Text message" there gets them a sentence saying why it is unavailable. `setItems` rather than
     * two buttons, because a dialog's positive and negative buttons imply "do it" and "don't".
     */
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

    /**
     * SMS branch, step 1: make sure the device can text at all, then ask for contacts access. The
     * telephony check comes first so a Wi-Fi-only tablet is never asked for a permission that could
     * not work.
     */
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

    /**
     * The email branch, step 1: show the exact message for confirmation before leaving the app.
     *
     * **This step did not used to exist**, on the reasoning that the mail app's compose window is
     * itself a preview. It is — but by the time the user reaches it they have been thrown into another
     * app, holding a draft they did not write with a send button in front of them, and the only way to
     * read the message *before* committing to that was to go and then come back. The text branch has
     * always shown its message first ([showPreviewDialog]); this is the same courtesy, and it makes
     * the two channels behave alike.
     *
     * A plain dialog rather than a [DelegationStage], unlike the text branch. That stage exists
     * because the text flow leaves the app to pick a contact and has to survive coming back; nothing
     * has been gathered here, so a rotation that closes this dialog costs the user one tap.
     */
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
            // The subject as well as the body, because the subject is half of what the recipient sees
            // first and it is the only part the user cannot change once the draft is open.
            .setMessage("$summary\n\n$subjectLine\n\n$message\n\n$note")
            .setNegativeButton(R.string.delegate_cancel, null)
            .setPositiveButton(R.string.delegate_email_continue) { _, _ ->
                openEmailDraft(subject, message)
            }
            .setOnDismissListener { previewDialog = null }
            .show()
    }

    /**
     * The email branch, step 2: hand the draft to the user's mail app.
     *
     * Everything past this point is exactly as it was before the preview was added — [EmailComposer]
     * opens a draft and the user sends it from there. The app never sends an email itself.
     */
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

    /**
     * Explains why a permission is needed, then requests it. Shown every time rather than only when
     * `shouldShowRequestPermissionRationale` is true, because that is false on the very first request
     * — exactly when the user has least idea why they are being asked.
     */
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

        // The fragment's own lifecycleScope, not the view's: a picker result can arrive before the
        // view has been recreated, when viewLifecycleOwner does not exist.
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

    /**
     * Reacts to the delegation stage. Driven entirely by ViewModel state, so a rotation part-way
     * through puts the confirmation dialog back rather than losing the chosen recipient.
     */
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

        // The full message verbatim: the user is about to send this exact text to a real person, and
        // the cost in messages is stated alongside it.
        previewDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delegate_preview_title, recipient.name))
            .setMessage("$summary\n\n$message\n\n$partNote")
            .setNegativeButton(R.string.delegate_cancel) { _, _ -> viewModel.onDelegationFinished() }
            .setPositiveButton(R.string.delegate_send) { _, _ -> onSendConfirmed() }
            // Back or a tap outside counts as cancelling, so the flow does not sit half-done.
            .setOnCancelListener { viewModel.onDelegationFinished() }
            // Fires however the dialog closes, including in onDestroyView, so it only releases the
            // reference and never changes the flow's state.
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

    /**
     * Builds the message body from the outstanding tasks on the chosen day.
     *
     * Every route out goes through here — the SMS preview, the SMS send and the email draft — so the
     * text confirmed on screen is guaranteed to be the text that goes out. Pet names are added to each
     * line only when the tasks span more than one pet.
     */
    private fun outstandingMessage(state: ChecklistUiState): String =
        buildDelegationMessage(
            context = requireContext(),
            petLabel = joinNames(state.outstandingPetNames),
            dayStart = state.selectedDay,
            items = state.outstandingItems,
            showPetNames = state.outstandingPetNames.size > 1
        )

    // ---------------------------------------------------------------------------------------
    // Permission helpers
    // ---------------------------------------------------------------------------------------

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), permission) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Explains what a refusal means, and offers a way back if it was permanent. After a refusal,
     * `shouldShowRequestPermissionRationale` returning false means "don't ask again" was chosen — the
     * system will no longer prompt, so the only route left is the app's settings screen.
     */
    private fun onPermissionDenied(permission: String, @StringRes messageRes: Int) {
        val deniedPermanently = !shouldShowRequestPermissionRationale(permission)

        val snackbar = Snackbar.make(binding.root, getString(messageRes), Snackbar.LENGTH_LONG)
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
        Snackbar.make(view, text, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Detach the dialogs from the window being destroyed. The SMS preview reappears from
        // ViewModel state once the view is rebuilt; the others are simply asked again next time.
        previewDialog?.dismiss()
        previewDialog = null
        channelDialog?.dismiss()
        channelDialog = null
        resetDialog?.dismiss()
        resetDialog = null
        routineDialog?.dismiss()
        routineDialog = null

        // The adapter is a property of the fragment, which outlives this view; detaching it stops the
        // destroyed RecyclerView being held alive through it.
        binding.checklistRecyclerView.adapter = null
        dayCells = emptyList()
        _binding = null
    }

    companion object {
        /** Navigation argument name; must match the one declared in nav_graph.xml. */
        const val ARG_PET_ID = "petId"

        /**
         * The delegation prefill arguments, sent by the profile screen when a saved contact is
         * tapped. Must match the names declared on this destination in nav_graph.xml, and the ones
         * ProfileFragment puts in the bundle.
         */
        const val ARG_DELEGATE_NAME = "delegateToName"
        const val ARG_DELEGATE_PHONE = "delegateToPhone"

        /** The chip key for "All" in either filter row — never a category name or a pet id. */
        private const val KEY_ALL = "all"
    }
}
