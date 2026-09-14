package com.prash.petcare.ui.checklist

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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The part of the day a task falls in, which is how the Tasks tab groups its list.
 *
 * Grouping by time rather than by category (as this screen used to) follows the way a day of care is
 * actually lived — the question at 8am is "what's this morning?", not "what's all the feeding?" —
 * and the category filter chips now cover the other question.
 *
 * The boundaries are fixed here rather than configurable: noon and 5pm are where "afternoon" and
 * "evening" start in ordinary speech, and a setting for it would be a choice nobody wants to make.
 *
 * @property labelRes The section header's text.
 */
enum class TimeOfDay(@param:StringRes val labelRes: Int) {
    MORNING(R.string.tasks_section_morning),
    AFTERNOON(R.string.tasks_section_afternoon),
    EVENING(R.string.tasks_section_evening),

    /** A task with no recorded time. The form always sets one, but the column allows null. */
    ANYTIME(R.string.tasks_section_anytime);

    companion object {
        private const val NOON = 12 * 60
        private const val FIVE_PM = 17 * 60

        /** Which section a task stored at [minutesSinceMidnight] belongs to. */
        fun of(minutesSinceMidnight: Int?): TimeOfDay = when {
            minutesSinceMidnight == null -> ANYTIME
            minutesSinceMidnight < NOON -> MORNING
            minutesSinceMidnight < FIVE_PM -> AFTERNOON
            else -> EVENING
        }
    }
}

/**
 * One row of the task list.
 *
 * The list mixes two kinds of row — section headers and task cards — so it is modelled as a sealed
 * interface. That gives the adapter a single list to render while the compiler guarantees every kind
 * is handled.
 */
sealed interface ChecklistItem {

    /**
     * A stable identity for this row, used by DiffUtil to tell "the same row, changed" from "a
     * different row". Headers and tasks are prefixed differently so they can never collide.
     */
    val itemId: String

    /**
     * "MORNING  2/3" — a section header with its own progress.
     *
     * @property doneCount How many tasks in this section are ticked off.
     * @property totalCount How many tasks the section holds.
     */
    data class SectionHeader(
        val timeOfDay: TimeOfDay,
        val doneCount: Int,
        val totalCount: Int
    ) : ChecklistItem {
        override val itemId: String get() = "section_${timeOfDay.name}"
    }

    /**
     * One task card.
     *
     * @property petName Whose task it is — shown on the card, since "All pets" mixes animals.
     * @property isDone Whether it was completed on the day being shown, worked out from its completion
     *   timestamp rather than a flag — see [CareSchedule.isDoneOn].
     */
    data class TaskRow(
        val task: CareTask,
        val petName: String,
        val isDone: Boolean
    ) : ChecklistItem {
        override val itemId: String get() = "task_${task.id}"
    }
}

/**
 * One cell of the week strip.
 *
 * @property dayStart Local midnight of this day — the value passed back when it is tapped.
 * @property hasTasks Whether any care (after the pet and category filters) is scheduled, which is
 *   what the dot under the date shows.
 */
data class WeekDay(
    val dayStart: Long,
    val isToday: Boolean,
    val isSelected: Boolean,
    val hasTasks: Boolean
)

/**
 * A routine a new task could be added to, for the "+ Add task" chooser.
 *
 * @property petName Shown beside the routine's title, since two pets can each have a "Morning" one.
 */
data class RoutineOption(val routine: CareRoutine, val petName: String)

/**
 * Everything the Tasks screen displays.
 *
 * @property weekDays The seven cells of the week strip, in order.
 * @property selectedDay Local midnight of the day the list shows.
 * @property isSelectedDayToday Whether that day is today. Ticking off, swiping right and resetting
 *   are only offered on today — see [ChecklistViewModel.setTaskDone] for why.
 * @property pets The pets offered as filter chips; empty when the screen shows one fixed pet.
 * @property selectedPetId The chosen pet chip, or [ChecklistViewModel.ALL_PETS].
 * @property selectedCategory The chosen category chip, or null for "All".
 * @property items Section headers and task rows, already interleaved in display order.
 * @property outstandingItems The same structure covering only tasks not yet done. Delegation sends
 *   this: handing a checklist over means telling someone what is *left*.
 * @property segments One entry per task in display order, true when done — the progress bar.
 * @property outstandingPetNames Distinct names of pets with outstanding tasks, for the delegation
 *   message's subject and header.
 * @property donePetNames Distinct names of pets with ticked-off tasks, for the reset confirmation.
 * @property routineOptions The active routines of the pets in scope, for "+ Add task".
 * @property isSinglePet True when every task shown belongs to one pet, so the delegation message can
 *   leave the pet's name off each line.
 * @property singlePetName The pet's name when the screen was opened for one pet, for the toolbar
 *   title; empty on the tab.
 * @property hasNoPets True when the account has no pets at all — a different empty state from a
 *   quiet day.
 * @property isLoading True until the first database results arrive.
 */
