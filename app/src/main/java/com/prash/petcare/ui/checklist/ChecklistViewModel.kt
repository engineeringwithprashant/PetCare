package com.prash.petcare.ui.checklist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.TaskCategory
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.util.CareSchedule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
 * @property outstandingItems The same structure but covering only tasks still to be done. Held
 *   separately because delegation sends this rather than [items]: the point of handing a checklist
 *   to someone is telling them what is left, so anything already done is left out — along with any
 *   category heading whose tasks are all finished, which would otherwise appear empty.
 * @property doneCount How many of today's tasks are ticked off.
 * @property totalCount How many are due today in total.
 * @property isLoading True until the first database results arrive.
 */
data class ChecklistUiState(
    val petName: String = "",
    val items: List<ChecklistItem> = emptyList(),
    val outstandingItems: List<ChecklistItem> = emptyList(),
    val doneCount: Int = 0,
    val totalCount: Int = 0,
    val isLoading: Boolean = true
) {
    /** True when the "nothing due today" message should replace the list. */
    val hasNothingDue: Boolean get() = !isLoading && totalCount == 0

    /** How many of today's tasks are still to be done. */
    val outstandingCount: Int get() = totalCount - doneCount

    /**
     * True when there is care due today and all of it is finished.
     *
     * Deliberately distinct from [hasNothingDue]: "nothing was scheduled" and "everything has been
     * done" both leave nothing to delegate, but they are different things to tell the user.
     */
    val isAllDone: Boolean get() = !isLoading && totalCount > 0 && outstandingCount == 0
}

/** The person a checklist is being delegated to. */
data class DelegationRecipient(val name: String, val phoneNumber: String)

/**
 * How far through the delegation flow the user is.
 *
 * This is held as state rather than left to the fragment because the flow spans several trips out
 * of the app — the contact picker, then possibly a permission prompt — and each of those can
 * destroy and recreate the screen. Keeping the stage here means a rotation mid-flow resumes where
 * it left off instead of silently starting over.
 *
 * Distinguishing [Preview] from [Sending] is what stops the preview dialog reappearing after the
 * user has already tapped Send and moved on to the permission prompt.
 */
sealed interface DelegationStage {

    /** Nothing in progress. */
    data object Idle : DelegationStage

    /** A recipient has been chosen; the message is waiting to be confirmed. */
    data class Preview(val recipient: DelegationRecipient) : DelegationStage

    /** The user confirmed; the message is being sent. */
    data class Sending(val recipient: DelegationRecipient) : DelegationStage
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
 * **Which pet, and the two ways this screen is reached.** The checklist is one of the four
 * bottom-navigation tabs *and* the screen a pet's detail page opens — see the note at the top of
 * nav_graph.xml on why one destination serves both. The difference is entirely in where the pet
 * comes from:
 *
 *  - From a pet's detail page, [initialPetId] is that animal and the pet never changes. [pets] is
 *    empty, so the fragment shows no picker.
 *  - From the bottom bar, [initialPetId] is [NO_PET_ID]. [pets] then carries the owner's animals
 *    for the picker chips, and the first of them is selected automatically — landing on a tab that
 *    asked the user to choose before showing anything would be a needless step, since one of them
 *    has to be shown first anyway.
 *
 * @param petRepository Source of the pet's name, and of the pet list for the picker.
 * @param careRoutineRepository Source of the routines that decide what runs today.
 * @param careTaskRepository Source of the tasks, and where completion is written back.
 * @param ownerId Whose pets the picker offers. Ignored when a specific pet was passed in.
 * @param initialPetId The pet to show, or [NO_PET_ID] to offer the picker instead.
 */
class ChecklistViewModel(
    private val petRepository: PetRepository,
    private val careRoutineRepository: CareRoutineRepository,
    private val careTaskRepository: CareTaskRepository,
    ownerId: Long,
    initialPetId: Long
) : ViewModel() {

    /**
     * True when this screen was opened as a tab rather than for one particular animal, and should
     * therefore let the user choose between pets.
     *
     * Fixed for the life of the ViewModel: which of the two jobs this instance is doing is decided
     * by how it was navigated to and cannot change afterwards.
     */
    val showsPetPicker: Boolean = initialPetId == NO_PET_ID

    /**
     * The pets the picker offers, or empty when this screen is showing one particular animal.
     *
     * Queried only in picker mode. Subscribing to the owner's pets on a screen that will never show
     * a picker would keep a query alive to populate a row that is not on screen.
     */
    val pets: StateFlow<List<Pet>> =
        if (showsPetPicker) {
            petRepository.observePetsForOwner(ownerId).stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )
        } else {
            MutableStateFlow(emptyList())
        }

    /** Which pet the checklist is currently showing. */
    private val _selectedPetId = MutableStateFlow(initialPetId)
    val selectedPetId: StateFlow<Long> = _selectedPetId.asStateFlow()

