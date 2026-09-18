package com.prash.petcare.ui.profile

import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.BuildConfig
import com.prash.petcare.R
import com.prash.petcare.data.local.CurrencyPreference
import com.prash.petcare.data.local.DelegationContact
import com.prash.petcare.data.local.ReminderChannel
import com.prash.petcare.data.local.WeekStart
import com.prash.petcare.data.local.WeightUnit
import com.prash.petcare.data.local.entity.User
import com.prash.petcare.data.local.entity.UserRole
import com.prash.petcare.data.sync.SyncStatus
import com.prash.petcare.databinding.FragmentProfileBinding
import com.prash.petcare.databinding.ItemProfileContactBinding
import com.prash.petcare.ui.common.bindHeaderProfilePhoto
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.common.showAssistantPill
import com.prash.petcare.ui.common.showPetCount
import com.prash.petcare.ui.session.SessionViewModel
import com.prash.petcare.util.PetPhotoStorage
import java.text.NumberFormat
import kotlinx.coroutines.launch

/**
 * The profile screen: who is signed in, how the app behaves for them, and the way out.
 *
 * Reached from the **monogram** in the shared header, from every screen that draws it — see
 * [com.prash.petcare.ui.common.openProfile]. The toolbar's overflow menu still leads here too, for the
 * screens that show a toolbar instead.
 *
 * In MVVM this is the **View**: it renders what [ProfileViewModel] publishes and forwards what the
 * user does. It decides three things of its own, and each is genuinely a screen's job rather than a
 * ViewModel's:
 *
 *  - **the wording**, because every one of these lines is a string resource and a ViewModel holding a
 *    Context to resolve them would stop being testable;
 *  - **the dialogs**, because confirming a destructive action is an interaction, not state;
 *  - **writing the exported file**, because it needs a `ContentResolver` and a URI the user chose
 *    through the system's save dialog — the ViewModel builds the text and stops there.
 *
 * **Why the screen is a single long layout rather than a list.** Its content is fixed: these cards,
 * these rows, in this order. The one variable-length part is the contacts card, which this class fills
 * by inflating a row per contact — see [renderContacts].
 */
class ProfileFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    /**
     * Which account is signed in — the one to display, and the one logging out clears.
     *
     * `by activityViewModels` so this is the Activity's instance, shared with every other screen.
     */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: ProfileViewModel by viewModels {
        ProfileViewModel.factory(sessionViewModel.userId.value)
    }

    /**
     * The CSV waiting to be written, held between asking the system for a location and being handed
     * one back.
     *
     * The save dialog is another app's Activity, and this one can be destroyed and recreated while it
     * is in front — so the text has to survive that gap. It is a field rather than a captured lambda
     * because [registerForActivityResult] must be called during construction, long before there is
     * anything to write.
     */
    private var pendingExportCsv: String? = null

    /**
     * Picks a new profile photo.
     *
     * The photo picker rather than a storage permission: it hands back exactly the one image the user
     * chose, needs no permission at all, and on Android 13 and above is the system's own component.
     * The same contract the pet form uses, so choosing a picture works identically in both places.
     */
    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) savePhoto(uri) }

    /**
     * Asks the system where to save the exported CSV.
     *
     * `CreateDocument` — the Storage Access Framework — rather than writing to a folder the app picked
     * and sharing it through a FileProvider. The user chooses the destination, the app receives a URI
     * it may write once, and **no storage permission is involved at all**, which matters for an export
     * that is by definition the user's own data leaving the app.
     */
    private val createExportFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(CSV_MIME_TYPE)
    ) { uri -> writeExport(uri) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setUpHeader()
        setUpClickListeners()
        showVersion()
        observeViewModel()
    }

    /**
     * The shared header, with its monogram unhooked.
     *
     * Passing null is what removes the tap, the ripple, the focus stop and the label — a monogram that
     * reopened the screen already being looked at would be a dead end. The notifications screen does
     * exactly this with its badge, and for the same reason; see TabHeader.kt.
     */
    private fun setUpHeader() {
        binding.header.setUp(
            title = R.string.menu_profile,
            // The badge still opens the notifications screen, as on every other screen that draws
            // this header — only the monogram is unhooked here.
            onBadgeClick = { findNavController().navigate(R.id.notificationsFragment) },
            onMonogramClick = null
        )

        // Keeps the header's mark showing the user's profile photo, falling back to the app's
        // monogram when there is none. See TabHeader.bindHeaderProfilePhoto.
        bindHeaderProfilePhoto(binding.header, sessionViewModel)

        binding.header.showAssistantPill {
            findNavController().navigate(R.id.action_profile_to_assistant)
        }
    }

    private fun setUpClickListeners() {
        // The row and its pill do the same thing, so they share one listener rather than two that
        // could drift. The pill is there as a visible affordance; the row is there because a 64dp
        // target is easier to hit than a 34dp one.
        val openDetails = View.OnClickListener {
            findNavController().navigate(R.id.action_profile_to_account_details)
        }
        binding.accountNameRow.setOnClickListener(openDetails)
        binding.editDetailsButton.setOnClickListener(openDetails)

        val openPassword = View.OnClickListener {
            findNavController().navigate(R.id.action_profile_to_change_password)
        }
        binding.accountPasswordRow.setOnClickListener(openPassword)
        binding.changePasswordButton.setOnClickListener(openPassword)

        binding.changePhotoButton.setOnClickListener {
            pickPhotoLauncher.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }

        setUpSwitches()
        setUpPreferenceToggles()

        binding.addContactButton.setOnClickListener { showAddContactDialog() }

        binding.exportRow.setOnClickListener { viewModel.onExportRequested() }
        binding.backupRow.setOnClickListener { viewModel.onBackupRequested() }
        binding.deleteAccountRow.setOnClickListener { confirmDeleteAccount() }

        binding.logOutButton.setOnClickListener { confirmLogOut() }
    }

    /**
     * The two security switches that store something.
     *
     * `setOnCheckedChangeListener` fires for a programmatic change as well as a tap, which would make
     * [renderSecurity] writing the stored value back into the switch look like the user having
     * toggled it — a loop. `isPressed` tells the two apart: it is true only while a real touch is in
     * progress. The two-factor switch gets no listener at all, because it is disabled and stores
     * nothing.
     */
    private fun setUpSwitches() {
        binding.fingerprintSwitch.setOnCheckedChangeListener { button, isChecked ->
            if (button.isPressed) viewModel.onFingerprintUnlockChanged(isChecked)
        }

        binding.careRemindersSwitch.setOnCheckedChangeListener { button, isChecked ->
            if (button.isPressed) viewModel.onCareRemindersChanged(isChecked)
        }
    }

    /**
     * The four two-option toggles.
     *
     * Each half simply reports the choice it stands for. Nothing is toggled here and no state is read:
     * the tap goes to the store, the store publishes, and [renderPreferences] draws the result — so
     * what is on screen is always what is saved, and a write that failed could not leave the control
     * showing a choice that was not made.
     */
    private fun setUpPreferenceToggles() {
        binding.weightMetricSegment.setOnClickListener {
            viewModel.onWeightUnitChosen(WeightUnit.METRIC)
        }
        binding.weightImperialSegment.setOnClickListener {
            viewModel.onWeightUnitChosen(WeightUnit.IMPERIAL)
        }

        binding.reminderPushSegment.setOnClickListener {
            viewModel.onReminderChannelChosen(ReminderChannel.PUSH)
        }
        binding.reminderSmsSegment.setOnClickListener {
            viewModel.onReminderChannelChosen(ReminderChannel.SMS)
        }

        binding.weekSundaySegment.setOnClickListener {
            viewModel.onWeekStartChosen(WeekStart.SUNDAY)
        }
        binding.weekMondaySegment.setOnClickListener {
            viewModel.onWeekStartChosen(WeekStart.MONDAY)
        }

        binding.currencyNprSegment.setOnClickListener {
            viewModel.onCurrencyChosen(CurrencyPreference.NPR)
        }
        binding.currencyUsdSegment.setOnClickListener {
            viewModel.onCurrencyChosen(CurrencyPreference.USD)
        }
    }

    /**
     * Subscribes to the ViewModel's state.
     *
     * `repeatOnLifecycle(STARTED)` begins collecting when the screen becomes visible and cancels when
     * it stops, so the fragment never does work in the background or touches views after they have
     * been destroyed. Each flow gets its own coroutine, because `collect` never returns and a second
     * call after the first would never run.
     */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.preferences.collect(::renderPreferences) }
                launch { viewModel.security.collect(::renderSecurity) }
                launch { viewModel.syncStatus.collect(::renderBackupStatus) }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    private fun render(state: ProfileUiState) {
        renderStats(state)
        renderContacts(state.contacts)

        // Null only for the instant before the first database read returns. The account's fields are
        // left as they are rather than filled with a placeholder, which would flash and be replaced.
        val user = state.user ?: return

        renderIdentityCard(user, state.petCount)
        renderAccountRows(user)

        binding.header.showPetCount(state.petCount)
    }

    /** The dark card: the photo, the name, the two contact lines and the pills. */
    private fun renderIdentityCard(user: User, petCount: Int) {
        binding.profileNameText.text = user.fullName
        binding.profileEmailText.text = user.email

        // The prompt rather than a blank line when no number has been recorded, so an empty row
        // explains itself instead of looking like something that failed to load.
        binding.profilePhoneText.text = user.phone ?: getString(R.string.profile_no_phone)

        binding.profileRolePill.setText(user.role.labelRes())
        binding.profilePetCountPill.text =
            resources.getQuantityString(R.plurals.profile_pet_count_pill, petCount, petCount)

        loadPhoto(user.photoPath)
    }

    /**
     * Draws the profile photo, or leaves the empty well showing.
     *
     * The image is decoded off the main thread and the view is only touched afterwards, inside the
     * view's own lifecycle scope — so a decode still running when the user leaves is cancelled rather
     * than completing and writing into a destroyed view.
     *
     * The content description changes with the outcome, because "Your profile photo" spoken over an
     * empty circle would be describing something that is not there.
     */
    private fun loadPhoto(path: String?) {
        if (path == null) {
            binding.profilePhoto.setImageDrawable(null)
            binding.profilePhoto.contentDescription = getString(R.string.cd_profile_photo_empty)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = PetPhotoStorage.load(path)

            // The file can be missing — deleted from under the app, or the row updated while the read
            // was in flight — so the empty well is the fallback rather than a broken-image mark.
            binding.profilePhoto.setImageBitmap(bitmap)
            binding.profilePhoto.contentDescription = getString(
                if (bitmap == null) R.string.cd_profile_photo_empty else R.string.cd_profile_photo
            )
        }
    }

    /**
     * The three stat cards.
     *
     * [NumberFormat] rather than `toString()`, as everywhere else a count is drawn in this app (see
     * TabHeader.showPetCount): `toString()` always produces Western Arabic digits, where a device set
     * to Bengali, Persian or Nepali expects the number in its own numerals.
     */
    private fun renderStats(state: ProfileUiState) {
        val format = NumberFormat.getInstance()

        binding.statPetsValue.text = format.format(state.petCount)
        binding.statStreakValue.text = format.format(state.streakFullDays)
        binding.statTasksValue.text = format.format(state.tasksThisWeek)
    }

    /** The ACCOUNT card's four subtitles. */
    private fun renderAccountRows(user: User) {
        binding.accountNameSubtitle.text = getString(
            R.string.profile_row_name_subtitle,
            user.fullName,
            getString(user.role.labelRes()).lowercase()
        )

        binding.accountEmailSubtitle.text = user.email

        // The number and what it is for, or an invitation to add one. The "VERIFIED" pill goes with
        // it: beside "Add one so carers know who is texting" it would be confirming something that
        // does not exist.
        val phone = user.phone
        binding.accountPhoneSubtitle.text = if (phone == null) {
            getString(R.string.profile_row_phone_subtitle_empty)
        } else {
            getString(R.string.profile_row_phone_subtitle, phone)
        }
        binding.phoneVerifiedPill.isVisible = phone != null

        // "Last changed 3 months ago", or the plain truth that it never has been. Those are different
        // facts, and reporting a change that never happened would be a small lie the user could not
        // check.
        binding.accountPasswordSubtitle.text = user.passwordChangedAt?.let { changedAt ->
            getString(
                R.string.profile_row_password_subtitle,
                DateUtils.getRelativeTimeSpanString(
                    changedAt,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                )
            )
        } ?: getString(R.string.profile_row_password_subtitle_never)
    }

    /** The four two-option toggles, and the currency and weight choices they stand for. */
    private fun renderPreferences(preferences: ProfilePreferences) {
        select(
            binding.weightMetricSegment,
            binding.weightImperialSegment,
            preferences.weightUnit == WeightUnit.METRIC
        )
        select(
            binding.reminderPushSegment,
            binding.reminderSmsSegment,
            preferences.reminderChannel == ReminderChannel.PUSH
        )
        select(
            binding.weekSundaySegment,
            binding.weekMondaySegment,
            preferences.weekStart == WeekStart.SUNDAY
        )
        select(
            binding.currencyNprSegment,
            binding.currencyUsdSegment,
            preferences.currency == CurrencyPreference.NPR
        )
    }

    /**
     * Marks one half of a toggle as chosen and the other as not.
     *
     * `isSelected` is the only thing set: the fill comes from bg_profile_segment.xml and the text
     * colour from res/color/profile_segment_text.xml, both state lists keyed on exactly that. Nothing
     * about how the control looks is decided in Kotlin.
     *
     * Both halves are set on every call, in both directions, so a choice can never leave two halves
     * filled or none.
     */
    private fun select(first: TextView, second: TextView, isFirstChosen: Boolean) {
        first.isSelected = isFirstChosen
        second.isSelected = !isFirstChosen
    }

    /** The two switches that store something, and their subtitles. */
    private fun renderSecurity(security: ProfileSecurity) {
        binding.fingerprintSwitch.isChecked = security.fingerprintUnlock
        binding.fingerprintSubtitle.setText(
            if (security.fingerprintUnlock) {
                R.string.profile_row_fingerprint_on
            } else {
                R.string.profile_row_fingerprint_off
            }
        )

        binding.careRemindersSwitch.isChecked = security.careRemindersEnabled
        binding.careRemindersSubtitle.setText(
            if (security.careRemindersEnabled) {
                R.string.profile_row_reminders_on
            } else {
                R.string.profile_row_reminders_off
            }
        )
    }

    /**
     * The "Backup & sync" subtitle.
     *
     * The same wording the dashboard and the old profile screen used, from the same source,
     * deliberately: this is the screen a user opens after glimpsing that line, and a second
     * differently-worded answer would invite the question of which one is right.
     */
    private fun renderBackupStatus(status: SyncStatus) {
        binding.backupSubtitle.text = when (status) {
            is SyncStatus.Idle -> getString(R.string.profile_row_backup_never)

            is SyncStatus.Syncing -> getString(R.string.profile_row_backup_syncing)

            // getQuantityString picks the right plural form for the device's language: the first
            // argument selects the form, the second fills the placeholder.
            is SyncStatus.Pending -> resources.getQuantityString(
                R.plurals.profile_row_backup_pending,
                status.changeCount,
                status.changeCount
            )

            is SyncStatus.Synced -> getString(
                R.string.profile_row_backup_subtitle,
                DateUtils.getRelativeTimeSpanString(
                    status.atMillis,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                )
            )

            is SyncStatus.Offline -> getString(R.string.profile_row_backup_offline)
        }
    }

    /**
     * The delegation contacts card.
     *
     * The container is emptied and refilled rather than diffed. That would be the wrong trade in a
     * RecyclerView, but this list is two or three rows that change only when the user adds or removes
     * one — so the simplest correct thing is also the right one, and it means there is no adapter
     * whose state could disagree with the store's.
     */
    private fun renderContacts(contacts: List<DelegationContact>) {
        binding.contactsContainer.removeAllViews()

        // The explanation replaces the rows when there are none: an empty card cannot say what it is
        // for, and "None yet" alone does not either.
        binding.contactsEmptyText.isVisible = contacts.isEmpty()

        binding.contactsStatusPill.text = if (contacts.isEmpty()) {
            getString(R.string.profile_contacts_none)
        } else {
            resources.getQuantityString(
                R.plurals.profile_contacts_saved,
                contacts.size,
                contacts.size
            )
        }

        val inflater = LayoutInflater.from(requireContext())

        contacts.forEachIndexed { index, contact ->
            // A hairline before every row but the first, so the rows read as one grouped list exactly
            // as the fixed cards above do.
            if (index > 0) {
                inflater.inflate(R.layout.view_profile_row_divider, binding.contactsContainer, true)
            }

            val row = ItemProfileContactBinding.inflate(inflater, binding.contactsContainer, false)
            bindContact(row, contact)
            binding.contactsContainer.addView(row.root)
        }
    }

    /** Fills one contact row and attaches what its two controls do. */
    private fun bindContact(row: ItemProfileContactBinding, contact: DelegationContact) {
        row.contactName.text = contact.name

        // Their first initial. uppercaseChar rather than uppercase() on the whole name, so only one
        // character is allocated; the blank case cannot normally happen — the store rejects a contact
        // with no name — but a stored file can be hand-edited, and a crash here would be a poor way to
        // find that out.
        row.contactInitial.text = contact.name.firstOrNull()?.uppercaseChar()?.toString().orEmpty()

        // What they help with, or a prompt saying what tapping the row does.
        row.contactNote.text = contact.note ?: getString(R.string.profile_row_contact_subtitle_empty)

        row.contactRow.setOnClickListener { delegateTo(contact) }

        row.contactRemoveButton.contentDescription =
            getString(R.string.cd_remove_contact, contact.name)
        row.contactRemoveButton.setOnClickListener { confirmRemoveContact(contact) }
    }

    /** "PetCare 1.0 · records stored on this device", from the build rather than from a literal. */
    private fun showVersion() {
        binding.versionText.text =
            getString(R.string.profile_version_format, BuildConfig.VERSION_NAME)
    }

    // ---------------------------------------------------------------------------------------------
    // Delegation
    // ---------------------------------------------------------------------------------------------

    /**
     * Hands today's checklist to a saved contact.
     *
     * It opens the **Tasks tab** with the recipient already chosen, rather than reimplementing
     * delegation here. Everything that makes delegating safe already lives there — the message is
     * built from the tasks actually outstanding, the SMS permission is requested with a rationale, and
     * the exact text is shown on a preview the user must confirm before a single message is sent. A
     * second path that skipped any of that would be the dangerous one.
     *
     * What the two arguments save is only the part the user has already answered: which channel, and
     * who. See the `delegateToName` and `delegateToPhone` arguments on checklistFragment in
     * nav_graph.xml.
     */
    private fun delegateTo(contact: DelegationContact) {
        findNavController().navigate(
            R.id.action_profile_to_checklist,
            Bundle().apply {
                putString(ARG_DELEGATE_NAME, contact.name)
                putString(ARG_DELEGATE_PHONE, contact.phoneNumber)
            }
        )
    }

    /**
     * Asks before forgetting a contact.
     *
     * A dialog because the number is not recoverable from inside the app once it is gone — it would
     * have to be found in the address book again. The message says what is *not* affected, since that
     * is the actual worry: removing someone from a list headed by the user's pets could plausibly look
     * like it changes something about the pets.
     *
     * Cancel is listed first and does nothing, so the safe choice is the easy one.
     */
    private fun confirmRemoveContact(contact: DelegationContact) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.profile_remove_contact_title, contact.name))
            .setMessage(R.string.profile_remove_contact_message)
            .setNegativeButton(R.string.delegate_cancel, null)
            .setPositiveButton(R.string.profile_remove_contact_confirm) { _, _ ->
                viewModel.onContactRemoved(contact.id)
            }
            .show()
    }

    /**
     * The add-contact dialog: a name, a number and an optional note.
     *
     * A dialog rather than a screen of its own, because it is three short fields and a save — a whole
     * destination with a toolbar and a back stack entry would be more ceremony than the task deserves.
     *
     * The layout is inflated here rather than being a permanent part of the screen, so the fields only
     * exist while the dialog is up.
     */
    private fun showAddContactDialog() {
        val dialogBinding = com.prash.petcare.databinding.DialogAddContactBinding
            .inflate(LayoutInflater.from(requireContext()))

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_add_contact_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.delegate_cancel, null)
            .setPositiveButton(R.string.profile_add_contact_save) { _, _ ->
                val name = dialogBinding.contactNameInput.text?.toString().orEmpty().trim()
                val phone = dialogBinding.contactPhoneInput.text?.toString().orEmpty().trim()
                val note = dialogBinding.contactNoteInput.text?.toString().orEmpty().trim()

                // Both halves are needed: a contact with no number is one nothing can be delegated to,
                // which is the single thing this list exists for.
                if (name.isEmpty() || phone.isEmpty()) {
                    showMessage(getString(R.string.profile_add_contact_incomplete))
                } else {
                    viewModel.onContactAdded(name, phone, note.takeIf { it.isNotEmpty() })
                }
            }
            .show()
    }

    // ---------------------------------------------------------------------------------------------
    // Photo
    // ---------------------------------------------------------------------------------------------

    /**
     * Copies the chosen image into the app's own storage and records its path.
     *
     * The picked `content://` URI grants read access only temporarily and stops working once the app
     * restarts, so a copy the app owns is what makes the photo still be there tomorrow — see
     * [PetPhotoStorage], which does exactly this for pets and is reused unchanged here.
     *
     * Unlike the pet form there is no cropping step. A pet's photo is drawn at 4:3 across a whole card,
     * where what was framed matters; this one is masked to a small circle and centre-cropped, so
     * offering a rectangular cropper before a circular mask would be asking the user to frame something
     * they will not get.
     */
    private fun savePhoto(uri: Uri) {
        // The application context, not the fragment's: the work outlives neither, but holding a
        // fragment's context in a coroutine that might finish after the view is gone is the habit
        // worth not forming.
        val appContext = requireContext().applicationContext

        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = PetPhotoStorage.decodeForCrop(appContext, uri)
            if (bitmap == null) {
                showMessage(getString(R.string.pet_photo_crop_unreadable))
                return@launch
            }

            val savedPath = PetPhotoStorage.saveBitmap(appContext, bitmap)
            if (savedPath == null) {
                showMessage(getString(R.string.pet_photo_crop_unreadable))
                return@launch
            }

            // The previous file is deleted by the repository, which is the only place that knows what
            // the old path was — see UserRepository.updatePhoto.
            viewModel.onPhotoChosen(savedPath)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Export, delete and log out
    // ---------------------------------------------------------------------------------------------

    private fun handleEvent(event: ProfileEvent) {
        when (event) {
            is ProfileEvent.ExportReady -> {
                pendingExportCsv = event.csv
                createExportFileLauncher.launch(
                    getString(R.string.profile_export_file_name, event.suggestedFileName)
                )
            }

            is ProfileEvent.ExportEmpty -> showMessage(getString(R.string.profile_export_empty))

            is ProfileEvent.AccountDeleted -> leaveForSignIn()

            // Nothing was deleted. Say why and stay on the profile screen.
            is ProfileEvent.AccountDeletionFailed -> showMessage(getString(event.messageRes))
        }
    }

    /**
     * Writes the pending CSV to the location the user picked.
     *
     * A null URI means they backed out of the save dialog, which is not a failure and deserves no
     * message — they know they cancelled. The pending text is cleared either way, so a later export
     * cannot write a stale document.
     *
     * `use` closes the stream even if writing throws, and the write is wrapped because a URI can stop
     * being writable between being granted and being used — a removed SD card, a provider that has
     * gone away. Reporting that is much better than crashing on the user's own data.
     */
    private fun writeExport(uri: Uri?) {
        val csv = pendingExportCsv
        pendingExportCsv = null

        if (uri == null || csv == null) return

        val succeeded = runCatching {
            requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                output.write(csv.toByteArray())
            } ?: error("The chosen location could not be opened for writing")
        }.isSuccess

        showMessage(
            getString(
                if (succeeded) R.string.profile_export_success else R.string.profile_export_failed
            )
        )
    }

    /**
     * Deleting the account: two dialogs, not one.
     *
     * The first explains what goes and offers a way out. The second asks the user to type the word,
     * which is the standard pattern for an action with no undo — it stops a mis-tap on the first
     * dialog's confirm button from destroying every record on the device, and the typing itself is a
     * moment in which the user re-reads what they are about to do.
     *
     * Cancel is listed first in both and does nothing.
     */
    private fun confirmDeleteAccount() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_delete_title)
            .setMessage(R.string.profile_delete_message)
            .setNegativeButton(R.string.delegate_cancel, null)
            .setPositiveButton(R.string.profile_delete_continue) { _, _ -> askForDeleteWord() }
            .show()
    }

    /**
     * The second gate: the user types DELETE and their password, or nothing happens.
     *
     * The word is checked here, since it is only a typing exercise. The password is passed on
     * untouched: Firebase checks it, through the ViewModel and the repository.
     */
    private fun askForDeleteWord() {
        val dialogBinding = com.prash.petcare.databinding.DialogConfirmDeleteBinding
            .inflate(LayoutInflater.from(requireContext()))

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_delete_confirm_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.delegate_cancel, null)
            .setPositiveButton(R.string.profile_delete_confirm_button) { _, _ ->
                val typed = dialogBinding.confirmInput.text?.toString().orEmpty().trim()

                // Compared case-insensitively against a non-translatable constant. The word is fixed
                // rather than localised because it is one half of a comparison, and translating one
                // half without the other would lock the user out of their own confirmation.
                // The password is not trimmed: a space can be part of a real password.
                val password = dialogBinding.passwordInput.text?.toString().orEmpty()

                when {
                    !typed.equals(getString(R.string.profile_delete_confirm_word), ignoreCase = true) ->
                        showMessage(getString(R.string.profile_delete_mismatch))

                    password.isEmpty() ->
                        showMessage(getString(R.string.profile_delete_wrong_password))

                    else -> viewModel.onDeleteAccountConfirmed(password)
                }
            }
            .show()
    }

    /**
     * Asks before logging out.
     *
     * A dialog because the action is not reversible from where the user is standing: the way back is
     * typing a password. The message says what is *not* lost, since that is the actual worry at this
     * point — an app that keeps everything locally could plausibly be discarding it.
     */
    private fun confirmLogOut() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_sign_out_dialog_title)
            .setMessage(R.string.profile_sign_out_dialog_message)
            .setNegativeButton(R.string.profile_sign_out_dialog_cancel, null)
            .setPositiveButton(R.string.profile_sign_out_dialog_confirm) { _, _ -> leaveForSignIn() }
            .show()
    }

    /**
     * Clears the session and returns to the sign-in screen — what both logging out and deleting the
     * account end with.
     *
     * `popUpTo` the graph itself with `inclusive` empties the entire back stack on the way, which is
     * what makes leaving final: without it the system back button from the sign-in screen would walk
     * straight back into the account just left — into a *deleted* account, in the delete case, where
     * every query would return nothing.
     */
    private fun leaveForSignIn() {
        sessionViewModel.signOut()

        findNavController().navigate(
            R.id.authFragment,
            null,
            navOptions { popUpTo(R.id.nav_graph) { inclusive = true } }
        )
    }

    /** A Snackbar on the screen's root, so it clears the bottom navigation bar. */
    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Release the reference to the destroyed view hierarchy — see the note on [_binding].
        _binding = null
    }

    companion object {

        /**
         * The delegation prefill arguments, as declared on checklistFragment in nav_graph.xml.
         *
         * Named here rather than built with generated Directions, because this screen navigates by
         * destination id through a plain Bundle — see [delegateTo].
         */
        const val ARG_DELEGATE_NAME = "delegateToName"
        const val ARG_DELEGATE_PHONE = "delegateToPhone"

        /** What the system's save dialog is told the exported file is. */
        private const val CSV_MIME_TYPE = "text/csv"
    }
}

/**
 * The words for a role — "Owner", "Carer".
 *
 * An extension in the View layer rather than a property on the enum, so
 * [com.prash.petcare.data.local.entity.UserRole] stays a plain data type with no Android resource ids
 * in it. The app's other enums are labelled the same way; see ui/common/SpeciesLabel.kt.
 */
private fun UserRole.labelRes(): Int = when (this) {
    UserRole.OWNER -> R.string.profile_role_owner
    UserRole.CARER -> R.string.profile_role_carer
}