data class ChecklistUiState(
    val weekDays: List<WeekDay> = emptyList(),
    val selectedDay: Long = 0L,
    val isSelectedDayToday: Boolean = true,
    val pets: List<Pet> = emptyList(),
    val selectedPetId: Long = ChecklistViewModel.ALL_PETS,
    val selectedCategory: TaskCategory? = null,
    val items: List<ChecklistItem> = emptyList(),
    val outstandingItems: List<ChecklistItem> = emptyList(),
    val segments: List<Boolean> = emptyList(),
    val outstandingPetNames: List<String> = emptyList(),
    val donePetNames: List<String> = emptyList(),
    val routineOptions: List<RoutineOption> = emptyList(),
    val isSinglePet: Boolean = true,
    val singlePetName: String = "",
    val hasNoPets: Boolean = false,
    val isLoading: Boolean = true
) {
    /** How many of the shown tasks are done. */
    val doneCount: Int get() = segments.count { it }

    /** How many tasks are shown. */
    val totalCount: Int get() = segments.size

    /** How many of the shown tasks are still to do. */
    val outstandingCount: Int get() = totalCount - doneCount

    /** True when the "nothing scheduled" message should replace the list. */
    val hasNothingDue: Boolean get() = !isLoading && !hasNoPets && totalCount == 0

    /**
     * True when there is care shown and all of it is finished.
     *
     * Distinct from [hasNothingDue]: "nothing was scheduled" and "everything is done" both leave
     * nothing to delegate, but they are different things to tell the user.
     */
    val isAllDone: Boolean get() = !isLoading && totalCount > 0 && outstandingCount == 0
}

/** The person a checklist is being delegated to. */
data class DelegationRecipient(val name: String, val phoneNumber: String)

/**
 * How far through the SMS delegation flow the user is.
 *
 * Held as state rather than left to the fragment because the flow spans several trips out of the
 * app — the contact picker, then possibly a permission prompt — and each can destroy and recreate
 * the screen. Keeping the stage here means a rotation mid-flow resumes where it left off.
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
 * Supplies the Tasks screen: the care scheduled on a chosen day, filtered by pet and category and
 * grouped by time of day — and records ticks, deletions, resets and delegation.
 *
 * Sits in the middle of the MVVM chain: it reads from three repositories and publishes one
 * [ChecklistUiState] for [ChecklistFragment] to render. It holds no views and no Context, so the
 * selected day and filters survive rotation. It uses only repository methods that already existed;
 * everything new about this screen is a different way of *looking* at the same rows.
 *
 * **Where a day's tasks come from.** Tasks are stored as repeating templates, so there is no table of
 * one day's items to read. For each pet this takes its active routines and its tasks, and keeps the
 * tasks whose routine runs on the chosen day — [CareSchedule.tasksDueOn], the same rule the Home and
 * Pets tabs use, so all three always agree.
 *
 * **Why the queries and the filters are separate streams.** The database is queried per pet in
 * scope, and the day, pet and category the user has chosen are held in [MutableStateFlow]s beside
 * it. The two are [combine]d, so tapping a chip or a day recomputes the list in memory from rows
 * already loaded rather than reopening any query — the screen responds instantly, and Room is only
 * asked again when the data itself changes.
 *
 * **The two ways this screen is reached.** It is the Tasks tab *and* the checklist a pet's detail
 * page opens (see the note at the top of nav_graph.xml):
 *  - As the tab, [initialPetId] is [NO_PET_ID]: every pet the owner has is in scope, the pet chips
 *    are offered, and "All pets" is selected to begin with.
 *  - For one pet, only that pet is in scope and the pet filter is fixed to it.
 *
 * @param petRepository Source of the pets in scope.
 * @param careRoutineRepository Source of the routines that decide what runs on a day.
 * @param careTaskRepository Source of the tasks, and where ticks and deletions are written.
 * @param ownerId Whose pets the tab shows. Ignored when a specific pet was passed in.
 * @param initialPetId The pet to show, or [NO_PET_ID] for the tab with all of the owner's pets.
 */
