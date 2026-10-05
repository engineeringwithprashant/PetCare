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

/** The parts of the routine form the ViewModel owns. */
data class RoutineFormState(
    val isEditMode: Boolean = false,
    val repeatUnit: RepeatUnit = RepeatUnit.DAY,
    val isSaving: Boolean = false,
    @param:StringRes val titleError: Int? = null
)

/** A one-off occurrence the routine form reacts to exactly once. */
sealed interface RoutineEvent {

    /** Carries a loaded routine's name so the fragment can fill in the text field. */
    data class PrefillForm(val title: String) : RoutineEvent

    /** The routine was created or updated; the screen should close. */
    data object Saved : RoutineEvent

    /** Something failed in a way not tied to the name field. */
    data class Error(@param:StringRes val messageRes: Int) : RoutineEvent
}

/** Backs the care routine form, in both its modes: creating a routine and editing one. */
class RoutineViewModel(
    private val careRoutineRepository: CareRoutineRepository,
    private val petId: Long,
    private val routineId: Long = NO_ROUTINE_ID
) : ViewModel() {

    private val isEditMode: Boolean = routineId != NO_ROUTINE_ID

    /** The routine as loaded from the database, kept so a save can preserve everything the form does not show. */
    private var loadedRoutine: CareRoutine? = null

    private val _formState = MutableStateFlow(
        // In edit mode the save button starts disabled until the routine has loaded.
        RoutineFormState(isEditMode = isEditMode, isSaving = isEditMode)
    )
    val formState: StateFlow<RoutineFormState> = _formState.asStateFlow()

    private val _events = Channel<RoutineEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        if (isEditMode) {
            loadExistingRoutine()
        }
    }

    /** Fetches the routine being edited and pushes its values into the form. */
    private fun loadExistingRoutine() {
        viewModelScope.launch {
            val routine = careRoutineRepository.getRoutine(routineId)

            if (routine == null) {
                // Deleted elsewhere — on another device, via sync — while this form was opening.
                _formState.value = _formState.value.copy(isSaving = false)
                _events.send(RoutineEvent.Error(R.string.error_routine_not_found))
                return@launch
            }

            loadedRoutine = routine
            _formState.value = _formState.value.copy(
                isSaving = false,
                repeatUnit = routine.repeatUnit
            )
            _events.send(RoutineEvent.PrefillForm(routine.title))
        }
    }

    /** Records whether the user picked daily or weekly. */
    fun onScheduleSelected(repeatUnit: RepeatUnit) {
        _formState.value = _formState.value.copy(repeatUnit = repeatUnit)
    }

    /** Clears the name error as the user starts correcting it. */
    fun clearTitleError() {
        _formState.value = _formState.value.copy(titleError = null)
    }

    /** Validates the name and creates or updates the routine. */
    fun save(title: String) {
        val trimmedTitle = title.trim()

        if (trimmedTitle.isEmpty()) {
            _formState.value = _formState.value.copy(titleError = R.string.error_routine_name_required)
            return
        }

        // In edit mode with nothing loaded there is nothing to update; the load failure has already been reported.
        if (isEditMode && loadedRoutine == null) return

        viewModelScope.launch {
            _formState.value = _formState.value.copy(isSaving = true, titleError = null)

            val existing = loadedRoutine
            val repeatUnit = _formState.value.repeatUnit

            if (existing != null) {
                // `copy` keeps the start date untouched, deliberately.
                careRoutineRepository.updateRoutine(
                    existing.copy(title = trimmedTitle, repeatUnit = repeatUnit)
                )
            } else {
                careRoutineRepository.addRoutine(
                    CareRoutine(
                        petId = petId,
                        title = trimmedTitle,
                        // The form offers only "every day" and "every week", so the interval is always one.
                        repeatInterval = 1,
                        repeatUnit = repeatUnit,
                        // Anchored to midnight today. The schedule is worked out by counting whole days from this point.
                        startDate = CareSchedule.startOfToday()
                    )
                )
            }

            _events.send(RoutineEvent.Saved)
        }
    }

    companion object {

        /** [routineId] value meaning "create a new routine". Matches the default in the nav graph. */
        const val NO_ROUTINE_ID = -1L

        fun factory(petId: Long, routineId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                RoutineViewModel(application.careRoutineRepository, petId, routineId)
            }
        }
    }
}
