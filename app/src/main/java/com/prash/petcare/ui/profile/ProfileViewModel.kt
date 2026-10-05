package com.prash.petcare.ui.profile

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.R
import com.prash.petcare.data.local.CurrencyPreference
import com.prash.petcare.data.local.DelegationContact
import com.prash.petcare.data.local.DelegationContactStore
import com.prash.petcare.data.local.ReminderChannel
import com.prash.petcare.data.local.SettingsStore
import com.prash.petcare.data.local.WeekStart
import com.prash.petcare.data.local.WeightUnit
import com.prash.petcare.data.local.entity.User
import com.prash.petcare.data.remote.AuthFailure
import com.prash.petcare.data.repository.AccountDeletionResult
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.ExpenseRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.data.repository.TwoFactorCheck
import com.prash.petcare.data.repository.TwoFactorRepository
import com.prash.petcare.data.repository.TwoFactorStatus
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.data.sync.SyncManager
import com.prash.petcare.data.sync.SyncStatus
import com.prash.petcare.ui.home.HomeDashboard
import com.prash.petcare.ui.home.PetCareData
import com.prash.petcare.util.CareSchedule
import com.prash.petcare.util.PhoneCheck
import com.prash.petcare.util.PhoneNumbers
import com.prash.petcare.util.RecordsCsv
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/** The four preference choices, gathered so the screen renders them from one object. */
data class ProfilePreferences(
    val weightUnit: WeightUnit = WeightUnit.METRIC,
    val reminderChannel: ReminderChannel = ReminderChannel.PUSH,
    val weekStart: WeekStart = WeekStart.MONDAY,
    val currency: CurrencyPreference = CurrencyPreference.NPR
)

/** The security-card setting whose value lives in the preferences file. */
data class ProfileSecurity(
    val careRemindersEnabled: Boolean = true
)

/** Everything the profile screen displays. */
data class ProfileUiState(
    val user: User? = null,
    val petCount: Int = 0,
    val streakFullDays: Int = 0,
    val tasksThisWeek: Int = 0,
    val contacts: List<DelegationContact> = emptyList(),
    val isLoading: Boolean = true
)

/** What the screen should do once, in response to something the user did. */
sealed interface ProfileEvent {

    /** The CSV is ready; the fragment hands it to the system's save dialog. */
    data class ExportReady(val csv: String, val suggestedFileName: String) : ProfileEvent

    /** There was nothing to export. */
    data object ExportEmpty : ProfileEvent

    /** A delegation contact was saved without a name or without a number; nothing was added. */
    data object ContactIncomplete : ProfileEvent

    /** A delegation contact's number is not a real number for the country chosen; nothing was added. */
    data object ContactPhoneInvalid : ProfileEvent

    /** The account and everything under it is gone; the fragment returns to the sign-in screen. */
    data object AccountDeleted : ProfileEvent

    /** Deleting failed (wrong password, no network…), so nothing was deleted. */
    data class AccountDeletionFailed(@param:StringRes val messageRes: Int) : ProfileEvent

    /** Two-factor authentication has been turned off, and the secret and codes are gone. */
    data object TwoFactorDisabled : ProfileEvent

    /** The code typed to turn two-factor authentication off was refused, so it is still on. */
    data class TwoFactorDisableFailed(@param:StringRes val messageRes: Int) : ProfileEvent
}

