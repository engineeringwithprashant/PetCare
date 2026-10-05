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
import com.prash.petcare.data.repository.TwoFactorStatus
import com.prash.petcare.data.sync.SyncStatus
import com.prash.petcare.databinding.FragmentProfileBinding
import com.prash.petcare.databinding.ItemProfileContactBinding
import com.prash.petcare.ui.common.PhoneNumberInput
import com.prash.petcare.ui.common.bindHeaderFromSession
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.common.showAssistantPill
import com.prash.petcare.ui.session.SessionViewModel
import com.prash.petcare.util.PetPhotoStorage
import java.text.NumberFormat
import kotlinx.coroutines.launch

/** The profile screen: who is signed in, how the app behaves for them, and the way out. */
class ProfileFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    /** Which account is signed in — the one to display, and the one logging out clears. */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: ProfileViewModel by viewModels {
        ProfileViewModel.factory(sessionViewModel.userId.value)
    }

    /** The CSV waiting to be written, held between asking the system for a location and being handed one back. */
    private var pendingExportCsv: String? = null

    /** Picks a new profile photo. */
    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) savePhoto(uri) }

    /** Asks the system where to save the exported CSV. */
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

    /** The shared header, with its monogram unhooked. */
    private fun setUpHeader() {
        binding.header.setUp(
            title = R.string.menu_profile,
            // The badge still opens the notifications screen, as on every other screen that draws this header.
            onBadgeClick = { findNavController().navigate(R.id.notificationsFragment) },
            onMonogramClick = null
        )

        // Keeps the header's mark showing the user's profile photo, falling back to the app's monogram.
        bindHeaderFromSession(binding.header, sessionViewModel)

        binding.header.showAssistantPill {
            findNavController().navigate(R.id.action_profile_to_assistant)
        }
    }

    private fun setUpClickListeners() {
        // The row and its pill do the same thing, so they share one listener rather than two that could drift.
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

    /** The two security switches. */
    private fun setUpSwitches() {
        // The two-factor switch is the odd one out: it saves nothing by itself.
        binding.twoFactorSwitch.setOnCheckedChangeListener { button, isChecked ->
            if (!button.isPressed) return@setOnCheckedChangeListener

            if (isChecked) {
                findNavController().navigate(R.id.action_profile_to_two_factor_setup)
            } else {
                confirmDisableTwoFactor()
            }
        }

        binding.careRemindersSwitch.setOnCheckedChangeListener { button, isChecked ->
            if (button.isPressed) viewModel.onCareRemindersChanged(isChecked)
        }
    }

    /** Asks for a code before turning two-factor authentication off. */
    private fun confirmDisableTwoFactor() {
        val dialogBinding = com.prash.petcare.databinding.DialogTwoFactorDisableBinding
            .inflate(LayoutInflater.from(requireContext()))

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.two_factor_disable_title)
            .setMessage(R.string.two_factor_disable_message)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.delegate_cancel, null)
            .setPositiveButton(R.string.two_factor_disable_confirm) { _, _ ->
                val code = dialogBinding.codeInput.text?.toString().orEmpty().trim()

                if (code.isEmpty()) {
                    showMessage(getString(R.string.two_factor_disable_code_required))
                } else {
                    viewModel.onTwoFactorDisableConfirmed(code)
                }
            }
            .setOnDismissListener {
                // Whatever happens next, the switch must go back to describing the account rather than the tap.
                renderTwoFactor(viewModel.twoFactor.value)
            }
            .show()
    }

    /** The four two-option toggles. */
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

    /** Subscribes to the ViewModel's state. */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.preferences.collect(::renderPreferences) }
                launch { viewModel.security.collect(::renderSecurity) }
                launch { viewModel.twoFactor.collect(::renderTwoFactor) }
                launch { viewModel.syncStatus.collect(::renderBackupStatus) }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }
    }

    // Rendering.

    private fun render(state: ProfileUiState) {
        renderStats(state)
        renderContacts(state.contacts)

        // Null only for the instant before the first database read returns.
        val user = state.user ?: return

        renderIdentityCard(user, state.petCount)
        renderAccountRows(user)

    }

    /** The dark card: the photo, the name, the two contact lines and the pills. */
    private fun renderIdentityCard(user: User, petCount: Int) {
        binding.profileNameText.text = user.fullName
        binding.profileEmailText.text = user.email

        // The prompt rather than a blank line when no number has been recorded.
        binding.profilePhoneText.text = user.phone ?: getString(R.string.profile_no_phone)

        binding.profileRolePill.setText(user.role.labelRes())
        binding.profilePetCountPill.text =
            resources.getQuantityString(R.plurals.profile_pet_count_pill, petCount, petCount)

        loadPhoto(user.photoPath)
    }

    /** Draws the profile photo, or leaves the empty well showing. */
    private fun loadPhoto(path: String?) {
        if (path == null) {
            binding.profilePhoto.setImageDrawable(null)
            binding.profilePhoto.contentDescription = getString(R.string.cd_profile_photo_empty)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = PetPhotoStorage.load(path)

            // The file can be missing — deleted from under the app, or the row updated while the read was in flight.
            binding.profilePhoto.setImageBitmap(bitmap)
            binding.profilePhoto.contentDescription = getString(
                if (bitmap == null) R.string.cd_profile_photo_empty else R.string.cd_profile_photo
            )
        }
    }

    /** The three stat cards. */
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

        // The number and what it is for, or an invitation to add one.
        val phone = user.phone
        binding.accountPhoneSubtitle.text = if (phone == null) {
            getString(R.string.profile_row_phone_subtitle_empty)
        } else {
            getString(R.string.profile_row_phone_subtitle, phone)
        }
        binding.phoneVerifiedPill.isVisible = phone != null

        // "Last changed 3 months ago", or the plain truth that it never has been.
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

    /** Marks one half of a toggle as chosen and the other as not. */
    private fun select(first: TextView, second: TextView, isFirstChosen: Boolean) {
        first.isSelected = isFirstChosen
        second.isSelected = !isFirstChosen
    }

    /** The two-factor row: its switch and the subtitle that says what is actually protecting the account. */
    private fun renderTwoFactor(status: TwoFactorStatus) {
        binding.twoFactorSwitch.isChecked = status.isEnabled

        binding.twoFactorSubtitle.text = if (status.isEnabled) {
            getString(
                R.string.profile_row_two_factor_on,
                resources.getQuantityString(
                    R.plurals.profile_row_two_factor_codes,
                    status.recoveryCodesRemaining,
                    status.recoveryCodesRemaining
                )
            )
        } else {
            getString(R.string.profile_row_two_factor_off)
        }
    }

    /** The security-card switch whose value comes from the preferences file, and its subtitle. */
    private fun renderSecurity(security: ProfileSecurity) {
        binding.careRemindersSwitch.isChecked = security.careRemindersEnabled
        binding.careRemindersSubtitle.setText(
            if (security.careRemindersEnabled) {
                R.string.profile_row_reminders_on
            } else {
                R.string.profile_row_reminders_off
            }
        )
    }

    /** The "Backup & sync" subtitle. */
    private fun renderBackupStatus(status: SyncStatus) {
        binding.backupSubtitle.text = when (status) {
            is SyncStatus.Idle -> getString(R.string.profile_row_backup_never)

            is SyncStatus.Syncing -> getString(R.string.profile_row_backup_syncing)

            // getQuantityString picks the right plural form for the device's language.
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

    /** The delegation contacts card. */
    private fun renderContacts(contacts: List<DelegationContact>) {
        binding.contactsContainer.removeAllViews()

        // The explanation replaces the rows when there are none: an empty card cannot say.
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
            // A hairline before every row but the first.
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

        // Their first initial. uppercaseChar rather than uppercase() on the whole name.
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

    // Delegation.

    /** Hands today's checklist to a saved contact. */
    private fun delegateTo(contact: DelegationContact) {
        findNavController().navigate(
            R.id.action_profile_to_checklist,
            Bundle().apply {
                putString(ARG_DELEGATE_NAME, contact.name)
                putString(ARG_DELEGATE_PHONE, contact.phoneNumber)
            }
        )
    }

    /** Asks before forgetting a contact. */
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

    /** The add-contact dialog: a name, a number and an optional note. */
    private fun showAddContactDialog() {
        val dialogBinding = com.prash.petcare.databinding.DialogAddContactBinding
            .inflate(LayoutInflater.from(requireContext()))

        // The number's two boxes behave as one control — the country chooser.
        val phoneInput = PhoneNumberInput(
            countryLayout = dialogBinding.contactPhoneCountryLayout,
            countryInput = dialogBinding.contactPhoneCountryInput,
            numberLayout = dialogBinding.contactPhoneInputLayout,
            numberInput = dialogBinding.contactPhoneInput
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_add_contact_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.delegate_cancel, null)
            .setPositiveButton(R.string.profile_add_contact_save) { _, _ ->
                // Whether the fields are acceptable is the ViewModel's decision, not this dialog's.
                viewModel.onContactAdded(
                    name = dialogBinding.contactNameInput.text?.toString().orEmpty(),
                    phoneRegion = phoneInput.regionCode(),
                    phone = phoneInput.nationalNumber(),
                    note = dialogBinding.contactNoteInput.text?.toString().orEmpty()
                )
            }
            .show()
    }

    // Photo.

    /** Copies the chosen image into the app's own storage and records its path. */
    private fun savePhoto(uri: Uri) {
        // The application context, not the fragment's: the work outlives.
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

            // The previous file is deleted by the repository.
            viewModel.onPhotoChosen(savedPath)
        }
    }

    // Export, delete and log out.

    private fun handleEvent(event: ProfileEvent) {
        when (event) {
            is ProfileEvent.ExportReady -> {
                pendingExportCsv = event.csv
                createExportFileLauncher.launch(
                    getString(R.string.profile_export_file_name, event.suggestedFileName)
                )
            }

            is ProfileEvent.ExportEmpty -> showMessage(getString(R.string.profile_export_empty))

            // The add-contact dialog has already closed by the time either of these arrives.
            is ProfileEvent.ContactIncomplete ->
                showMessage(getString(R.string.profile_add_contact_incomplete))

            is ProfileEvent.ContactPhoneInvalid -> showMessage(getString(R.string.error_phone_invalid))

            is ProfileEvent.AccountDeleted -> leaveForSignIn()

            // Nothing was deleted. Say why and stay on the profile screen.
            is ProfileEvent.AccountDeletionFailed -> showMessage(getString(event.messageRes))

            // The switch is already right: the row observes the database rows the turn-off just changed.
            is ProfileEvent.TwoFactorDisabled ->
                showMessage(getString(R.string.two_factor_disable_done))

            // The code was refused, so it is still on. The dialog's dismiss listener has already put the switch back.
            is ProfileEvent.TwoFactorDisableFailed -> showMessage(getString(event.messageRes))
        }
    }

    /** Writes the pending CSV to the location the user picked. */
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

    /** Deleting the account: two dialogs, not one. */
    private fun confirmDeleteAccount() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_delete_title)
            .setMessage(R.string.profile_delete_message)
            .setNegativeButton(R.string.delegate_cancel, null)
            .setPositiveButton(R.string.profile_delete_continue) { _, _ -> askForDeleteWord() }
            .show()
    }

    /** The second gate: the user types DELETE and their password, or nothing happens. */
    private fun askForDeleteWord() {
        val dialogBinding = com.prash.petcare.databinding.DialogConfirmDeleteBinding
            .inflate(LayoutInflater.from(requireContext()))

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_delete_confirm_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.delegate_cancel, null)
            .setPositiveButton(R.string.profile_delete_confirm_button) { _, _ ->
                val typed = dialogBinding.confirmInput.text?.toString().orEmpty().trim()

                // Compared case-insensitively against a non-translatable constant.
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

    /** Asks before logging out. */
    private fun confirmLogOut() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_sign_out_dialog_title)
            .setMessage(R.string.profile_sign_out_dialog_message)
            .setNegativeButton(R.string.profile_sign_out_dialog_cancel, null)
            .setPositiveButton(R.string.profile_sign_out_dialog_confirm) { _, _ -> leaveForSignIn() }
            .show()
    }

    /** Clears the session and returns to the sign-in screen. */
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

        /** The delegation prefill arguments, as declared on checklistFragment in nav_graph.xml. */
        const val ARG_DELEGATE_NAME = "delegateToName"
        const val ARG_DELEGATE_PHONE = "delegateToPhone"

        /** What the system's save dialog is told the exported file is. */
        private const val CSV_MIME_TYPE = "text/csv"
    }
}

/** The words for a role — "Owner", "Carer". */
private fun UserRole.labelRes(): Int = when (this) {
    UserRole.OWNER -> R.string.profile_role_owner
    UserRole.CARER -> R.string.profile_role_carer
}
