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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentChecklistBinding
import com.prash.petcare.util.ContactLookup
import com.prash.petcare.util.ContactReader
import com.prash.petcare.util.SmsSender
import kotlinx.coroutines.launch

/**
 * Today's care checklist for one pet, grouped by category, with each task tickable — and the
 * ability to text the whole list to someone looking after the animal.
 *
 * The list is worked out rather than stored: tasks are repeating templates, so [ChecklistViewModel]
 * decides which of them fall due today before grouping them. Ticking a box writes straight back to
 * the database and the list re-emits from Room as a result.
 *
 * **The delegation flow**, which is most of this class, runs in strict order and can stop at any
 * step without leaving anything half-done:
 *  1. the user taps Delegate;
 *  2. a rationale dialog explains why contacts access is needed, then the permission is requested;
 *  3. the system contact picker opens and the chosen contact's number is looked up;
 *  4. the exact message is shown for confirmation, with how many texts it will cost;
 *  5. a second rationale dialog explains the SMS permission, which is then requested;
 *  6. only then is anything sent.
 *
 * Nothing is read or sent without the user having asked for it and confirmed it.
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
        setUpClickListeners()
        observeViewModel()
    }

    private fun setUpRecyclerView() {
        binding.checklistRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = checklistAdapter
            // Deliberately not setHasFixedSize(true): headers and task rows are different heights,
            // so the list's own size changes as categories appear and disappear.
        }
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
        binding.checklistTitleText.text = if (state.petName.isNotEmpty()) {
            getString(R.string.checklist_title_format, state.petName)
        } else {
            // Blank rather than a half-finished title while the name is still being read.
            ""
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

    /** Step 1: the user tapped Delegate. */
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

        // Checked before any permission is requested: prompting for SMS access on a device that
        // cannot send texts would ask the user for something that could never work.
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
     * Step 2: explain, then ask.
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

    /** Step 3: open the system contact picker. */
    private fun launchContactPicker() {
        pickContactLauncher.launch(null)
    }

    /** Step 3b: read the chosen contact's name and number. */
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

    /** Step 4: show the exact message for confirmation before anything is sent. */
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

    /** Step 5: the user confirmed; make sure there is permission to send. */
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

    /** Step 6: send. */
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
     * Used for both the preview and the send, from the one place, so the text confirmed on screen
     * is guaranteed to be the text that goes out.
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
        // Detaches the dialog from the window being destroyed. The flow's stage lives in the
        // ViewModel, so the dialog reappears from that state once the view is rebuilt.
        previewDialog?.dismiss()
        previewDialog = null
        _binding = null
    }

    companion object {
        /** Navigation argument name; must match the one declared in nav_graph.xml. */
        const val ARG_PET_ID = "petId"
    }
}
