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

/** The parts of the care task form the ViewModel owns. */
data class CareTaskFormState(
    val isEditMode: Boolean = false,
    val category: TaskCategory = TaskCategory.FEEDING,
    val timeOfDayMinutes: Int? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    @param:StringRes val titleError: Int? = null,
    @param:StringRes val timeError: Int? = null
)

/** Starting values for a new task, supplied by the screen that opened the form. */
data class CareTaskPrefill(
    val title: String?,
    val notes: String?,
    val category: TaskCategory?
)

/** A one-off occurrence the task form reacts to exactly once. */
sealed interface CareTaskEvent {

    /** Carries a loaded task so the fragment can fill in the text fields. */
    data class PrefillForm(val task: CareTask) : CareTaskEvent

    /** Carries a [CareTaskPrefill]'s text for a new task, for the same once-only reason as [PrefillForm]. */
    data class PrefillDraft(val title: String, val notes: String) : CareTaskEvent

    /** The task was created or updated; the screen should close. */
    data object Saved : CareTaskEvent

    /** The task was deleted; the screen should close. */
    data object Deleted : CareTaskEvent

    /** Something failed that is not tied to a single field. */
    data class Error(@param:StringRes val messageRes: Int) : CareTaskEvent
}

/** Backs the add/edit care task form. */
class CareTaskViewModel(
    private val careTaskRepository: CareTaskRepository,
    private val careRoutineRepository: CareRoutineRepository,
    private val routineId: Long,
    private val taskId: Long,
    private val prefill: CareTaskPrefill? = null
) : ViewModel() {

    /** True when changing an existing record rather than creating one. */
    private val isEditMode: Boolean = taskId != NO_TASK_ID

    /** The owning routine, needed for its pet and its start date. */
    private var routine: CareRoutine? = null

    /** The task as it was loaded, kept so a save can preserve the fields the form does not show — the id, the pet. */
    private var loadedTask: CareTask? = null

    private val _formState = MutableStateFlow(CareTaskFormState(isEditMode = isEditMode))
    val formState: StateFlow<CareTaskFormState> = _formState.asStateFlow()

    private val _events = Channel<CareTaskEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        load()
    }

    /** Fetches the owning routine, and the task itself when editing. */
    private fun load() {
        viewModelScope.launch {
            val loadedRoutine = careRoutineRepository.getRoutine(routineId)

            if (loadedRoutine == null) {
                // The routine was deleted while this screen was opening.
                _formState.value = _formState.value.copy(isLoading = false)
                _events.send(CareTaskEvent.Error(R.string.error_routine_not_found))
                return@launch
            }

            routine = loadedRoutine

            if (!isEditMode) {
                // A new task starts from the defaults — or from what the opening screen suggested.
                _formState.value = _formState.value.copy(
                    isLoading = false,
                    category = prefill?.category ?: _formState.value.category
                )
                if (prefill != null && (prefill.title != null || prefill.notes != null)) {
                    _events.send(
                        CareTaskEvent.PrefillDraft(prefill.title.orEmpty(), prefill.notes.orEmpty())
                    )
                }
                return@launch
            }

            val task = careTaskRepository.getTask(taskId)

            if (task == null) {
                _formState.value = _formState.value.copy(isLoading = false)
                _events.send(CareTaskEvent.Error(R.string.error_task_not_found))
                return@launch
            }

            loadedTask = task

            // Picker-chosen values become state.
            _formState.value = _formState.value.copy(
                isLoading = false,
                category = task.category,
                timeOfDayMinutes = task.timeOfDayMinutes
            )

            // The typed fields are filled once, via an event.
            _events.send(CareTaskEvent.PrefillForm(task))
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

    /** Validates the form and writes the task. */
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

            // The anchor date: the routine's own start day, at this task's time of day.
            val dueAt = CareSchedule.startOfDay(owningRoutine.startDate) +
                minutes * MILLIS_PER_MINUTE

            val existing = loadedTask

            // `copy` on the loaded task keeps id, petId and createdAt untouched.
            val task = existing?.copy(
                title = trimmedTitle,
                notes = notes.trimToNull(),
                requiredSupplies = requiredSupplies.trimToNull(),
                category = state.category,
                timeOfDayMinutes = minutes,
                dueAt = dueAt
            ) ?: CareTask(
                // Taken from the routine so the task, its routine and its pet always agree.
                petId = owningRoutine.petId,
                routineId = owningRoutine.id,
                title = trimmedTitle,
                notes = notes.trimToNull(),
                requiredSupplies = requiredSupplies.trimToNull(),
                category = state.category,
                timeOfDayMinutes = minutes,
                dueAt = dueAt
            )

            if (existing == null) {
                careTaskRepository.addTask(task)
            } else {
                careTaskRepository.updateTask(task)
            }

            _events.send(CareTaskEvent.Saved)
        }
    }

    /** Deletes the task being edited. */
    fun delete() {
        val task = loadedTask ?: return

        viewModelScope.launch {
            _formState.value = _formState.value.copy(isSaving = true)

            careTaskRepository.deleteTask(task)

            _events.send(CareTaskEvent.Deleted)
        }
    }

    /** Blank input and "not provided" mean the same thing, so both are stored as null. */
    private fun String.trimToNull(): String? = trim().takeIf { it.isNotEmpty() }

    companion object {
        private const val MINUTES_PER_HOUR = 60
        private const val MILLIS_PER_MINUTE = 60_000L

        /** [taskId] value meaning "create a new task". Matches the default in the nav graph. */
        const val NO_TASK_ID = -1L

        fun factory(
            routineId: Long,
            taskId: Long,
            prefill: CareTaskPrefill? = null
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                CareTaskViewModel(
                    careTaskRepository = application.careTaskRepository,
                    careRoutineRepository = application.careRoutineRepository,
                    routineId = routineId,
                    taskId = taskId,
                    prefill = prefill
                )
            }
        }
    }
}
