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
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentChecklistBinding
import com.prash.petcare.ui.common.SwipeActionCallback
import com.prash.petcare.ui.common.setToolbarTitle
import com.prash.petcare.util.ContactLookup
import com.prash.petcare.util.ContactReader
import com.prash.petcare.util.EmailComposer
import com.prash.petcare.util.ShakeDetector
import com.prash.petcare.util.SmsSender
import kotlinx.coroutines.launch

/**
 * The ways a checklist can be handed over, in the order they are offered in the chooser.
 *
 * An enum rather than two bare positions in a string array: the dialog's list and the branch that
 * acts on what the user tapped are both built from these entries, so they cannot drift out of step
 * with one another — and a third channel would be a single entry here rather than a new index to
 * thread through two places.
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
 * Today's care checklist for one pet, grouped by category, with each task tickable — and the
 * ability to hand the whole list to someone looking after the animal, by text message or by email.
 *
 * The list is worked out rather than stored: tasks are repeating templates, so [ChecklistViewModel]
 * decides which of them fall due today before grouping them. Ticking a box writes straight back to
 * the database and the list re-emits from Room as a result.
 *
 * **The delegation flow**, which is most of this class, starts by asking which channel to use. The
 * two branches are deliberately very different lengths, because they differ in who does the sending.
 *
 * *Text message* — the app sends it, so every step is the app's responsibility. It runs in strict
 * order and can stop at any step without leaving anything half-done:
 *  1. a rationale dialog explains why contacts access is needed, then the permission is requested;
 *  2. the system contact picker opens and the chosen contact's number is looked up;
 *  3. the exact message is shown for confirmation, with how many texts it will cost;
 *  4. a second rationale dialog explains the SMS permission, which is then requested;
 *  5. only then is anything sent.
 *
 * *Email* — the app sends nothing. The checklist is handed to the user's own mail app as a draft,
 * and they address it and send it from there, so there is no permission to ask for, no recipient to
 * look up and no preview to show: the compose window they land in is the preview.
 *
 * Either way nothing is read or sent without the user having asked for it and confirmed it.
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

    /**
     * The confirmation dialog, held so it can be dismissed if the screen is destroyed.
     *
     * A dialog belongs to the window it was shown in, so leaving one attached to a destroyed
     * Activity leaks it. The delegation stage lives in the ViewModel, so after a rotation the
     * dialog is simply shown again from that state.
     */
    private var previewDialog: AlertDialog? = null

    /**
     * The "text message or email?" chooser, held so it can be dismissed if the screen is destroyed.
     *
     * Unlike [previewDialog] this is not backed by ViewModel state, and does not need to be: it is
     * answered in a single tap without leaving the app, so there is no trip out of the app that
     * could destroy the screen mid-question. A rotation simply closes it and the user taps Delegate
     * again, having lost nothing.
     */
    private var channelDialog: AlertDialog? = null

    /**
     * The shake-to-reset confirmation, held for the same reason as [previewDialog] and also so a
     * continued shake cannot stack a second copy on top of the first.
     */
    private var resetDialog: AlertDialog? = null

    /**
     * Watches the accelerometer for a shake.
     *
     * Started in [onResume] and stopped in [onPause]: a registered sensor listener keeps hardware
     * powered, so leaving it running while the screen is in the background would drain the battery
     * for readings nobody is waiting on.
     */
    private val shakeDetector = ShakeDetector { onShakeDetected() }

    /**
     * Launchers for the trips out of this screen.
     *
     * All three are registered as properties because `registerForActivityResult` must be called
     * while the fragment is being created — later is too late, since the callback has to be in
     * place before a restored fragment can be handed a pending result.
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
                // The flow ends here, but the chosen recipient is discarded so the user restarts
                // cleanly rather than finding a stale confirmation waiting.
                viewModel.onDelegationFinished()
                onPermissionDenied(
                    Manifest.permission.SEND_SMS,
                    R.string.permission_sms_denied
                )
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
        setUpRecyclerView()
        setUpSwipeGestures()
        setUpClickListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        shakeDetector.start(requireContext())
    }

    override fun onPause() {
        super.onPause()
        shakeDetector.stop()
    }

    private fun setUpRecyclerView() {
        binding.checklistRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = checklistAdapter
            // Deliberately not setHasFixedSize(true): headers and task rows are different heights,
            // so the list's own size changes as categories appear and disappear.
        }
    }

    /**
     * Attaches swipe-to-complete to the checklist.
     *
     * Only swipe-right is offered here. Swipe-left means delete across the app, and a checklist row
     * is a repeating template rather than a one-day copy — deleting it from a view of *today* would
     * quietly remove it from every future day too. Task deletion therefore lives on the routine
     * detail screen, which is where tasks are managed.
     *
     * [ItemTouchHelper] does the gesture detection and animation;
     * [com.prash.petcare.ui.common.SwipeActionCallback] draws the background and calls back here.
     */
    private fun setUpSwipeGestures() {
        val callback = SwipeActionCallback(
            context = requireContext(),
            allowedDirections = { position ->
                // `currentList` is what the adapter is actually showing, which is what the swiped
                // position refers to. Reading from ViewModel state instead could disagree with it
                // mid-update and act on the wrong row.
                val item = checklistAdapter.currentList.getOrNull(position)

                // Category headings cannot be completed, and neither can a task already ticked off
                // today — offering the gesture there would imply something would happen.
                if (item is ChecklistItem.TaskRow && !item.isDoneToday) {
                    ItemTouchHelper.RIGHT
                } else {
                    0
                }
            },
            onSwipe = { position, _ ->
                val row = checklistAdapter.currentList.getOrNull(position)
                if (row is ChecklistItem.TaskRow) {
                    onSwipedToComplete(position, row)
                }
            }
        )

        ItemTouchHelper(callback).attachToRecyclerView(binding.checklistRecyclerView)
    }

    /**
     * Swiped right: mark the task done.
     *
     * The row stays on the checklist, now ticked, so its swiped-away view has to be put back.
     * ItemTouchHelper leaves a swiped row translated off-screen on the assumption it is about to be
     * removed; rebinding the position resets that. Without this the row would simply vanish until
     * the next time the list was rebuilt.
     */
    private fun onSwipedToComplete(position: Int, row: ChecklistItem.TaskRow) {
        viewModel.setTaskDone(row.task.id, true)
        checklistAdapter.notifyItemChanged(position)
    }

    /**
     * Reacts to the phone being shaken.
     *
     * The gesture only ever asks a question — it never resets anything on its own. A shake can
     * happen by accident in a pocket or a bag, and silently wiping a morning's worth of ticked-off
     * care would be no small thing to undo by hand.
     */
    private fun onShakeDetected() {
        // Already asking; a continued shake should not stack dialogs.
        if (resetDialog != null) return

        // Nothing ticked off means nothing to reset, so the question would be pointless.
        if (viewModel.uiState.value.doneCount == 0) return

        showResetConfirmation()
    }

    /** Confirms before clearing the day's progress. */
    private fun showResetConfirmation() {
        val petName = viewModel.uiState.value.petName

        resetDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reset_dialog_title)
            .setMessage(getString(R.string.reset_dialog_message, petName))
            // Cancel is listed first and does nothing, so the safe choice is the easy one.
            .setNegativeButton(R.string.reset_dialog_cancel, null)
            .setPositiveButton(R.string.reset_dialog_confirm) { _, _ ->
                viewModel.resetTodaysChecklist()
                showMessage(getString(R.string.reset_done))
            }
            // Fires however the dialog closes, so the reference is always released and a later
            // shake can ask again.
            .setOnDismissListener { resetDialog = null }
            .show()
    }

    private fun setUpClickListeners() {
        binding.delegateFab.setOnClickListener { startDelegation() }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Two independent collectors, each in its own coroutine: collect() never returns,
                // so calling them in sequence would mean the second never ran.
                launch {
                    viewModel.uiState.collect { state -> render(state) }
                }
                launch {
                    viewModel.delegationStage.collect { stage -> renderDelegation(stage) }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Checklist rendering
    // ---------------------------------------------------------------------------------------

    /** Applies the current checklist state to the views. */
    private fun render(state: ChecklistUiState) {
        // The pet's name only arrives once the database read finishes. Until then the app bar
        // keeps the destination's own label, which reads sensibly on its own — better than
        // blanking the title and having it appear a moment later.
        if (state.petName.isNotEmpty()) {
            setToolbarTitle(getString(R.string.checklist_title_format, state.petName))
        }

        binding.checklistProgressText.text = progressText(state)

        checklistAdapter.submitList(state.items)

        // The list and the empty message share the same region, so exactly one is ever shown. Both
        // stay hidden while loading, which stops "Nothing due today" flashing up first.
        binding.checklistRecyclerView.visibility =
            if (state.items.isNotEmpty()) View.VISIBLE else View.GONE
        binding.emptyChecklistContainer.visibility =
            if (state.hasNothingDue) View.VISIBLE else View.GONE

        // Shown only when there was care due and it has all been finished — not when nothing was
        // scheduled in the first place, which the empty state already explains.
        binding.allDoneText.visibility = if (state.isAllDone) View.VISIBLE else View.GONE

        // Delegating means telling someone what still needs doing, so with nothing outstanding
        // there is nothing to send. The button is disabled rather than hidden: a control that
        // vanishes leaves the user wondering where it went, whereas a greyed-out one alongside the
        // "everything is done" message explains itself.
        binding.delegateFab.isEnabled = state.outstandingCount > 0
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

    // ---------------------------------------------------------------------------------------
    // Delegation flow
    // ---------------------------------------------------------------------------------------

    /** The user tapped Delegate: check there is something to hand over, then ask how to send it. */
    private fun startDelegation() {
        val state = viewModel.uiState.value

        // The button is already disabled in this case; this is a guard against the state changing
        // between the tap and this running — a task ticked off on another screen, say.
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
     * Both options are always offered, and whether a channel is actually usable is checked only
     * once it has been chosen. Filtering the list beforehand would leave the user with a single
     * unexplained option on a tablet, whereas choosing "Text message" there gets them a sentence
     * saying why it is unavailable.
     *
     * `setItems` rather than two buttons: a dialog's positive and negative buttons carry a sense of
     * "do it" and "don't", which is wrong for two equal choices where either one goes ahead.
     */
    private fun showChannelChooser() {
        val channels = DelegationChannel.entries

        // Built from the enum in its own order, so the label the user taps and the branch that runs
        // are guaranteed to be the same channel.
        val labels = channels.map { getString(it.labelRes) }.toTypedArray()

        channelDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delegate_channel_title)
            .setItems(labels) { _, index ->
                when (channels[index]) {
                    DelegationChannel.SMS -> startSmsDelegation()
                    DelegationChannel.EMAIL -> startEmailDelegation()
                }
            }
            // Backing out does nothing at all, so changing one's mind costs nothing.
            .setNegativeButton(R.string.delegate_cancel, null)
            .setOnDismissListener { channelDialog = null }
            .show()
    }

    /**
     * SMS branch, step 1: make sure the device can text at all, then ask for contacts access.
     *
     * The telephony check comes before any permission is requested: prompting for SMS access on a
     * tablet or a Wi-Fi-only device would ask the user for something that could never work.
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
     * The email branch, in full — it is this short because the app does not do the sending.
     *
     * [EmailComposer] opens a draft in the user's mail app and PetCare's part is over. There is no
     * preview dialog here, unlike the SMS branch, and that is not an omission: showing the message
     * for confirmation and then immediately handing it to a compose window that shows the very same
     * text would be asking the same question twice. Nothing leaves the device unless the user
     * addresses the draft and sends it themselves.
     *
     * The body comes from the same builder the text message uses, so both channels describe the
     * checklist identically; only the subject line is particular to email.
     */
    private fun startEmailDelegation() {
        val state = viewModel.uiState.value

        val wasOpened = EmailComposer.compose(
            context = requireContext(),
            subject = buildDelegationSubject(requireContext(), state.petName),
            body = outstandingMessage(state),
            chooserTitle = getString(R.string.delegate_email_chooser_title)
        )

        // A device with no mail app set up is a situation to explain, not an error: the checklist
        // itself is untouched and the text message channel is still there.
        if (!wasOpened) {
            showMessage(getString(R.string.delegate_no_email_app))
        }
    }

    /**
     * Explains why a permission is needed, then requests it.
     *
     * Shared by both points in the SMS branch that need one — contacts at step 1, SMS at step 4.
     *
     * The rationale is shown every time rather than only when
     * `shouldShowRequestPermissionRationale` returns true. That helper is false on the very first
     * request, which is exactly when the user has least idea why they are being asked — and both
     * permissions here are ones people are rightly cautious about granting.
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
            // "Not now" does nothing at all, so declining is the easy, consequence-free choice.
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
        // view has been recreated, and viewLifecycleOwner does not exist at that point.
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
     * Reacts to the delegation stage.
     *
     * Driven entirely by ViewModel state, so a rotation part-way through the flow puts the
     * confirmation dialog back rather than losing the chosen recipient.
     *
     * Only the SMS branch has stages to track. The email branch leaves and returns in one step, and
     * the draft it produced is held by the mail app rather than by this screen, so there is nothing
     * for it to restore.
     */
    private fun renderDelegation(stage: DelegationStage) {
        when (stage) {
            is DelegationStage.Preview -> {
                // Guarded so re-collecting the same state does not stack a second dialog on top.
                if (previewDialog == null) {
                    showPreviewDialog(stage.recipient)
                }
            }

            // In both other stages no dialog should be on screen. Sending is reached by the user
            // confirming, which dismisses it themselves.
            is DelegationStage.Sending, is DelegationStage.Idle -> Unit
        }
    }

    /** SMS branch, step 3: show the exact message for confirmation before anything is sent. */
    private fun showPreviewDialog(recipient: DelegationRecipient) {
        val state = viewModel.uiState.value
        val message = outstandingMessage(state)

        // Summarises what is being handed over, so the user can sanity-check the scope at a glance
        // without reading the whole message body — "4 remaining tasks for Max".
        val summary = resources.getQuantityString(
            R.plurals.delegate_outstanding_summary,
            state.outstandingCount,
            state.outstandingCount,
            state.petName
        )

        val partCount = SmsSender.partCount(requireContext(), message)
        val partNote = resources.getQuantityString(
            R.plurals.delegate_part_count,
            partCount,
            partCount
        )

        // The full message is shown verbatim, not a summary: the user is about to send this exact
        // text to a real person, and the cost in messages is stated alongside it.
        previewDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delegate_preview_title, recipient.name))
            .setMessage("$summary\n\n$message\n\n$partNote")
            .setNegativeButton(R.string.delegate_cancel) { _, _ -> viewModel.onDelegationFinished() }
            .setPositiveButton(R.string.delegate_send) { _, _ -> onSendConfirmed() }
            // Back button or a tap outside counts as cancelling, so the flow does not sit half-done.
            .setOnCancelListener { viewModel.onDelegationFinished() }
            // Fires however the dialog closes, including being dismissed in onDestroyView, so it
            // only releases the reference and never changes the flow's state.
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
            if (wasSent) {
                getString(R.string.delegate_sent, recipient.name)
            } else {
                getString(R.string.delegate_failed)
            }
        )

        viewModel.onDelegationFinished()
    }

    /**
     * Builds the message body from the outstanding tasks only.
     *
     * Every route out of this screen goes through here — the SMS preview, the SMS send, and the
     * email draft — so the text confirmed on screen is guaranteed to be the text that goes out, and
     * a carer gets the same checklist whichever way it reaches them.
     */
    private fun outstandingMessage(state: ChecklistUiState): String =
        buildDelegationMessage(requireContext(), state.petName, state.outstandingItems)

    // ---------------------------------------------------------------------------------------
    // Permission helpers
    // ---------------------------------------------------------------------------------------

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), permission) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Explains what the refusal means, and offers a way back if it was permanent.
     *
     * A refusal is never treated as an error: the checklist itself still works, only delegation is
     * unavailable, so this is a brief explanation rather than anything blocking.
     *
     * After a request has been refused, `shouldShowRequestPermissionRationale` returning false
     * means the user chose "don't ask again" — the system will no longer show a prompt, so asking
     * again would do nothing and the only route left is the app's settings screen.
     */
    private fun onPermissionDenied(permission: String, @StringRes messageRes: Int) {
        val deniedPermanently = !shouldShowRequestPermissionRationale(permission)

        val snackbar = Snackbar.make(
            binding.root,
            getString(messageRes),
            Snackbar.LENGTH_LONG
        )

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
        // Detaches the dialogs from the window being destroyed. The delegation stage lives in the
        // ViewModel, so that dialog reappears from state once the view is rebuilt; the channel
        // chooser and the reset confirmation are transient and simply asked again next time.
        previewDialog?.dismiss()
        previewDialog = null
        channelDialog?.dismiss()
        channelDialog = null
        resetDialog?.dismiss()
        resetDialog = null
        _binding = null
    }

    companion object {
        /** Navigation argument name; must match the one declared in nav_graph.xml. */
        const val ARG_PET_ID = "petId"
    }
}
