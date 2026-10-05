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

/** Everything the routine detail screen displays. */
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

/** Supplies the routine detail screen with a routine and the tasks inside it, and handles pausing and deleting it. */
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

    /** Pauses or resumes the routine. */
    fun setActive(isActive: Boolean) {
        viewModelScope.launch {
            careRoutineRepository.setRoutineActive(routineId, isActive)
        }
    }

    /** Deletes a single task from this routine, as the swipe-left gesture does. */
    fun deleteTask(task: CareTask) {
        viewModelScope.launch {
            careTaskRepository.deleteTask(task)
        }
    }

    /** Puts back a task removed by [deleteTask], for the undo action. */
    fun restoreTask(task: CareTask) {
        viewModelScope.launch {
            careTaskRepository.addTask(task)
        }
    }

    /** Deletes the routine and every task inside it. */
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
