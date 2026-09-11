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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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

/**
 * Supplies the routine detail screen with a routine and the tasks inside it.
 *
 * Read-only, like [com.prash.petcare.ui.pet.PetDetailViewModel]: adding a task is handled by the
 * form the user moves on to.
 *
 * @param careRoutineRepository Source of the routine itself.
 * @param careTaskRepository Source of the tasks belonging to it.
 * @param routineId Which routine to show, passed in as a navigation argument.
 */
class RoutineDetailViewModel(
    careRoutineRepository: CareRoutineRepository,
    careTaskRepository: CareTaskRepository,
    routineId: Long
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
