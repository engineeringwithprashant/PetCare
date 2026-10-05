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

/** How often a vaccine is repeated — the four options of the "repeats every" control. */
// The annotation is targeted at the getter rather than the constructor parameter.
enum class RepeatInterval(val months: Int?, @get:StringRes val labelRes: Int) {
    SIX_MONTHS(6, R.string.record_repeat_6_months),
    ONE_YEAR(12, R.string.record_repeat_1_year),
    THREE_YEARS(36, R.string.record_repeat_3_years),
    ONCE(null, R.string.record_repeat_once)
}

/** The record screen, as its views draw it. */
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

    /** Which preset chips to offer, from the pet's species. */
    val presets: List<Int>
        get() = when (pet?.species) {
            PetSpecies.CAT -> CAT_PRESETS
            PetSpecies.DOG -> DOG_PRESETS
            else -> emptyList()
        }

    /** Whether the record can be saved. */
    val canSave: Boolean get() = vaccineName.isNotBlank() && !isSaving

    /** Whether the checklist switch shows as on. */
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

        /** Midnight UTC today — the date the form opens on, and the base its default due date is derived from. */
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

    /** The record is in the database, with what the pet detail screen needs to confirm it and offer undo. */
    data class Saved(
        val recordId: Long,
        val vaccineName: String,
        val petName: String,
        val taskId: Long
    ) : AddVaccinationEvent
}

/** Holds the "Record a vaccination" form, works out the next due date, and writes the record. */
class AddVaccinationViewModel(
    private val petRepository: PetRepository,
    private val vaccinationRepository: VaccinationRepository,
    private val careTaskRepository: CareTaskRepository,
    private val petId: Long
) : ViewModel() {

    private val _formState = MutableStateFlow(VaccinationFormState())
    val formState: StateFlow<VaccinationFormState> = _formState.asStateFlow()

    /** One-shot events, as a [Channel] rather than more state. */
    private val _events = Channel<AddVaccinationEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // One read rather than an ongoing observation: the screen needs the pet's name and species.
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

    /** A new date given was picked — so the due date is re-derived from the current interval. */
    fun onDateGivenSelected(dateGiven: Long) {
        _formState.update { state ->
            state.copy(
                dateGiven = dateGiven,
                dateDue = dueDateFor(dateGiven, state.interval)
            )
        }
    }

    /** A due date was picked by hand. */
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

    /** The due date implied by a date given and an interval: null for a one-off dose. */
    private fun dueDateFor(dateGiven: Long, interval: RepeatInterval): Long? =
        interval.months?.let { months -> addMonths(dateGiven, months) }

    /** Writes the record, and the checklist task beside it when one was asked for. */
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

            // isChecklistOn rather than the raw preference.
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

    /** Creates the "Rabies due" task on the due date. */
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

        /** Builds the ViewModel for one pet. A function rather than a property. */
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
