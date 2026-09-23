package com.prash.petcare.ui.pet

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.PetSpecies
import com.prash.petcare.data.local.entity.TaskCategory
import com.prash.petcare.data.local.entity.VaccinationRecord
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.data.repository.VaccinationRepository
import com.prash.petcare.util.addMonths
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

/**
 * How often a vaccine is repeated — the four options of the "repeats every" control.
 *
 * Modelled as an enum with the interval *in months* rather than as four loose buttons, because that
 * is what makes the rule below a single calculation: picking any option sets the next due date to
 * the date given plus [months], and "once" is simply the option with no interval at all.
 *
 * @property months Calendar months until the next dose, or null for a one-off dose.
 * @property labelRes The words on the segment.
 */
// The annotation is targeted at the getter rather than the constructor parameter, so lint can check
// that every read of `interval.labelRes` really is a string resource — the same arrangement NavTab
// uses for its icons and labels.
enum class RepeatInterval(val months: Int?, @get:StringRes val labelRes: Int) {
    SIX_MONTHS(6, R.string.record_repeat_6_months),
    ONE_YEAR(12, R.string.record_repeat_1_year),
    THREE_YEARS(36, R.string.record_repeat_3_years),
    ONCE(null, R.string.record_repeat_once)
}

/**
 * The record screen, as its views draw it.
 *
 * Everything the screen shows is here, including the two dates. They are state rather than being
 * left in the date fields for a reason that is the whole point of this screen: the dates are *linked*
 * — moving the date given moves the next due date with it — and two views that read each other's
 * text cannot express a link like that without one of them becoming the source of truth by accident.
 *
 * @property pet The animal the record is for, or null while it loads. It supplies the subtitle and,
 *   through its species, which preset chips are offered.
 * @property vaccineName What has been typed or chosen. Held here, not read out of the field when
 *   needed, so the summary and the save button can react to it as it changes.
 * @property dateGiven The day the dose was given, as midnight UTC. Never null: it starts at today,
 *   because a record being entered now is almost always for a shot given now.
 * @property dateDue The next due date, or null for a one-off dose. Derived from [dateGiven] and
 *   [interval] whenever either changes — and then freely overridable by hand, see [onDueDateSelected].
 * @property interval The chosen repeat, which is what [dateDue] is derived from.
 * @property clinic Where it was given. Optional.
 * @property addToChecklist Whether saving should also create a vet-visit task on the due date.
 * @property isSaving True while the write is in flight, so a second tap cannot record it twice.
 */
data class VaccinationFormState(
    val pet: Pet? = null,
    val vaccineName: String = "",
    val dateGiven: Long = todayUtc(),
    val dateDue: Long? = addMonths(todayUtc(), DEFAULT_INTERVAL.months ?: 0),
    val interval: RepeatInterval = DEFAULT_INTERVAL,
    val clinic: String = "",
    val addToChecklist: Boolean = true,
    val isSaving: Boolean = false
) {

    /**
     * Which preset chips to offer, from the pet's species.
     *
     * A cat is never offered DHPP and a dog is never offered FVRCP: a shortcut that fills in a
     * vaccine the animal cannot be given is worse than no shortcut, because it invites a wrong
     * record that looks deliberate.
     *
     * Any other species gets no chips rather than a generic list. The field is still there and still
     * takes anything typed into it, which is the honest answer for a rabbit or a parrot — the app
     * does not know that species' schedule and should not pretend to.
     */
    val presets: List<Int>
        get() = when (pet?.species) {
            PetSpecies.CAT -> CAT_PRESETS
            PetSpecies.DOG -> DOG_PRESETS
            else -> emptyList()
        }

    /**
     * Whether the record can be saved.
     *
     * The two things a record cannot be meaningful without: something that was given, and the day it
     * was given on. The date always has a value, so in practice this waits on the name — but both are
     * stated, because "the date is never null" is a property of this class today rather than a rule
     * the save should depend on.
     */
    val canSave: Boolean get() = vaccineName.isNotBlank() && !isSaving

    /**
     * Whether the checklist switch shows as on.
     *
     * **Forced off when there is no due date**, whatever the user last chose. The task it would
     * create is a task *on the due date*, so with no due date there is nothing to create — and a
     * switch left sitting in the on position would be promising one. The stored preference is
     * deliberately not cleared, so choosing "Once" and changing back to "1 yr" restores the switch
     * rather than silently dropping the user's choice.
     */
    val isChecklistOn: Boolean get() = addToChecklist && dateDue != null

    /** The pet's first name, for the summary line: "Krishala" from "Krishala Pant Thapa Baby". */
    val petFirstName: String get() = pet?.name?.trim()?.substringBefore(' ').orEmpty()

    companion object {
        /** A yearly booster is by far the most common case, so the form opens on it. */
        private val DEFAULT_INTERVAL = RepeatInterval.ONE_YEAR

        private val CAT_PRESETS = listOf(
            R.string.record_preset_fvrcp,
            R.string.record_preset_rabies,
            R.string.record_preset_felv,
            R.string.record_preset_deworming
        )

        private val DOG_PRESETS = listOf(
            R.string.record_preset_rabies,
            R.string.record_preset_dhpp,
            R.string.record_preset_leptospirosis,
            R.string.record_preset_bordetella
        )

        /**
         * Midnight UTC today — the date the form opens on, and the base its default due date is
         * derived from.
         *
         * UTC for the reason given in [com.prash.petcare.util.VaccinationDates]: a stored date is a
         * calendar day, and Material's date picker hands back its selection in the same form, so a
         * date the user never touches and one they pick from the calendar are the same kind of
         * value.
         */
        fun todayUtc(): Long {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            return calendar.timeInMillis
        }
    }
}

