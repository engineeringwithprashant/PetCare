package com.prash.petcare.ui.routine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Everything the routine detail screen displays.
 *
 * @property routine The routine being viewed, or null while loading.
 * @property tasks The care tasks it contains, ordered through the day.
 * @property isLoading True until the first database results arrive.
 */
data class RoutineDetailUiState(
    val routine: CareRoutine? = null,
    val tasks: List<CareTask> = emptyList(),
    val isLoading: Boolean = true
) {
    /** True when the "no tasks yet" message should replace the list. */
    val hasNoTasks: Boolean get() = !isLoading && tasks.isEmpty()
}

/** A one-off occurrence the routine detail screen reacts to exactly once. */
sealed interface RoutineDetailEvent {
    /** The routine was deleted; the screen should close, since there is nothing left to show. */
    data object Deleted : RoutineDetailEvent
}

/**
 * Supplies the routine detail screen with a routine and the tasks inside it, and handles pausing
 * and deleting it.
 *
 * Adding a task is still handled by the form the user moves on to; what lives here are the two
 * actions that operate on the routine as a whole.
 *
 * @param careRoutineRepository Source of the routine, and where pause and delete are written.
 * @param careTaskRepository Source of the tasks belonging to it, and used to clear them on delete.
 * @param routineId Which routine to show, passed in as a navigation argument.
 */
class RoutineDetailViewModel(
    private val careRoutineRepository: CareRoutineRepository,
    private val careTaskRepository: CareTaskRepository,
    private val routineId: Long
) : ViewModel() {

    val uiState: StateFlow<RoutineDetailUiState> = combine(
        careRoutineRepository.observeRoutine(routineId),
        careTaskRepository.observeTasksForRoutine(routineId)
    ) { routine, tasks ->
        RoutineDetailUiState(
            routine = routine,
            tasks = tasks,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RoutineDetailUiState()
    )

    private val _events = Channel<RoutineDetailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * Pauses or resumes the routine.
     *
     * Pausing is the reversible alternative to deleting: the routine and its tasks stay exactly as
     * they are, but [com.prash.petcare.util.CareSchedule.occursOn] stops treating it as due, so
     * none of its tasks appear on any checklist until it is switched back on. That suits care that
     * stops for a while — a course of medication that has finished, walks paused after surgery.
     *
     * A targeted UPDATE through the repository rather than rewriting the whole row, so a concurrent
     * edit elsewhere cannot be undone by this toggle.
     */
    fun setActive(isActive: Boolean) {
        viewModelScope.launch {
            careRoutineRepository.setRoutineActive(routineId, isActive)
        }
    }

    /**
     * Deletes the routine and every task inside it.
     *
     * The tasks are removed first, deliberately. The foreign key on `routineId` is SET_NULL, so
     * deleting the routine on its own would leave its tasks behind pointing at nothing — and a task
     * with no routine has no days on which it is due, making it permanently invisible rather than
     * preserved.
     *
     * The order also decides what a partial failure looks like. Clearing tasks first means an
     * interruption leaves an empty routine the user can simply delete again; the other order would
     * leave orphaned tasks with no route back to them.
     *
     * The confirmation dialog is the fragment's responsibility — by the time this runs the user has
     * already agreed.
     */
    fun delete() {
        val routine = uiState.value.routine ?: return

        viewModelScope.launch {
            careTaskRepository.deleteTasksForRoutine(routineId)
            careRoutineRepository.deleteRoutine(routine)

            _events.send(RoutineDetailEvent.Deleted)
        }
    }

    companion object {
        fun factory(routineId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                RoutineDetailViewModel(
                    careRoutineRepository = application.careRoutineRepository,
                    careTaskRepository = application.careTaskRepository,
                    routineId = routineId
                )
            }
        }
    }
}