class ChecklistViewModel(
    petRepository: PetRepository,
    careRoutineRepository: CareRoutineRepository,
    private val careTaskRepository: CareTaskRepository,
    ownerId: Long,
    initialPetId: Long
) : ViewModel() {

    /**
     * True when this screen is the Tasks tab rather than one pet's checklist, and should offer the
     * pet chips. Fixed for the ViewModel's life — it is decided by how the screen was navigated to.
     */
    val showsPetPicker: Boolean = initialPetId == NO_PET_ID

    // ------------------------------------------------------------------------------------------
    // What the user has chosen
    // ------------------------------------------------------------------------------------------

    /**
     * The chosen day, or null meaning "whatever today is".
     *
     * Null rather than today's midnight captured at creation, so a screen left open past midnight
     * moves on to the new day by itself instead of quietly showing yesterday as if it were today.
     */
    private val selectedDay = MutableStateFlow<Long?>(null)

    /** The chosen pet chip. In single-pet mode this is that pet and never changes. */
    private val selectedPetId = MutableStateFlow(if (showsPetPicker) ALL_PETS else initialPetId)

    /** The chosen category chip, or null for "All". */
    private val selectedCategory = MutableStateFlow<TaskCategory?>(null)

    /** Shows the tasks for the day beginning at [dayStart]. */
    fun selectDay(dayStart: Long) {
        // Choosing today stores null, so the selection keeps following "today" across midnight.
        selectedDay.value = dayStart.takeIf { it != CareSchedule.startOfToday() }
    }

    /** Filters to one pet, or to every pet with [ALL_PETS]. Ignored for a fixed-pet checklist. */
    fun selectPet(petId: Long) {
        if (showsPetPicker) selectedPetId.value = petId
    }

    /** Filters to one category, or to all of them with null. */
    fun selectCategory(category: TaskCategory?) {
        selectedCategory.value = category
    }

    // ------------------------------------------------------------------------------------------
    // The data
    // ------------------------------------------------------------------------------------------

    /** One pet with the routines and tasks needed to work out any of its days. */
    private data class PetCare(
        val pet: Pet,
        val activeRoutines: List<CareRoutine>,
        val tasks: List<CareTask>
    )

    /** The pets this screen covers: all of the owner's on the tab, or the one it was opened for. */
    private val petsInScope: Flow<List<Pet>> =
        if (showsPetPicker) {
            petRepository.observePetsForOwner(ownerId)
        } else {
            petRepository.observePet(initialPetId).map { listOfNotNull(it) }
        }

    /**
     * Each pet in scope with its active routines and tasks.
     *
     * [flatMapLatest] rebuilds the per-pet queries whenever the pet list changes, cancelling the
     * previous set — so a deleted pet's queries stop rather than accumulating. The inner [combine]
     * over a list of flows emits one complete list, so the screen never sees pets arriving one at a
     * time. The empty case is short-circuited because `combine` over an empty list never emits, which
     * would leave a user with no pets on the loading state for ever.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val petCare: Flow<List<PetCare>> = petsInScope.flatMapLatest { pets ->
        if (pets.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                pets.map { pet ->
                    combine(
                        careRoutineRepository.observeActiveRoutinesForPet(pet.id),
                        careTaskRepository.observeTasksForPet(pet.id)
                    ) { routines, tasks -> PetCare(pet, routines, tasks) }
                }
            ) { perPet -> perPet.toList() }
        }
    }

    /** The three selections as one value, so they can be combined with the data in one step. */
    private data class Filters(val day: Long?, val petId: Long, val category: TaskCategory?)

    private val filters: Flow<Filters> =
        combine(selectedDay, selectedPetId, selectedCategory) { day, petId, category ->
            Filters(day, petId, category)
        }

    /**
     * The full state of the screen, recomputed whenever the data or any selection changes.
     *
     * [stateIn] turns the result into a hot [StateFlow] that remembers the latest value, so a
     * rotation re-attaches to data that is already there. `WhileSubscribed(5_000)` keeps the queries
     * alive for five seconds after the screen stops observing — long enough to survive a rotation,
     * short enough that a backgrounded screen stops doing work.
     */
    val uiState: StateFlow<ChecklistUiState> = combine(petCare, filters) { care, chosen ->
        buildState(care, chosen)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChecklistUiState()
    )

    /**
     * Works out everything the screen shows from the loaded rows and the current selections.
     *
     * A plain function of its inputs, called from inside the flow above, so all the arithmetic for
     * one frame happens against one consistent snapshot.
     */
    private fun buildState(care: List<PetCare>, chosen: Filters): ChecklistUiState {
        // "Today" is recomputed on every emission, so the boundaries stay correct past midnight.
        val today = CareSchedule.startOfToday()
        val day = chosen.day ?: today

        // A pet chip whose pet has since been deleted falls back to "All pets" rather than showing
        // an empty list for an animal that no longer exists.
        val petId = if (care.any { it.pet.id == chosen.petId }) chosen.petId else ALL_PETS
        val inScope = if (petId == ALL_PETS) care else care.filter { it.pet.id == petId }

        val rows = rowsOn(inScope, day, chosen.category)

        val weekDays = CareSchedule.weekContaining(today).map { weekDay ->
            WeekDay(
                dayStart = weekDay,
                isToday = weekDay == today,
                isSelected = weekDay == day,
                // Same filters as the list, so a dot always means "tapping here shows something".
                hasTasks = if (weekDay == day) rows.isNotEmpty()
                else rowsOn(inScope, weekDay, chosen.category).isNotEmpty()
            )
        }

        return ChecklistUiState(
            weekDays = weekDays,
            selectedDay = day,
            isSelectedDayToday = day == today,
            pets = if (showsPetPicker) care.map { it.pet } else emptyList(),
            selectedPetId = petId,
            selectedCategory = chosen.category,
            items = groupByTimeOfDay(rows),
            // Grouped again from the outstanding rows alone, rather than filtering the grouped list,
            // which would leave headers behind for sections whose tasks are all done.
            outstandingItems = groupByTimeOfDay(rows.filterNot { it.isDone }),
            segments = rows.map { it.isDone },
            outstandingPetNames = rows.filterNot { it.isDone }.map { it.petName }.distinct(),
            donePetNames = rows.filter { it.isDone }.map { it.petName }.distinct(),
            routineOptions = inScope.flatMap { pc ->
                pc.activeRoutines.map { RoutineOption(it, pc.pet.name) }
            },
            isSinglePet = inScope.size <= 1,
            singlePetName = if (showsPetPicker) "" else care.firstOrNull()?.pet?.name.orEmpty(),
            hasNoPets = care.isEmpty(),
            isLoading = false
        )
    }

    /**
     * The task rows for [pets] on the day beginning at [dayStart], earliest first.
     *
     * Ordered by time of day, then pet, then title, so the grouped list reads as the day itself
     * rather than one animal after another. A task with no time sorts last, which the large fallback
     * value achieves.
     */
    private fun rowsOn(
        pets: List<PetCare>,
        dayStart: Long,
        category: TaskCategory?
    ): List<ChecklistItem.TaskRow> {
        val dayEnd = CareSchedule.endOfDay(dayStart)

        return pets
            .flatMap { pc ->
                CareSchedule.tasksDueOn(pc.activeRoutines, pc.tasks, dayStart)
                    .filter { category == null || it.category == category }
                    .map { task ->
                        ChecklistItem.TaskRow(
                            task = task,
                            petName = pc.pet.name,
                            isDone = CareSchedule.isDoneOn(task, dayStart, dayEnd)
                        )
                    }
            }
            .sortedWith(
                compareBy(
                    { it.task.timeOfDayMinutes ?: Int.MAX_VALUE },
                    { it.petName },
                    { it.task.title }
                )
            )
    }

    /**
     * Interleaves time-of-day headers with their tasks.
     *
     * Walks [TimeOfDay] in declaration order so the sections always run morning to evening, and skips
     * a section with no tasks so no header ever appears with nothing under it. The rows arrive already
     * sorted by time, so each section's tasks keep that order.
     */
    private fun groupByTimeOfDay(rows: List<ChecklistItem.TaskRow>): List<ChecklistItem> =
        buildList {
            TimeOfDay.entries.forEach { section ->
                val inSection = rows.filter { TimeOfDay.of(it.task.timeOfDayMinutes) == section }

                if (inSection.isNotEmpty()) {
                    add(
                        ChecklistItem.SectionHeader(
                            timeOfDay = section,
                            doneCount = inSection.count { it.isDone },
                            totalCount = inSection.size
                        )
                    )
                    addAll(inSection)
                }
            }
        }

    // ------------------------------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------------------------------

    /**
     * Ticks a task off, or clears it again — only while today is the day being shown.
     *
     * **Why only today.** A repeating task keeps a single "last completed at" timestamp, and the
     * repository stamps it with the current time. Ticking a task while looking at Friday would
     * therefore record it as done *now*, marking today's instead. Rather than write something the
     * data cannot represent, the screen offers ticking on today alone and shows other days as a plan.
     * The guard is repeated here, not just in the UI, so no route around the disabled controls can
     * write a wrong completion.
     *
     * The list then updates itself, because the underlying Room query re-emits once the row changes.
     */
    fun setTaskDone(taskId: Long, isDone: Boolean) {
        if (!uiState.value.isSelectedDayToday) return

        viewModelScope.launch {
            careTaskRepository.setTaskCompleted(taskId, isDone)
        }
    }

    /**
     * Deletes a task, as the swipe-left gesture does.
     *
     * No confirmation: the fragment offers an undo instead, which is quicker for the common case and
     * still safe for the accidental one. Note a task is a repeating template, so this removes it from
     * every day, exactly as deleting it from its routine's own screen does.
     */
    fun deleteTask(task: CareTask) {
        viewModelScope.launch {
            careTaskRepository.deleteTask(task)
        }
    }

    /**
     * Puts back a task removed by [deleteTask], for the undo action.
     *
     * Re-inserting the original object restores its id as well as its contents, because Room only
     * generates a new key when given zero — so the task returns exactly as it was.
     */
    fun restoreTask(task: CareTask) {
        viewModelScope.launch {
            careTaskRepository.addTask(task)
        }
    }

    /**
     * Clears the ticks on every task currently shown as done, so the day starts over.
     *
     * Scoped to exactly what is on screen — the chosen pet and category — because that is what the
     * user was looking at when they asked; resetting a pet or category they had filtered out of view
     * would be a surprise. The tasks themselves are untouched, and like ticking this is only offered
     * on today (see [setTaskDone]).
     *
     * Each task is cleared through [CareTaskRepository.setTaskCompleted], the same call un-ticking a
     * single circle makes, so a reset and a manual un-tick leave identical rows behind.
     */
    fun resetDay() {
        val state = uiState.value
        if (!state.isSelectedDayToday) return

        val doneTaskIds = state.items
            .filterIsInstance<ChecklistItem.TaskRow>()
            .filter { it.isDone }
            .map { it.task.id }

        if (doneTaskIds.isEmpty()) return

        viewModelScope.launch {
            doneTaskIds.forEach { careTaskRepository.setTaskCompleted(it, false) }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Delegation
    // ------------------------------------------------------------------------------------------

    // Runs alongside the list rather than inside its state, because it is driven by the user rather
    // than derived from the database — folding it in would rebuild the whole list at every step.
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
     * SMS permission prompt, during which the screen can be recreated. Were the stage still Preview,
     * the confirmation dialog would pop up again behind the prompt.
     */
    fun onSendConfirmed() {
        val recipient = (_delegationStage.value as? DelegationStage.Preview)?.recipient ?: return
        _delegationStage.value = DelegationStage.Sending(recipient)
    }

    /** Returns to idle, whether the message was sent, cancelled or failed. */
    fun onDelegationFinished() {
        _delegationStage.value = DelegationStage.Idle
    }

    companion object {

        /**
         * The pet id meaning "none given", matching the app's convention for absent ids and the
         * default declared on this destination in nav_graph.xml. Its presence puts the screen into
         * tab mode.
         */
        const val NO_PET_ID = -1L

        /**
         * The pet filter meaning "every pet". The same value as [NO_PET_ID] — both mean "no one pet"
         * — but named separately because they answer different questions: one is how the screen was
         * opened, the other which chip is selected.
         */
        const val ALL_PETS = NO_PET_ID

        /**
         * Builds the ViewModel.
         *
         * A function rather than a property because neither id is known until runtime: the owner
         * comes from the session and the pet from the navigation argument.
         *
         * @param ownerId Whose pets the tab shows.
         * @param petId The pet to show, or [NO_PET_ID] for the tab.
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