/** What the screen has to do once, rather than draw — see [AddVaccinationViewModel.events]. */
sealed interface AddVaccinationEvent {

    /**
     * The record is in the database. Carries everything the pet detail screen needs to confirm it
     * and to offer the undo.
     *
     * @property recordId The row just written.
     * @property vaccineName What was recorded, for the confirmation's wording.
     * @property petName The animal it was recorded for.
     * @property taskId The checklist task created alongside it, or [NO_TASK_ID] when the toggle was
     *   off or there was no due date to put one on. Carried so that undoing removes *both* — an undo
     *   that took the record back but left a "Rabies due" task on the checklist would be a half
     *   undo, and the stray task would have nothing left explaining where it came from.
     */
    data class Saved(
        val recordId: Long,
        val vaccineName: String,
        val petName: String,
        val taskId: Long
    ) : AddVaccinationEvent
}

/**
 * Holds the "Record a vaccination" form, works out the next due date, and writes the record.
 *
 * Its place in MVVM: [AddVaccinationFragment] draws [formState] and forwards every tap and keystroke
 * here; this class reads and writes only through repositories, holds no Context and knows nothing
 * about views. The date *picker* stays in the fragment, because a dialog needs a Context — but which
 * date is chosen, and what it does to the other one, is decided here.
 *
 * **The one piece of real logic is the link between the two dates**, and it is worth stating in full
 * because three different actions touch it:
 *
 *  - choosing an interval sets the due date to the date given plus that interval;
 *  - choosing "Once" clears the due date, because a one-off dose has no next one;
 *  - moving the date given re-derives the due date from whichever interval is currently chosen.
 *
 * After all that, the user can still pick a due date by hand and it sticks. That is deliberate: the
 * derivation is a convenience that gets the common case right in one tap, not a rule about what a
 * due date is allowed to be — a vet who says "bring her back in ten weeks" must be able to win.
 *
 * @param petRepository Source of the pet, for the subtitle and the species-aware chips.
 * @param vaccinationRepository Where the record is written.
 * @param careTaskRepository Where the optional checklist task is written.
 * @param petId Which pet the record is for, passed in as a navigation argument.
 */
