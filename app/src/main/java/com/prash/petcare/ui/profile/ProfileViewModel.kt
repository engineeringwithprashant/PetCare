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

/**
 * The four preference choices, gathered so the screen renders them from one object.
 *
 * @property weightUnit Kilograms or pounds, on the pet cards and the pet detail screen.
 * @property reminderChannel How the user would rather be told — see [ReminderChannel] on how far that
 *   choice actually reaches.
 * @property weekStart Which day the Tasks week strip and the care streak begin on.
 * @property currency Which symbol money is written with. Never a conversion.
 */
data class ProfilePreferences(
    val weightUnit: WeightUnit = WeightUnit.METRIC,
    val reminderChannel: ReminderChannel = ReminderChannel.PUSH,
    val weekStart: WeekStart = WeekStart.MONDAY,
    val currency: CurrencyPreference = CurrencyPreference.NPR
)

/**
 * The security-card setting whose value lives in the preferences file.
 *
 * Two-factor authentication is deliberately *not* here, even though its switch sits in the same card.
 * It is not a preference: it is a property of the account, it lives in the database beside the
 * account's own row, and it means nothing without the secret and recovery codes stored with it. So it
 * is published separately, from [ProfileViewModel.twoFactor], by the repository that owns it. Folding
 * it into this object would have put one fact about the account into a file about this installation.
 *
 * A wrapper around a single Boolean may look like ceremony, and today it nearly is — the "unlock with
 * fingerprint" switch that used to sit beside it has been removed (see [SettingsStore]). It is kept
 * as a type rather than collapsed to a bare `StateFlow<Boolean>` because the screen renders this card
 * from one object, and adding the next preference-backed security row should be a field here rather
 * than a new flow, a new collector and a new render call.
 */
data class ProfileSecurity(
    val careRemindersEnabled: Boolean = true
)

/**
 * Everything the profile screen displays.
 *
 * @property user The signed-in account, or null while the first database read is in flight.
 * @property petCount How many pets are in care — the header pill and the first stat card.
 * @property streakFullDays How many days this week every due task was completed. The dashboard's own
 *   figure, from the dashboard's own function, so the two screens can never report different streaks.
 * @property tasksThisWeek How many care tasks fall due across the current week.
 * @property contacts The people saved for delegation.
 * @property isLoading True until the first database read returns, so the screen can tell "still
 *   loading" apart from "genuinely empty".
 */
data class ProfileUiState(
    val user: User? = null,
    val petCount: Int = 0,
    val streakFullDays: Int = 0,
    val tasksThisWeek: Int = 0,
    val contacts: List<DelegationContact> = emptyList(),
    val isLoading: Boolean = true
)

/**
 * What the screen should do once, in response to something the user did.
 *
 * Events rather than state, because each is a one-off: a message to show, a file to write, a screen to
 * leave. Held in state they would fire again on every rotation — the user would see "Records saved"
 * each time they turned the phone.
 */
sealed interface ProfileEvent {

    /** The CSV is ready; the fragment hands it to the system's save dialog. */
    data class ExportReady(val csv: String, val suggestedFileName: String) : ProfileEvent

    /** There was nothing to export. */
    data object ExportEmpty : ProfileEvent

    /** A delegation contact was saved without a name or without a number; nothing was added. */
    data object ContactIncomplete : ProfileEvent

    /**
     * A delegation contact's number is not a real number for the country chosen; nothing was added.
     *
     * Kept apart from [ContactIncomplete] because the two send the user to different places: one
     * field is empty, or the country beside the number is wrong.
     */
    data object ContactPhoneInvalid : ProfileEvent

    /** The account and everything under it is gone; the fragment returns to the sign-in screen. */
    data object AccountDeleted : ProfileEvent

    /** Deleting failed (wrong password, no network…), so nothing was deleted. */
    data class AccountDeletionFailed(@param:StringRes val messageRes: Int) : ProfileEvent

    /** Two-factor authentication has been turned off, and the secret and codes are gone. */
    data object TwoFactorDisabled : ProfileEvent

    /**
     * The code typed to turn two-factor authentication off was refused, so it is still on.
     *
     * Carries the message rather than a plain failure flag, because "that code is not right" and
     * "you have already used that recovery code" send the user to different places — see
     * [com.prash.petcare.data.repository.TwoFactorCheck].
     */
    data class TwoFactorDisableFailed(@param:StringRes val messageRes: Int) : ProfileEvent
}

