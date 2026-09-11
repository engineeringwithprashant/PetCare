package com.prash.petcare.ui.routine

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.RepeatUnit
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.util.CareSchedule
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The parts of the routine form the ViewModel owns.
 *
 * The typed name lives in its EditText, which restores itself across rotation. What is held here is
 * the chosen schedule, which is a pair of toggle buttons rather than a field, plus the error and
 * progress the screen has to display.
 *
 * @property repeatUnit Whether the routine runs every day or every week.
 * @property isSaving True while the write is in flight, so the button can be disabled.
 */
data class RoutineFormState(
    val repeatUnit: RepeatUnit = RepeatUnit.DAY,
    val isSaving: Boolean = false,
    @param:StringRes val titleError: Int? = null
)

/** A one-off occurrence the routine form reacts to exactly once. */
sealed interface RoutineEvent {
    /** The routine was created; the screen should close. */
    data object Saved : RoutineEvent
}

/**
 * Backs the "create a care routine" form.
 *
 * A routine is the schedule that a group of care tasks share — "every morning", "every Sunday".
 * It holds no tasks of its own at creation time; the user adds those from the routine's own screen
 * afterwards.
 *
 * @param careRoutineRepository Data access, injected so tests can supply a fake.
 * @param petId The pet this routine will belong to.
 */
class RoutineViewModel(
    private val careRoutineRepository: CareRoutineRepository,
    private val petId: Long
) : ViewModel() {

    private val _formState = MutableStateFlow(RoutineFormState())
    val formState: StateFlow<RoutineFormState> = _formState.asStateFlow()

    private val _events = Channel<RoutineEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Records whether the user picked daily or weekly. */
    fun onScheduleSelected(repeatUnit: RepeatUnit) {
        _formState.value = _formState.value.copy(repeatUnit = repeatUnit)
    }

    /** Clears the name error as the user starts correcting it. */
    fun clearTitleError() {
        _formState.value = _formState.value.copy(titleError = null)
    }

    /** Validates the name and saves the routine. */
    fun save(title: String) {
        val trimmedTitle = title.trim()

        if (trimmedTitle.isEmpty()) {
            _formState.value = _formState.value.copy(titleError = R.string.error_routine_name_required)
            return
        }

        viewModelScope.launch {
            _formState.value = _formState.value.copy(isSaving = true, titleError = null)

            val routine = CareRoutine(
                petId = petId,
                title = trimmedTitle,
                // The form offers only "every day" and "every week", so the interval is always
                // one. The entity supports larger gaps — every three weeks, say — for later.
                repeatInterval = 1,
                repeatUnit = _formState.value.repeatUnit,
                // Anchored to midnight today. The schedule is worked out by counting whole days
                // from this point, so a mid-afternoon creation time would make a weekly routine
                // land awkwardly between days.
                startDate = CareSchedule.startOfToday()
            )

            careRoutineRepository.addRoutine(routine)

            _events.send(RoutineEvent.Saved)
        }
    }

    companion object {
        fun factory(petId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                RoutineViewModel(application.careRoutineRepository, petId)
            }
        }
    }
}