    /**
     * Keeps the selection pointing at a pet that actually exists.
     *
     * This covers two moments with one rule. On arrival the selection is [NO_PET_ID] and the pet
     * list has not loaded yet, so the first pet to arrive is chosen. Later, if the selected pet is
     * deleted — from the Pets tab, or by a sync pulling down its deletion from another device — it
     * vanishes from the list and the same rule moves the screen to whichever pet is now first,
     * rather than leaving it showing the checklist of an animal that no longer exists.
     *
     * Only runs in picker mode: with a pet passed in explicitly there is nothing to choose, and
     * reassigning the selection would quietly show a different animal than the one asked for.
     */
    init {
        if (showsPetPicker) {
            viewModelScope.launch {
                pets.collect { available ->
                    if (available.none { it.id == _selectedPetId.value }) {
                        _selectedPetId.value = available.firstOrNull()?.id ?: NO_PET_ID
                    }
                }
            }
        }
    }

    /** Records the pet the user tapped in the picker. */
    fun selectPet(petId: Long) {
        _selectedPetId.value = petId
    }

    /**
     * Today's checklist for whichever pet is selected.
     *
     * [flatMapLatest] is what lets the pet change at runtime: each new selection replaces the
     * previous pet's three queries with the new pet's. "Latest" is the important half of the name —
     * the queries opened for the pet being left are cancelled, so switching between animals
     * repeatedly does not leave a subscription behind for each one visited.
     *
     * With nothing selected — the moment before the pet list arrives, or a user with no pets at all
     * — it emits an empty, finished state rather than no state at all. A flow that never emits
     * would leave the screen on its loading state forever, and the fragment could not tell that
     * apart from a slow query.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ChecklistUiState> = _selectedPetId.flatMapLatest { petId ->
        if (petId == NO_PET_ID) {
            flowOf(ChecklistUiState(isLoading = false))
        } else {
            checklistFor(petId)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChecklistUiState()
    )

    /** Builds the checklist state for one pet. */
    private fun checklistFor(petId: Long): Flow<ChecklistUiState> = combine(
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
            // Grouped again from the outstanding tasks alone, rather than filtering the finished
            // list afterwards — that would leave behind headings for categories whose tasks are
            // all done.
            outstandingItems = buildGroupedItems(rows.filterNot { it.isDoneToday }),
            doneCount = rows.count { it.isDoneToday },
            totalCount = rows.size,
            isLoading = false
        )
    }

    /**
     * Clears every task ticked off today, so the checklist starts over.
     *
     * The tasks themselves are untouched — only their completion is cleared, and only for today.
     * Anything completed on an earlier day keeps its record.
     */
    fun resetTodaysChecklist() {
        // The pet currently selected, which on the Tasks tab is whichever chip is chosen. Read at
        // the moment of the reset rather than captured when the screen opened, so a shake always
        // clears the checklist actually being looked at.
        val petId = _selectedPetId.value
        if (petId == NO_PET_ID) return

        viewModelScope.launch {
            careTaskRepository.clearCompletionsForPet(
                petId = petId,
                fromMillis = CareSchedule.startOfToday(),
                toMillis = CareSchedule.endOfToday()
            )
        }
    }

    // Delegation runs alongside the checklist rather than being folded into its state, because it
    // is driven by the user rather than derived from the database — combining it into the query
    // above would mean rebuilding the whole list every time the flow moved a step.
    private val _delegationStage = MutableStateFlow<DelegationStage>(DelegationStage.Idle)
    val delegationStage: StateFlow<DelegationStage> = _delegationStage.asStateFlow()

    /** Records the contact the user picked, moving the flow on to the confirmation preview. */
    fun onRecipientChosen(name: String, phoneNumber: String) {
        _delegationStage.value = DelegationStage.Preview(
            DelegationRecipient(name = name, phoneNumber = phoneNumber)
        )
    }

    /**
     * Records that the user confirmed the message.
     *
     * Moving out of [DelegationStage.Preview] immediately is deliberate: sending may still need an
     * SMS permission prompt, and while that is on screen the checklist can be recreated. Were the
     * stage still Preview, the confirmation dialog would pop up again behind the prompt.
     */
    fun onSendConfirmed() {
        val recipient = (_delegationStage.value as? DelegationStage.Preview)?.recipient ?: return
        _delegationStage.value = DelegationStage.Sending(recipient)
    }

    /** Returns to idle, whether the message was sent, cancelled or failed. */
    fun onDelegationFinished() {
        _delegationStage.value = DelegationStage.Idle
    }

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

        /**
         * The pet id meaning "none chosen", matching the app's convention for absent ids and the
         * default declared on this destination in nav_graph.xml. Its presence is what puts the
         * screen into picker mode.
         */
        const val NO_PET_ID = -1L

        /**
         * Builds the ViewModel.
         *
         * A function rather than a property because neither id is known until runtime: the owner
         * comes from the session and the pet from the navigation argument, and both have to be
         * captured here to reach the constructor.
         *
         * @param ownerId Whose pets the picker offers.
         * @param petId The pet to show, or [NO_PET_ID] to offer the picker instead.
         */
        fun factory(ownerId: Long, petId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                ChecklistViewModel(
                    petRepository = application.petRepository,
                    careRoutineRepository = application.careRoutineRepository,
                    careTaskRepository = application.careTaskRepository,
                    ownerId = ownerId,
                    initialPetId = petId
                )
            }
        }
    }
}