/**
 * Supplies the profile screen with the account, the stats, the settings and the saved contacts, and
 * carries out the changes the screen asks for.
 *
 * Sits in the middle of the MVVM chain: it reads from [UserRepository], [PetRepository] and the two
 * local stores, and publishes state for [ProfileFragment] to render. It holds no Android view types,
 * so it survives rotation and can be tested without a device.
 *
 * **Why the stores are a dependency like any repository.** [SettingsStore] and
 * [DelegationContactStore] live in the data layer and expose flows, exactly as a repository does. They
 * are SharedPreferences rather than Room underneath, and each explains at length why — but that is a
 * storage decision, invisible from here. Nothing about depending on them differs from depending on a
 * DAO-backed repository.
 *
 * **Why the streak comes from [HomeDashboard] rather than being recomputed.** "Day streak" on this
 * screen and the streak row on the dashboard are the same fact, and a second implementation of a rule
 * as fiddly as "every task due that day was completed within that day" would drift from the first
 * within a release. Calling the dashboard's own pure function means they cannot disagree. It sits in
 * `ui/home` rather than in the data layer, which is a little awkward, but reaching sideways to a pure
 * function in the same layer is much better than duplicating the rule.
 *
 * @param userId Which account to show, read from the session — see
 *   [com.prash.petcare.ui.session.SessionViewModel].
 */
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

    /**
     * How the backup is getting on.
     *
     * The same status the dashboard shows, from the same source. Kept out of [uiState] for the reason
     * it is there too: the account's details and the sync's progress change on completely different
     * rhythms, and rebuilding a week of streak days because a sync ticked would be work for nothing.
     */
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

    /**
     * Whether the account has an authenticator set up, and how many recovery codes it has left.
     *
     * Straight from the repository, which reads it out of the database — so the row redraws by itself
     * when setup finishes on another screen, and when a recovery code is spent at sign-in. Nothing
     * here has to be told that something changed.
     */
    val twoFactor: StateFlow<TwoFactorStatus> = twoFactorRepository.observeStatus(userId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TwoFactorStatus()
        )

    /**
     * Each pet with its routines, tasks and expenses.
     *
     * The same shape the dashboard builds, and for the same reason: the streak is computed from a
     * single consistent snapshot of every pet's care. [flatMapLatest] rebuilds the per-pet queries
     * when a pet is added or removed and cancels the previous ones, so a deleted pet's queries stop
     * rather than accumulating. The empty case is short-circuited because `combine` over an empty list
     * never emits — without it, an account with no pets would sit on the loading state forever.
     *
     * Expenses are queried here even though this screen shows no money, because [PetCareData] carries
     * them and the export needs them. One subscription serving both is cheaper than two.
     */
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

    /**
     * The completion history the streak is drawn from.
     *
     * A week's worth, which is all the streak asks about. The start is fixed when the ViewModel is
     * created and does not move if the screen is left open — harmless, because the query is
     * "everything since then" and so still covers every newer day; it only carries a few extra rows.
     */
    private val completionsFlow = careTaskRepository.observeCompletionsForOwnerSince(
        ownerId = userId,
        fromMillis = historyWindowStart()
    )

    /**
     * The screen's state.
     *
     * Everything here is driven by Room and the preferences file, so there is no manual refresh
     * anywhere: adding a pet, ticking a task, saving a contact or changing the week start all arrive
     * as a new emission.
     *
     * `WhileSubscribed(5_000)` keeps the queries alive for five seconds after the last observer
     * leaves — long enough to survive a rotation, short enough that a backgrounded screen stops doing
     * work.
     */
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

    // ---------------------------------------------------------------------------------------------
    // Preferences and security
    // ---------------------------------------------------------------------------------------------

    /*
     * Each of these is a single line handing the choice to the store, and that is the whole point:
     * the store publishes a flow, every screen that cares reads that flow, and so a tap here redraws
     * the Pets tab's weight pills or the Money tab's symbols with nothing else to remember. There is
     * deliberately no "save" step and no confirmation — a preference that takes effect immediately and
     * can be tapped back is not a decision that needs one.
     */

    fun onWeightUnitChosen(unit: WeightUnit) = settingsStore.setWeightUnit(unit)

    fun onReminderChannelChosen(channel: ReminderChannel) =
        settingsStore.setReminderChannel(channel)

    fun onWeekStartChosen(weekStart: WeekStart) = settingsStore.setWeekStart(weekStart)

    fun onCurrencyChosen(currency: CurrencyPreference) = settingsStore.setCurrency(currency)

    /** True while a turn-off is in flight, so a second confirm cannot start another. */
    private var isDisablingTwoFactor = false

    /**
     * Turns two-factor authentication off, once [code] has been accepted.
     *
     * **The check and the change are one call**, deliberately. Verifying in the dialog and disabling
     * from a separate method would be two steps with a gap between them, and the gap is the bug: the
     * second could be reached without the first by any future caller who did not know the rule. Here
     * the only way to reach [TwoFactorRepository.disable] is through a code that has just been
     * accepted.
     *
     * A recovery code is accepted as well as an authenticator code, and doing so *spends* it — which
     * is right: it was used, and the codes are about to be deleted anyway.
     *
     * Every refusal leaves the account exactly as it was and says which refusal it is, so someone who
     * mistyped can try again and someone reading a spent line off their sheet is told to try another.
     */
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

                // Already off — turned off on another device, or while this dialog was open. There is
                // nothing to do and nothing has gone wrong, so this reports the outcome the user
                // asked for rather than an error about a state they cannot see.
                TwoFactorCheck.NotEnabled -> ProfileEvent.TwoFactorDisabled
            }

            isDisablingTwoFactor = false
            _events.send(event)
        }
    }

    /**
     * Turns care reminders on or off.
     *
     * The only switch on the screen that changes what the app *does* rather than how it looks:
     * [com.prash.petcare.reminder.ReminderManager] observes this value and withdraws every scheduled
     * alarm when it goes false, re-registering them all when it comes back. Nothing needs to be
     * called here to make that happen — it is watching the flow.
     */
    fun onCareRemindersChanged(enabled: Boolean) = settingsStore.setCareRemindersEnabled(enabled)

    // ---------------------------------------------------------------------------------------------
    // Delegation contacts
    // ---------------------------------------------------------------------------------------------

    /**
     * Saves a delegation contact, once its name and number are acceptable.
     *
     * **Why the checks moved here from the dialog.** They were a pair of `isEmpty()` calls in
     * [ProfileFragment], which was fine while "acceptable" meant "not blank". It no longer does: the
     * number now has to be a real number for the country chosen, because the whole point of this list
     * is that a delegation message can be *sent* to it, and a local number saved with no dialling
     * code will not leave the country it was typed in. A rule with that much behind it belongs where
     * the app's other rules are, and where it can be tested without driving a dialog.
     *
     * Both halves are required: a contact with no number is one nothing can be delegated to, which is
     * the single thing this list exists for.
     *
     * @param phoneRegion The country chosen beside the number box, and [phone] the local number typed
     *   next to it. Stored as one international number — see [PhoneNumbers].
     * @param note Optional; blank is stored as no note rather than as an empty line.
     */
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
            // Just confirmed valid above, so toE164 cannot return null here; the elvis is there
            // because the function is nullable, not because the fallback is reachable.
            phoneNumber = PhoneNumbers.toE164(phoneRegion, trimmedPhone) ?: trimmedPhone,
            note = note.trim().takeIf { it.isNotEmpty() }
        )
    }

    fun onContactRemoved(contactId: String) {
        contactStore.remove(contactId)
    }

    // ---------------------------------------------------------------------------------------------
    // Data and privacy
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds the CSV and hands it to the screen to save.
     *
     * **The ViewModel builds the text; the fragment writes the file.** Writing means a
     * `ContentResolver` and a URI the user picked through the system's save dialog, both of which are
     * Android framework objects a ViewModel must not touch. Splitting at exactly this point keeps the
     * rule intact and leaves the interesting half — what goes in the file — testable.
     *
     * The rows are taken with [first] rather than by collecting: an export is a snapshot of this
     * moment, not something that should keep producing new files as the data changes.
     */
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

    /**
     * Records a newly chosen profile photo.
     *
     * The file has already been written by the screen — copying a picked image is framework work
     * needing a Context — so what arrives here is a path, and the only thing left is the database
     * write. Deleting the *previous* file belongs to the repository, which is the one place that knows
     * what the old path was before this one replaced it; see [UserRepository.updatePhoto].
     */
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

    /**
     * Deletes the account and everything belonging to it, once Firebase has accepted [password].
     *
     * Called only after the screen has taken two confirmations, the second of which asks the user to
     * type the word and their password — see ProfileFragment. The word is not checked again here;
     * the password is checked by Firebase, inside [UserRepository.deleteAccount], which also explains
     * the order the account's two halves are deleted in.
     *
     * A failure leaves everything in place and is reported, so the user can fix it (a mistyped
     * password, no connection) and try again.
     */
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

    /**
     * How many care tasks fall due across the seven days of [week].
     *
     * Counted day by day through [CareSchedule.tasksDueOn], the same function the checklist and the
     * streak use, so "due" means exactly what it means everywhere else in the app — a repeating task
     * counts once for each day it lands on, which is what makes "18 tasks this week" the number of
     * things to actually do rather than the number of task rows in the database.
     */
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

        /**
         * Builds the ViewModel for a particular account.
         *
         * A function rather than a property because the user id is only known at runtime and has to
         * be captured here to reach the constructor.
         */
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
