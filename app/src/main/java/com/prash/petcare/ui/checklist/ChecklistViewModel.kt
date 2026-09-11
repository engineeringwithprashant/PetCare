package com.prash.petcare.ui.checklist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.TaskCategory
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.util.CareSchedule
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One row of the checklist.
 *
 * The list mixes two kinds of row — category headings and tasks — so it is modelled as a sealed
 * interface. That gives the adapter a single list to render while letting the compiler guarantee
 * every kind is handled, which a list of loosely typed objects could not.
 */
sealed interface ChecklistItem {

    /**
     * A stable identity for this row.
     *
     * DiffUtil uses it to tell "the same row, changed" from "a different row". Headers and tasks
     * are prefixed differently so a category can never collide with a task that happens to share
     * its number.
     */
    val itemId: String

    /** A heading introducing the tasks of one category. */
    data class CategoryHeader(val category: TaskCategory) : ChecklistItem {
        override val itemId: String get() = "header_${category.name}"
    }

    /**
     * A tickable task.
     *
     * @property isDoneToday Whether it has been completed *today* specifically, worked out from
     *   the task's completion timestamp rather than read from a flag — see [CareSchedule.isDoneOn].
     */
    data class TaskRow(val task: CareTask, val isDoneToday: Boolean) : ChecklistItem {
        override val itemId: String get() = "task_${task.id}"
    }
}

/**
 * Everything the checklist screen displays.
 *
 * @property petName Whose checklist this is, shown in the heading.
 * @property items Headers and task rows already interleaved in display order.
 * @property doneCount How many of today's tasks are ticked off.
 * @property totalCount How many are due today in total.
 * @property isLoading True until the first database results arrive.
 */
data class ChecklistUiState(
    val petName: String = "",
    val items: List<ChecklistItem> = emptyList(),
    val doneCount: Int = 0,
    val totalCount: Int = 0,
    val isLoading: Boolean = true
) {
    /** True when the "nothing due today" message should replace the list. */
    val hasNothingDue: Boolean get() = !isLoading && totalCount == 0
}

/**
 * Supplies today's checklist for one pet, and records tasks being ticked off.
 *
 * **Where "today's tasks" come from.** Tasks are stored as repeating templates, so there is no
 * table of today's items to read. Instead this combines the pet's active routines with all of its
 * tasks, keeps the tasks whose routine actually runs today, and groups what is left by category.
 * Doing the work here rather than in SQL keeps the day-and-schedule arithmetic in Kotlin, where it
 * can respect the device's time zone and be unit-tested.
 *
 * @param petRepository Source of the pet's name.
 * @param careRoutineRepository Source of the routines that decide what runs today.
 * @param careTaskRepository Source of the tasks, and where completion is written back.
 * @param petId Whose checklist to show.
 */
class ChecklistViewModel(
    petRepository: PetRepository,
    careRoutineRepository: CareRoutineRepository,
    private val careTaskRepository: CareTaskRepository,
    petId: Long
) : ViewModel() {

    val uiState: StateFlow<ChecklistUiState> = combine(
        petRepository.observePet(petId),
        careRoutineRepository.observeActiveRoutinesForPet(petId),
        careTaskRepository.observeTasksForPet(petId)
    ) { pet, activeRoutines, allTasks ->

        // Recomputed on every emission rather than captured once, so the boundaries stay correct
        // if the screen is still open after midnight.
        val todayStart = CareSchedule.startOfToday()
        val todayEnd = CareSchedule.endOfToday()

        // Which routines actually run today — a weekly routine only counts on its own weekday.
        val routineIdsDueToday = activeRoutines
            .filter { routine -> CareSchedule.occursOn(routine, todayStart) }
            .map { it.id }
            .toSet()

        val rows = allTasks
            .filter { task -> task.routineId?.let { it in routineIdsDueToday } == true }
            .map { task ->
                ChecklistItem.TaskRow(
                    task = task,
                    isDoneToday = CareSchedule.isDoneOn(task, todayStart, todayEnd)
                )
            }

        ChecklistUiState(
            petName = pet?.name.orEmpty(),
            items = buildGroupedItems(rows),
            doneCount = rows.count { it.isDoneToday },
            totalCount = rows.size,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChecklistUiState()
    )

    /**
     * Interleaves category headers with their tasks, in display order.
     *
     * Categories are walked in the order they appear in [TaskCategory] rather than being sorted
     * alphabetically, so the grouping stays in a fixed, familiar order and does not rearrange
     * itself when the app is translated. Empty categories are skipped entirely, so no heading ever
     * appears with nothing under it.
     */
    private fun buildGroupedItems(rows: List<ChecklistItem.TaskRow>): List<ChecklistItem> =
        buildList {
            TaskCategory.entries.forEach { category ->
                val tasksInCategory = rows
                    .filter { it.task.category == category }
                    // Earliest first. A task with no recorded time sorts last rather than first,
                    // which is what the large fallback value achieves.
                    .sortedWith(
                        compareBy(
                            { it.task.timeOfDayMinutes ?: Int.MAX_VALUE },
                            { it.task.title }
                        )
                    )

                if (tasksInCategory.isNotEmpty()) {
                    add(ChecklistItem.CategoryHeader(category))
                    addAll(tasksInCategory)
                }
            }
        }

    /**
     * Ticks a task off, or clears it again.
     *
     * Only the task id and the new state are passed; the repository decides the timestamp, so every
     * screen records completion the same way. The list then updates itself, because the underlying
     * Room query re-emits as soon as the row changes.
     */
    fun setTaskDone(taskId: Long, isDone: Boolean) {
        viewModelScope.launch {
            careTaskRepository.setTaskCompleted(taskId, isDone)
        }
    }

    companion object {
        fun factory(petId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                ChecklistViewModel(
                    petRepository = application.petRepository,
                    careRoutineRepository = application.careRoutineRepository,
                    careTaskRepository = application.careTaskRepository,
                    petId = petId
                )
            }
        }
    }
}
