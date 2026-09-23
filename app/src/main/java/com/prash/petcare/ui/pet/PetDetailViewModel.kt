package com.prash.petcare.ui.pet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.SettingsStore
import com.prash.petcare.data.local.WeightUnit
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.VaccinationRecord
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.data.repository.VaccinationRepository
import com.prash.petcare.util.CareSchedule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One care routine as its card on the pet detail screen draws it.
 *
 * The task count travels with the routine rather than being looked up by the adapter, so the list is
 * drawn from one consistent snapshot and DiffUtil sees a change when a task is added to a routine as
 * well as when the routine itself is edited.
 *
 * @property routine The routine: its name, schedule and whether it is paused.
 * @property taskCount How many care tasks the routine contains.
 */
data class PetRoutine(
    val routine: CareRoutine,
    val taskCount: Int
)

/**
 * One vaccination as its row on the pet detail screen draws it: the stored record, and how urgent it
 * is today.
 *
 * **The status travels with the record rather than being worked out by the adapter**, for two
 * reasons. It keeps the View layer free of the rule — the adapter draws what it is given, which is
 * what the MVVM split is for — and it means the status and the day count come from a single
 * evaluation, so a pill can never show one state's colour with another state's wording.
 *
 * It is also what lets DiffUtil notice a record whose *status* has changed while the record itself
 * has not: a booster that crossed its due date overnight produces a different [VaccinationRow] from
 * the same database row, and the list redraws. See [VaccinationAdapter].
 *
 * @property record The stored vaccination — what was given, when, where, and when it is next due.
 * @property status How that due date reads against today. Derived, never stored; see
 *   [VaccinationStatus].
 * @property daysUntilDue Whole days until the next dose, negative when it has passed. Carried
 *   alongside the status because the "DUE IN 11D" pill needs the number, and recomputing it in the
 *   adapter would risk the two disagreeing.
 */
data class VaccinationRow(
    val record: VaccinationRecord,
    val status: VaccinationStatus,
    val daysUntilDue: Int
)

/**
 * Everything the pet detail screen displays.
 *
 * @property pet The animal being viewed, or null while loading or if it has been deleted.
 * @property ownerName Who cares for the pet, for the "Cared for by" row. Empty until loaded.
 * @property petCount How many pets the owner has, for the header's count badge.
 * @property routines That pet's care routines, active ones first, each with its task count.
 * @property vaccinations That pet's vaccination history, most recently given first, each with the
 *   status derived from its due date — see [VaccinationRow].
 * @property tasksDueToday How many tasks fall due for this pet today, across all its routines.
 * @property tasksDoneToday How many of those have been ticked off today.
 * @property isLoading True until the first database results arrive, so the screen can tell
 *   "still loading" apart from "genuinely has no routines".
 */
data class PetDetailUiState(
    val pet: Pet? = null,
    val ownerName: String = "",
    val petCount: Int = 0,
    val routines: List<PetRoutine> = emptyList(),
    val vaccinations: List<VaccinationRow> = emptyList(),
    val tasksDueToday: Int = 0,
    val tasksDoneToday: Int = 0,
    /** Which unit the weight pill is written in, from the profile screen's preference. */
    val weightUnit: WeightUnit = WeightUnit.METRIC,
    val isLoading: Boolean = true
) {
    /** True when the "no routines yet" message should replace the list. */
    val hasNoRoutines: Boolean get() = !isLoading && routines.isEmpty()

    /**
     * True when the vaccination card's "nothing recorded yet" row should replace its list.
     *
     * Gated on [isLoading] for the same reason [hasNoRoutines] is: without it the empty message
     * would flash up for one frame before the first database result arrives, telling the user they
     * have no records a moment before their records appear.
     */
    val hasNoVaccinations: Boolean get() = !isLoading && vaccinations.isEmpty()

    /** Today's tasks still to do — what the yellow status pill on the photo reports. */
    val tasksOutstanding: Int get() = tasksDueToday - tasksDoneToday

    /**
     * The pet's first name, for the header: "Krishala" from "Krishala Pant Thapa Baby".
     *
     * Worked out here rather than in the fragment because it is plain string handling with no
     * resources involved, and keeping it in state means it is testable without a device. The full
     * name still appears across the photo; the header only has room for one word beside its two
     * controls.
     */
    val petFirstName: String
        get() = pet?.name?.trim()?.substringBefore(' ').orEmpty()
}

