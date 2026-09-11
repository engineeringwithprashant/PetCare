package com.prash.petcare.ui.task

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.TaskCategory
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.util.CareSchedule
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The parts of the care task form the ViewModel owns.
 *
 * The typed title, supplies and notes stay in their EditTexts, which restore themselves across
 * rotation. Held here are the two values chosen from pickers, plus errors and progress.
 *
 * @property category Which kind of care this is; the checklist groups by it.
 * @property timeOfDayMinutes Chosen time as minutes since midnight, or null if not yet picked.
 * @property isLoading True while the owning routine is being fetched.
 * @property isSaving True while the write is in flight.
 */
data class CareTaskFormState(
    val category: TaskCategory = TaskCategory.FEEDING,
    val timeOfDayMinutes: Int? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    @param:StringRes val titleError: Int? = null,
    @param:StringRes val timeError: Int? = null
)

/** A one-off occurrence the task form reacts to exactly once. */
sealed interface CareTaskEvent {
    /** The task was created; the screen should close. */
    data object Saved : CareTaskEvent

    /** Something failed that is not tied to a single field. */
    data class Error(@param:StringRes val messageRes: Int) : CareTaskEvent
}

/**
 * Backs the "add a care task" form.
 *
 * A task is one item of care inside a routine — what to do, and at what time of day. It does not
 * carry a date: the routine it belongs to decides which days it applies to, which is what lets a
 * single row stand for "every morning" rather than needing one row per day.
 *
 * The screen is opened with a routine id alone. The pet is read from that routine rather than
 * passed in separately, so the two can never disagree about which animal the task is for.
 *
 * @param careTaskRepository Where the new task is written.
 * @param careRoutineRepository Used to look up the owning routine.
 * @param routineId The routine this task will belong to.
 */
class CareTaskViewModel(
    private val careTaskRepository: CareTaskRepository,
    private val careRoutineRepository: CareRoutineRepository,
    private val routineId: Long
) : ViewModel() {

    /** The owning routine, needed for its pet and its start date. */
    private var routine: CareRoutine? = null

    private val _formState = MutableStateFlow(CareTaskFormState())
    val formState: StateFlow<CareTaskFormState> = _formState.asStateFlow()

    private val _events = Channel<CareTaskEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        loadRoutine()
    }

    private fun loadRoutine() {
        viewModelScope.launch {
            val loaded = careRoutineRepository.getRoutine(routineId)

            _formState.value = _formState.value.copy(isLoading = false)

            if (loaded == null) {
                // The routine was deleted while this screen was opening.
                _events.send(CareTaskEvent.Error(R.string.error_routine_not_found))
                return@launch
            }

            routine = loaded
        }
    }

    /** Records the category chosen in the dropdown. */
    fun onCategorySelected(category: TaskCategory) {
        _formState.value = _formState.value.copy(category = category)
    }

    /** Records the time chosen in the time picker, as minutes since midnight. */
    fun onTimeSelected(hour: Int, minute: Int) {
        _formState.value = _formState.value.copy(
            timeOfDayMinutes = hour * MINUTES_PER_HOUR + minute,
            timeError = null
        )
    }

    /** Clears the title error as the user starts correcting it. */
    fun clearTitleError() {
        _formState.value = _formState.value.copy(titleError = null)
    }

    /** Validates the form and saves the task. */
    fun save(title: String, requiredSupplies: String, notes: String) {
        val owningRoutine = routine
        val trimmedTitle = title.trim()
        val state = _formState.value

        val titleError = if (trimmedTitle.isEmpty()) R.string.error_task_title_required else null
        // A task without a time could not be placed in the day's running order, so it is required.
        val timeError = if (state.timeOfDayMinutes == null) R.string.error_task_time_required else null

        if (titleError != null || timeError != null) {
            _formState.value = state.copy(titleError = titleError, timeError = timeError)
            return
        }

        if (owningRoutine == null) {
            viewModelScope.launch { _events.send(CareTaskEvent.Error(R.string.error_routine_not_found)) }
            return
        }

        viewModelScope.launch {
            _formState.value = state.copy(isSaving = true, titleError = null, timeError = null)

            val minutes = state.timeOfDayMinutes ?: return@launch

            val task = CareTask(
                // Taken from the routine so the task, its routine and its pet always agree.
                petId = owningRoutine.petId,
                routineId = owningRoutine.id,
                title = trimmedTitle,
                notes = notes.trimToNull(),
                requiredSupplies = requiredSupplies.trimToNull(),
                category = state.category,
                timeOfDayMinutes = minutes,
                // The anchor date: the routine's own start day, at this task's time of day. Every
                // later occurrence is derived from the routine's schedule rather than stored.
                dueAt = CareSchedule.startOfDay(owningRoutine.startDate) +
                    minutes * MILLIS_PER_MINUTE
            )

            careTaskRepository.addTask(task)

            _events.send(CareTaskEvent.Saved)
        }
    }

    /** Blank input and "not provided" mean the same thing, so both are stored as null. */
    private fun String.trimToNull(): String? = trim().takeIf { it.isNotEmpty() }

    companion object {
        private const val MINUTES_PER_HOUR = 60
        private const val MILLIS_PER_MINUTE = 60_000L

        fun factory(routineId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                CareTaskViewModel(
                    careTaskRepository = application.careTaskRepository,
                    careRoutineRepository = application.careRoutineRepository,
                    routineId = routineId
                )
            }
        }
    }
}
