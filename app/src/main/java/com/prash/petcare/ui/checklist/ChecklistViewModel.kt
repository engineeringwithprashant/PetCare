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
import com.prash.petcare.data.local.SettingsStore
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

/** The part of the day a task falls in, which is how the Tasks tab groups its list. */
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

/** One row of the task list. */
sealed interface ChecklistItem {

    /** A stable identity for this row, used by DiffUtil to tell "the same row, changed" from "a different row". */
    val itemId: String

    /** "MORNING 2/3" — a section header with its own progress. */
    data class SectionHeader(
        val timeOfDay: TimeOfDay,
        val doneCount: Int,
        val totalCount: Int
    ) : ChecklistItem {
        override val itemId: String get() = "section_${timeOfDay.name}"
    }

    /** One task card. */
    data class TaskRow(
        val task: CareTask,
        val petName: String,
        val isDone: Boolean
    ) : ChecklistItem {
        override val itemId: String get() = "task_${task.id}"
    }
}

/** One cell of the week strip. */
data class WeekDay(
    val dayStart: Long,
    val isToday: Boolean,
    val isSelected: Boolean,
    val hasTasks: Boolean
)

/** A routine a new task could be added to, for the "+ Add task" chooser. */
data class RoutineOption(val routine: CareRoutine, val petName: String)

/** Everything the Tasks screen displays. */
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

    /** True when there is care shown and all of it is finished. */
    val isAllDone: Boolean get() = !isLoading && totalCount > 0 && outstandingCount == 0
}

/** The person a checklist is being delegated to. */
data class DelegationRecipient(val name: String, val phoneNumber: String)

/** How far through the SMS delegation flow the user is. */
sealed interface DelegationStage {

    /** Nothing in progress. */
    data object Idle : DelegationStage

    /** A recipient has been chosen; the message is waiting to be confirmed. */
    data class Preview(val recipient: DelegationRecipient) : DelegationStage

    /** The user confirmed; the message is being sent. */
    data class Sending(val recipient: DelegationRecipient) : DelegationStage
}

/** Supplies the Tasks screen: the care scheduled on a chosen day. */
class ChecklistViewModel(
    petRepository: PetRepository,
    careRoutineRepository: CareRoutineRepository,
    private val careTaskRepository: CareTaskRepository,
    private val settingsStore: SettingsStore,
    ownerId: Long,
    initialPetId: Long
) : ViewModel() {

    /** True when this screen is the Tasks tab rather than one pet's checklist, and should offer the pet chips. */
    val showsPetPicker: Boolean = initialPetId == NO_PET_ID

    // What the user has chosen.

    /** The chosen day, or null meaning "whatever today is". */
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

    // The data.

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

    /** Each pet in scope with its active routines and tasks. */
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

    /** The full state of the screen, recomputed whenever the data or any selection changes. */
    val uiState: StateFlow<ChecklistUiState> = combine(
        petCare,
        filters,
        // The week-start preference is an input rather than something read inside buildState.
        settingsStore.weekStart
    ) { care, chosen, weekStart ->
        buildState(care, chosen, weekStart.calendarDay)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChecklistUiState()
    )

    /** Works out everything the screen shows from the loaded rows and the current selections. */
    private fun buildState(
        care: List<PetCare>,
        chosen: Filters,
        firstDayOfWeek: Int
    ): ChecklistUiState {
        // "Today" is recomputed on every emission, so the boundaries stay correct past midnight.
        val today = CareSchedule.startOfToday()
        val day = chosen.day ?: today

        // A pet chip whose pet has since been deleted falls back to "All pets".
        val petId = if (care.any { it.pet.id == chosen.petId }) chosen.petId else ALL_PETS
        val inScope = if (petId == ALL_PETS) care else care.filter { it.pet.id == petId }

        val rows = rowsOn(inScope, day, chosen.category)

        val weekDays = CareSchedule.weekContaining(today, firstDayOfWeek).map { weekDay ->
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
            // Grouped again from the outstanding rows alone, rather than filtering the grouped list.
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

    /** The task rows for [pets] on the day beginning at [dayStart], earliest first. */
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

    /** Interleaves time-of-day headers with their tasks. */
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

    // Actions.

    /** Ticks a task off, or clears it again — only while today is the day being shown. */
    fun setTaskDone(taskId: Long, isDone: Boolean) {
        if (!uiState.value.isSelectedDayToday) return

        viewModelScope.launch {
            careTaskRepository.setTaskCompleted(taskId, isDone)
        }
    }

    /** Deletes a task, as the swipe-left gesture does. */
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

    /** Clears the ticks on every task currently shown as done, so the day starts over. */
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

    // Delegation.

    // Runs alongside the list rather than inside its state, because it is driven by the user.
    private val _delegationStage = MutableStateFlow<DelegationStage>(DelegationStage.Idle)
    val delegationStage: StateFlow<DelegationStage> = _delegationStage.asStateFlow()

    /** Records the contact the user picked, moving the flow on to the confirmation preview. */
    fun onRecipientChosen(name: String, phoneNumber: String) {
        _delegationStage.value = DelegationStage.Preview(
            DelegationRecipient(name = name, phoneNumber = phoneNumber)
        )
    }

    /** Records that the user confirmed the message. */
    fun onSendConfirmed() {
        val recipient = (_delegationStage.value as? DelegationStage.Preview)?.recipient ?: return
        _delegationStage.value = DelegationStage.Sending(recipient)
    }

    /** Returns to idle, whether the message was sent, cancelled or failed. */
    fun onDelegationFinished() {
        _delegationStage.value = DelegationStage.Idle
    }

    companion object {

        /** The pet id meaning "none given". */
        const val NO_PET_ID = -1L

        /** The pet filter meaning "every pet". The same value as [NO_PET_ID] — both mean "no one pet". */
        const val ALL_PETS = NO_PET_ID

        /** Builds the ViewModel. */
        fun factory(ownerId: Long, petId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                ChecklistViewModel(
                    petRepository = application.petRepository,
                    careRoutineRepository = application.careRoutineRepository,
                    careTaskRepository = application.careTaskRepository,
                    settingsStore = application.settingsStore,
                    ownerId = ownerId,
                    initialPetId = petId
                )
            }
        }
    }
}