/**
 * Supplies the pet detail screen with the pet, its care routines and today's progress, and carries
 * out the two routine actions offered from each routine card's menu — pause/resume and delete.
 *
 * Its place in MVVM: [PetDetailFragment] observes [uiState] and forwards taps here; this class reads
 * and writes only through repositories and never touches a DAO or holds a Context. Editing the pet or
 * a routine is still left to the form screen the user moves on to — what lives here are the actions
 * that need no form.
 *
 * @param userRepository Source of the owner's name.
 * @param petRepository Source of the pet itself and the owner's pet count.
 * @param careRoutineRepository Source of the pet's routines, and where pause and delete are written.
 * @param careTaskRepository Source of the pet's tasks, for per-routine counts and today's progress.
 * @param vaccinationRepository Source of the pet's vaccination history. Records are written by
 *   [AddVaccinationViewModel] on the screen the "+ RECORD" button opens and arrive here by
 *   themselves, because the query below is a Flow; the only write this class makes to it is
 *   [undoVaccinationRecord].
 * @param petId Which pet to show, passed in as a navigation argument.
 */
class PetDetailViewModel(
    userRepository: UserRepository,
    petRepository: PetRepository,
    private val careRoutineRepository: CareRoutineRepository,
    private val careTaskRepository: CareTaskRepository,
    private val vaccinationRepository: VaccinationRepository,
    settingsStore: SettingsStore,
    petId: Long
) : ViewModel() {

    /** The pet itself. Named because two streams below depend on it. */
    private val pet: Flow<Pet?> = petRepository.observePet(petId)

    /**
     * The owner's name and how many pets they have, as one pair.
     *
     * Both are keyed on the pet's *owner*, which is only known once the pet has loaded — hence
     * [flatMapLatest]: each time the owner id arrives (in practice, once) the queries for that owner
     * are opened, and any previous ones are cancelled. [distinctUntilChanged] stops an ordinary edit
     * to the pet, which re-emits the row with the same owner, from tearing those queries down and
     * reopening them for nothing.
     *
     * The owner comes from the pet rather than from the signed-in session so the "Cared for by" row
     * can never name somebody other than the account the pet actually belongs to.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ownerDetails: Flow<Pair<String, Int>> = pet
        .map { it?.ownerId }
        .distinctUntilChanged()
        .flatMapLatest { ownerId ->
            if (ownerId == null) {
                flowOf("" to 0)
            } else {
                combine(
                    userRepository.observeUser(ownerId),
                    petRepository.observePetCountForOwner(ownerId)
                ) { user, count -> user?.fullName.orEmpty() to count }
            }
        }

    /**
     * The three lists this pet owns — its routines, its tasks and its vaccination history — combined
     * into one stream before they reach the state below.
     *
     * Grouped for a plain mechanical reason: Kotlin's [combine] is only typed up to five flows, and
     * the state needs six inputs. Rather than fall back to the untyped vararg version and lose the
     * compiler's help, the three that belong together are combined first. They genuinely do belong
     * together — all three are "what is recorded against this pet" — so the grouping reads as
     * structure rather than as a workaround.
     */
    private val petRecords: Flow<Triple<List<CareRoutine>, List<CareTask>, List<VaccinationRecord>>> =
        combine(
            careRoutineRepository.observeRoutinesForPet(petId),
            careTaskRepository.observeTasksForPet(petId),
            vaccinationRepository.observeRecordsForPet(petId)
        ) { routines, tasks, vaccinations -> Triple(routines, tasks, vaccinations) }

    /**
     * The full state of the screen.
     *
     * [combine] means any of the streams updating — renaming the pet, adding a routine, ticking a
     * task on the Tasks tab, recording a vaccination — refreshes the screen, and all the values
     * always describe the same moment. [stateIn] caches the latest value so a rotation re-attaches
     * instead of re-querying and flashing empty.
     *
     * The task counts are derived from the pet's task list rather than queried separately:
     *  - **per routine**, by grouping tasks on their routine id;
     *  - **due today**, with [CareSchedule.tasksDueOn] — the same rule the Home and Pets tabs use, so
     *    this pill can never disagree with the figure on the card the user tapped to get here;
     *  - **done today**, with [CareSchedule.isDoneOn], which tests the completion *date* so
     *    yesterday's ticks do not count.
     *
     * Each vaccination's status is derived here for the same reason and in the same place: it is a
     * reading of today's date, so it is worked out on every emission rather than stored — see
     * [VaccinationStatus].
     */
    val uiState: StateFlow<PetDetailUiState> = combine(
        pet,
        petRecords,
        ownerDetails,
        // One more input, so switching units on the profile screen redraws the weight pill by the
        // same path an edit to the pet does.
        settingsStore.weightUnit
    ) { pet, (routines, tasks, vaccinations), (ownerName, petCount), weightUnit ->
        // Tasks whose routine was deleted have a null routine id and belong to no card, so they
        // simply drop out of the counts.
        val countsByRoutine = tasks.groupingBy { it.routineId }.eachCount()

        // "Today" is recomputed on every emission rather than captured once, so the pill stays right
        // if the screen is left open past midnight.
        val dayStart = CareSchedule.startOfToday()
        val dayEnd = CareSchedule.endOfToday()
        val dueToday = CareSchedule.tasksDueOn(routines, tasks, dayStart)

        PetDetailUiState(
            pet = pet,
            ownerName = ownerName,
            petCount = petCount,
            routines = routines.map { routine ->
                PetRoutine(routine = routine, taskCount = countsByRoutine[routine.id] ?: 0)
            },
            // The DAO already returns these newest-given-first, so the order is the query's rather
            // than being re-sorted here — one place decides it, which is what stops the card and the
            // database disagreeing about what "most recent" means.
            vaccinations = vaccinations.map { record ->
                val (status, days) = VaccinationStatus.of(record.dateDue)
                VaccinationRow(record = record, status = status, daysUntilDue = days)
            },
            tasksDueToday = dueToday.size,
            tasksDoneToday = dueToday.count { CareSchedule.isDoneOn(it, dayStart, dayEnd) },
            weightUnit = weightUnit,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PetDetailUiState()
    )

    /**
     * Pauses a running routine, or resumes a paused one — the card menu's toggle.
     *
     * Pausing is the reversible alternative to deleting: the routine and its tasks stay as they are,
     * but [CareSchedule.occursOn] stops treating it as due, so its tasks leave the checklist (and the
     * status pill's count) until it is switched back on.
     *
     * A targeted UPDATE of one column through the repository rather than rewriting the whole row, so
     * it cannot undo an edit made to the routine elsewhere a moment earlier.
     */
    fun toggleRoutineActive(routine: CareRoutine) {
        viewModelScope.launch {
            careRoutineRepository.setRoutineActive(routine.id, !routine.isActive)
        }
    }

    /**
     * Deletes a routine and every task inside it.
     *
     * The tasks go first, for the reason set out on
     * [com.prash.petcare.ui.routine.RoutineDetailViewModel.delete]: the routine foreign key is
     * SET_NULL, so deleting the routine alone would strand its tasks with no schedule and no way
     * back to them. The confirmation dialog is the fragment's job; by the time this runs the user
     * has agreed.
     *
     * No event is sent afterwards. The routine query re-emits without the deleted row and the card
     * animates out on its own.
     */
    fun deleteRoutine(routine: CareRoutine) {
        viewModelScope.launch {
            careTaskRepository.deleteTasksForRoutine(routine.id)
            careRoutineRepository.deleteRoutine(routine)
        }
    }

    /**
     * Takes back a vaccination just recorded — the undo on the confirmation Snackbar.
     *
     * **Both halves go, not just the record.** Saving optionally creates a vet-visit task on the due
     * date, and an undo that removed the record but left "Rabies due" sitting on the checklist would
     * be a half undo: the task would outlive the only thing that explained where it came from.
     * [AddVaccinationViewModel.NO_TASK_ID] means no task was created, so there is nothing to remove.
     *
     * The task is fetched before being deleted because the repository deletes by entity — it also
     * writes a sync tombstone, which needs the row. A task that has somehow gone already simply
     * leaves nothing to do.
     *
     * No event is sent afterwards. Both queries re-emit without the deleted rows and the card and the
     * checklist redraw themselves.
     */
    fun undoVaccinationRecord(recordId: Long, taskId: Long) {
        viewModelScope.launch {
            vaccinationRepository.deleteRecord(recordId)

            if (taskId != AddVaccinationViewModel.NO_TASK_ID) {
                careTaskRepository.getTask(taskId)?.let { careTaskRepository.deleteTask(it) }
            }
        }
    }

    companion object {

        /**
         * Builds the ViewModel for one pet. A function rather than a property because the pet id is
         * only known at runtime, from the navigation arguments. The repositories come from the
         * Application, so this screen shares them with every other ViewModel.
         */
        fun factory(petId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                PetDetailViewModel(
                    userRepository = application.userRepository,
                    petRepository = application.petRepository,
                    careRoutineRepository = application.careRoutineRepository,
                    careTaskRepository = application.careTaskRepository,
                    vaccinationRepository = application.vaccinationRepository,
                    settingsStore = application.settingsStore,
                    petId = petId
                )
            }
        }
    }
}