/** Supplies the profile screen with the account, the stats, the settings and the saved contacts. */
class ProfileViewModel(
    private val userRepository: UserRepository,
    private val petRepository: PetRepository,
    private val careRoutineRepository: CareRoutineRepository,
    private val careTaskRepository: CareTaskRepository,
    private val expenseRepository: ExpenseRepository,
    private val settingsStore: SettingsStore,
    private val contactStore: DelegationContactStore,
    private val syncManager: SyncManager,
    private val twoFactorRepository: TwoFactorRepository,
    private val userId: Long
) : ViewModel() {

    /** How the backup is getting on. */
    val syncStatus: StateFlow<SyncStatus> = syncManager.status

    /** The four preference choices, as one object the screen can render in a single pass. */
    val preferences: StateFlow<ProfilePreferences> = combine(
        settingsStore.weightUnit,
        settingsStore.reminderChannel,
        settingsStore.weekStart,
        settingsStore.currency,
        ::ProfilePreferences
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProfilePreferences()
    )

    /** The security-card switch whose value comes from the preferences file. */
    val security: StateFlow<ProfileSecurity> = settingsStore.careRemindersEnabled
        .map(::ProfileSecurity)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProfileSecurity()
        )

    /** Whether the account has an authenticator set up, and how many recovery codes it has left. */
    val twoFactor: StateFlow<TwoFactorStatus> = twoFactorRepository.observeStatus(userId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TwoFactorStatus()
        )

    /** Each pet with its routines, tasks and expenses. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val petCareFlow: Flow<List<PetCareData>> =
        petRepository.observePetsForOwner(userId).flatMapLatest { pets ->
            if (pets.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    pets.map { pet ->
                        combine(
                            careRoutineRepository.observeActiveRoutinesForPet(pet.id),
                            careTaskRepository.observeTasksForPet(pet.id),
                            expenseRepository.observeExpensesForPet(pet.id)
                        ) { routines, tasks, expenses -> PetCareData(pet, routines, tasks, expenses) }
                    }
                ) { perPet -> perPet.toList() }
            }
        }

    /** The completion history the streak is drawn from. */
    private val completionsFlow = careTaskRepository.observeCompletionsForOwnerSince(
        ownerId = userId,
        fromMillis = historyWindowStart()
    )

    /** The screen's state. */
    val uiState: StateFlow<ProfileUiState> = combine(
        userRepository.observeUser(userId),
        petCareFlow,
        completionsFlow,
        contactStore.contacts,
        settingsStore.weekStart
    ) { user, petCare, completions, contacts, weekStart ->
        val now = System.currentTimeMillis()
        val week = CareSchedule.weekContaining(now, weekStart.calendarDay)

        ProfileUiState(
            user = user,
            petCount = petCare.size,
            // The dashboard's own function, so the two screens cannot report different streaks.
            streakFullDays = HomeDashboard
                .streak(petCare, completions, now, weekStart.calendarDay)
                .count { it.isFull },
            tasksThisWeek = tasksDueAcross(petCare, week),
            contacts = contacts,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProfileUiState()
    )

    private val _events = Channel<ProfileEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Preferences and security.

    /* Each of these is a single line handing the choice to the store, and that is the whole point. */

    fun onWeightUnitChosen(unit: WeightUnit) = settingsStore.setWeightUnit(unit)

    fun onReminderChannelChosen(channel: ReminderChannel) =
        settingsStore.setReminderChannel(channel)

    fun onWeekStartChosen(weekStart: WeekStart) = settingsStore.setWeekStart(weekStart)

    fun onCurrencyChosen(currency: CurrencyPreference) = settingsStore.setCurrency(currency)

    /** True while a turn-off is in flight, so a second confirm cannot start another. */
    private var isDisablingTwoFactor = false

    /** Turns two-factor authentication off, once [code] has been accepted. */
    fun onTwoFactorDisableConfirmed(code: String) {
        if (isDisablingTwoFactor) return
        isDisablingTwoFactor = true

        viewModelScope.launch {
            val event = when (twoFactorRepository.verify(userId, code)) {
                TwoFactorCheck.Correct, is TwoFactorCheck.RecoveryCodeAccepted -> {
                    twoFactorRepository.disable(userId)
                    ProfileEvent.TwoFactorDisabled
                }

                TwoFactorCheck.RecoveryCodeAlreadyUsed ->
                    ProfileEvent.TwoFactorDisableFailed(R.string.two_factor_recovery_already_used)

                TwoFactorCheck.Incorrect ->
                    ProfileEvent.TwoFactorDisableFailed(R.string.error_code_incorrect)

                // Already off — turned off on another device, or while this dialog was open.
                TwoFactorCheck.NotEnabled -> ProfileEvent.TwoFactorDisabled
            }

            isDisablingTwoFactor = false
            _events.send(event)
        }
    }

    /** Turns care reminders on or off. */
    fun onCareRemindersChanged(enabled: Boolean) = settingsStore.setCareRemindersEnabled(enabled)

    // Delegation contacts.

    /** Saves a delegation contact, once its name and number are acceptable. */
    fun onContactAdded(name: String, phoneRegion: String, phone: String, note: String) {
        val trimmedName = name.trim()
        val trimmedPhone = phone.trim()

        val event = when {
            trimmedName.isEmpty() || trimmedPhone.isEmpty() -> ProfileEvent.ContactIncomplete
            PhoneNumbers.check(phoneRegion, trimmedPhone) != PhoneCheck.VALID ->
                ProfileEvent.ContactPhoneInvalid

            else -> null
        }

        if (event != null) {
            viewModelScope.launch { _events.send(event) }
            return
        }

        contactStore.add(
            name = trimmedName,
            // Just confirmed valid above, so toE164 cannot return null here; the elvis is there.
            phoneNumber = PhoneNumbers.toE164(phoneRegion, trimmedPhone) ?: trimmedPhone,
            note = note.trim().takeIf { it.isNotEmpty() }
        )
    }

    fun onContactRemoved(contactId: String) {
        contactStore.remove(contactId)
    }

    // Data and privacy.

    /** Builds the CSV and hands it to the screen to save. */
    fun onExportRequested() {
        viewModelScope.launch {
            val petCare = petCareFlow.first()

            val csv = RecordsCsv.build(
                pets = petCare.map { it.pet },
                tasksByPetId = petCare.associate { it.pet.id to it.tasks },
                expensesByPetId = petCare.associate { it.pet.id to it.expenses }
            )

            _events.send(
                if (csv == null) {
                    ProfileEvent.ExportEmpty
                } else {
                    ProfileEvent.ExportReady(
                        csv = csv,
                        suggestedFileName = RecordsCsv.fileNameDate(System.currentTimeMillis())
                    )
                }
            )
        }
    }

    /** Records a newly chosen profile photo. */
    fun onPhotoChosen(photoPath: String) {
        viewModelScope.launch {
            userRepository.updatePhoto(userId, photoPath)
        }
    }

    /** Asks the backup to run now — what tapping the "Backup & sync" row does. */
    fun onBackupRequested() {
        syncManager.onUserSignedIn(userId)
    }

    /** True while a deletion is in flight, so a second tap cannot start another one. */
    private var isDeleting = false

    /** Deletes the account and everything belonging to it, once Firebase has accepted [password]. */
    fun onDeleteAccountConfirmed(password: String) {
        if (isDeleting) return
        isDeleting = true

        viewModelScope.launch {
            val event = when (val result = userRepository.deleteAccount(userId, password)) {
                // Already gone counts as done: either way there is no account to stay signed in to.
                AccountDeletionResult.Deleted, AccountDeletionResult.AccountMissing ->
                    ProfileEvent.AccountDeleted

                is AccountDeletionResult.Failed ->
                    ProfileEvent.AccountDeletionFailed(deletionFailureMessage(result.reason))
            }
            isDeleting = false
            _events.send(event)
        }
    }

    /** What to tell the user when Firebase would not delete the account. */
    @StringRes
    private fun deletionFailureMessage(reason: AuthFailure): Int = when (reason) {
        AuthFailure.INVALID_CREDENTIALS -> R.string.profile_delete_wrong_password
        AuthFailure.NO_NETWORK -> R.string.auth_error_network
        AuthFailure.RATE_LIMITED -> R.string.auth_error_rate_limited
        AuthFailure.NOT_SIGNED_IN, AuthFailure.ACCOUNT_UNAVAILABLE -> R.string.auth_error_sign_in_again
        else -> R.string.auth_error_unknown
    }

    /** How many care tasks fall due across the seven days of [week]. */
    private fun tasksDueAcross(petCare: List<PetCareData>, week: List<Long>): Int =
        week.sumOf { dayStart ->
            petCare.sumOf { CareSchedule.tasksDueOn(it.routines, it.tasks, dayStart).size }
        }

    /** Midnight a week ago, the earliest day the streak can ask about. */
    private fun historyWindowStart(): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = CareSchedule.startOfToday()
            add(Calendar.DAY_OF_MONTH, -HomeDashboard.STREAK_DAYS)
        }
        return CareSchedule.startOfDay(calendar.timeInMillis)
    }

    companion object {

        /** Builds the ViewModel for a particular account. */
        fun factory(userId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication

                ProfileViewModel(
                    userRepository = application.userRepository,
                    petRepository = application.petRepository,
                    careRoutineRepository = application.careRoutineRepository,
                    careTaskRepository = application.careTaskRepository,
                    expenseRepository = application.expenseRepository,
                    settingsStore = application.settingsStore,
                    contactStore = application.delegationContactStore,
                    syncManager = application.syncManager,
                    twoFactorRepository = application.twoFactorRepository,
                    userId = userId
                )
            }
        }
    }
}