class AddVaccinationViewModel(
    private val petRepository: PetRepository,
    private val vaccinationRepository: VaccinationRepository,
    private val careTaskRepository: CareTaskRepository,
    private val petId: Long
) : ViewModel() {

    private val _formState = MutableStateFlow(VaccinationFormState())
    val formState: StateFlow<VaccinationFormState> = _formState.asStateFlow()

    /**
     * How many pets the owner has, for the shared header's count badge.
     *
     * Separate from [formState] because it is *chrome* rather than part of the form — it belongs to
     * the header this screen draws in common with every tab, and mixing it into the form's state
     * would make an unrelated pet being added elsewhere look like a change to the record being
     * entered.
     *
     * The owner comes from the pet rather than from the signed-in session, for the reason given on
     * [PetDetailViewModel]: the two can then never disagree about whose animal this is. Hence
     * [flatMapLatest] — the count query can only be opened once the pet has loaded and named an
     * owner. [distinctUntilChanged] stops that query being torn down and reopened if the pet row
     * re-emits with the same owner.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val petCount: StateFlow<Int> = petRepository.observePet(petId)
        .map { it?.ownerId }
        .distinctUntilChanged()
        .flatMapLatest { ownerId ->
            if (ownerId == null) flowOf(0) else petRepository.observePetCountForOwner(ownerId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    /**
     * One-shot events, as a [Channel] rather than more state.
     *
     * "The record was saved" happens once and must be acted on once. Published as state it would be
     * replayed after a rotation and close the screen a second time — the same reasoning as the other
     * form screens in this app.
     */
    private val _events = Channel<AddVaccinationEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // One read rather than an ongoing observation: the screen needs the pet's name and species,
        // neither of which can change while this form is open on top of the pet's own screen.
        viewModelScope.launch {
            val pet = petRepository.observePet(petId).first()
            _formState.update { it.copy(pet = pet) }
        }
    }

    /** The vaccine field changed, by typing or by tapping a preset chip. */
    fun onVaccineNameChanged(name: String) {
        _formState.update { it.copy(vaccineName = name) }
    }

    /** The clinic field changed. */
    fun onClinicChanged(clinic: String) {
        _formState.update { it.copy(clinic = clinic) }
    }

    /**
     * A new date given was picked — so the due date is re-derived from the current interval.
     *
     * This is the rule people expect and rarely notice: correcting "given" from today to last
     * Tuesday should move next year's booster to last Tuesday too, not leave it a week out. A hand-
     * picked due date is overwritten here, which is the right trade — the user has just changed the
     * value the other one is measured from.
     */
    fun onDateGivenSelected(dateGiven: Long) {
        _formState.update { state ->
            state.copy(
                dateGiven = dateGiven,
                dateDue = dueDateFor(dateGiven, state.interval)
            )
        }
    }

    /**
     * A due date was picked by hand.
     *
     * Only the due date moves. The interval is left showing whatever was chosen, because it is a
     * record of how the current date was arrived at rather than a constraint on it — and the moment
     * the user touches it again, the derivation takes over and overwrites this date, which is the
     * behaviour a segmented control implies.
     */
    fun onDueDateSelected(dateDue: Long) {
        _formState.update { it.copy(dateDue = dateDue) }
    }

    /** An interval was chosen — the due date follows from it and the current date given. */
    fun onIntervalSelected(interval: RepeatInterval) {
        _formState.update { state ->
            state.copy(
                interval = interval,
                dateDue = dueDateFor(state.dateGiven, interval)
            )
        }
    }

    /** The checklist row was pressed. */
    fun onChecklistToggled() {
        _formState.update { it.copy(addToChecklist = !it.addToChecklist) }
    }

    /**
     * The due date implied by a date given and an interval: null for a one-off dose, and otherwise
     * the same day of the month, that many calendar months later.
     *
     * Calendar months rather than a fixed number of days — see [addMonths] for why that distinction
     * matters to an annual booster.
     */
    private fun dueDateFor(dateGiven: Long, interval: RepeatInterval): Long? =
        interval.months?.let { months -> addMonths(dateGiven, months) }

    /**
     * Writes the record, and the checklist task beside it when one was asked for.
     *
     * The record goes first. If the task write were to fail the record would still be saved, which is
     * the right way round: the record is what the user came to enter, and a missing reminder is
     * recoverable in a way a missing vaccination history is not.
     *
     * The name is trimmed on the way in, so a stray space cannot produce two entries that look
     * identical in the history. A blank clinic becomes null rather than an empty string, which is
     * what lets the row's meta line tell "no clinic recorded" apart from one recorded as nothing.
     *
     * [VaccinationFormState.isSaving] guards a second tap: without it a double press would insert the
     * same shot twice.
     *
     * @param taskTitleFormat The checklist task's title as an unfilled format string ("%1$s due"),
     *   and [taskSupplies] the text for its supplies field. Both are passed in from the fragment
     *   rather than looked up here, because they come from string resources and a ViewModel holds no
     *   Context to resolve one with — the same arrangement the "Add a place" screen uses.
     */
    fun save(taskTitleFormat: String, taskSupplies: String) {
        val state = _formState.value
        if (!state.canSave) return

        _formState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val vaccineName = state.vaccineName.trim()

            val recordId = vaccinationRepository.addRecord(
                VaccinationRecord(
                    petId = petId,
                    vaccineName = vaccineName,
                    dateGiven = state.dateGiven,
                    dateDue = state.dateDue,
                    clinic = state.clinic.trim().takeIf { it.isNotBlank() }
                )
            )

            // isChecklistOn rather than the raw preference, so a form left with the switch on and the
            // interval later set to "Once" cannot create a task with no date to put it on.
            val taskId = if (state.isChecklistOn && state.dateDue != null) {
                createChecklistTask(
                    title = taskTitleFormat.format(vaccineName),
                    supplies = taskSupplies,
                    dateDue = state.dateDue
                )
            } else {
                NO_TASK_ID
            }

            _events.send(
                AddVaccinationEvent.Saved(
                    recordId = recordId,
                    vaccineName = vaccineName,
                    petName = state.pet?.name.orEmpty(),
                    taskId = taskId
                )
            )
        }
    }

    /**
     * Creates the "Rabies due" task on the due date.
     *
     * It is an ordinary care task in the existing [TaskCategory.VET_VISIT] category rather than a new
     * kind of thing, which is what makes it appear on the checklist, in the "coming up this week"
     * card and in the reminder alarms with no other screen changing — the app already knows how to
     * carry a dated vet visit.
     *
     * **`routineId` is left null on purpose.** A routine is a repeating schedule, and this is a
     * single dated appointment; attaching it to one would make it recur. The `care_tasks` foreign key
     * to routines is SET_NULL precisely so a task can exist without one — see [CareTask].
     *
     * `dueAt` is the due date at [TASK_TIME_MINUTES], late enough in the morning to be a plausible
     * appointment and to fall after any daily care. The supplies field carries the vaccine card,
     * which is the one thing that has to be in the owner's bag for the visit to be worth making.
     *
     * The two are set together rather than one being derived from the other at read time, because
     * the checklist uses `timeOfDayMinutes` to *write* the time on the row and `dueAt` to decide
     * which day the row belongs to — a task that set only one of them would appear on the right day
     * with no time, or at the right time on no day.
     */
    private suspend fun createChecklistTask(
        title: String,
        supplies: String,
        dateDue: Long
    ): Long = careTaskRepository.addTask(
        CareTask(
            petId = petId,
            title = title,
            requiredSupplies = supplies,
            category = TaskCategory.VET_VISIT,
            timeOfDayMinutes = TASK_TIME_MINUTES,
            dueAt = dateDue + TASK_TIME_MINUTES * MILLIS_PER_MINUTE
        )
    )

    companion object {

        /** No checklist task was created — see [AddVaccinationEvent.Saved.taskId]. */
        const val NO_TASK_ID = -1L

        /** 11:00, as minutes since midnight — the time the design gives the vet-visit task. */
        private const val TASK_TIME_MINUTES = 11 * 60

        private const val MILLIS_PER_MINUTE = 60L * 1000L

        /**
         * Builds the ViewModel for one pet. A function rather than a property because the pet id is
         * only known at runtime, from the navigation arguments — the same arrangement as
         * [PetDetailViewModel.factory].
         */
        fun factory(petId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                AddVaccinationViewModel(
                    petRepository = application.petRepository,
                    vaccinationRepository = application.vaccinationRepository,
                    careTaskRepository = application.careTaskRepository,
                    petId = petId
                )
            }
        }
    }
}
