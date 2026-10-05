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

/** One care routine as its card on the pet detail screen draws it. */
data class PetRoutine(
    val routine: CareRoutine,
    val taskCount: Int
)

/** One vaccination as its row on the pet detail screen draws it: the stored record, and how urgent it is today. */
data class VaccinationRow(
    val record: VaccinationRecord,
    val status: VaccinationStatus,
    val daysUntilDue: Int
)

/** Everything the pet detail screen displays. */
data class PetDetailUiState(
    val pet: Pet? = null,
    val ownerName: String = "",
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

    /** True when the vaccination card's "nothing recorded yet" row should replace its list. */
    val hasNoVaccinations: Boolean get() = !isLoading && vaccinations.isEmpty()

    /** Today's tasks still to do — what the yellow status pill on the photo reports. */
    val tasksOutstanding: Int get() = tasksDueToday - tasksDoneToday

    /** The pet's first name, for the header: "Krishala" from "Krishala Pant Thapa Baby". */
    val petFirstName: String
        get() = pet?.name?.trim()?.substringBefore(' ').orEmpty()
}

/** Supplies the pet detail screen with the pet, its care routines and today's progress. */
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

    /** The owner's name, for the "Cared for by" row. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ownerName: Flow<String> = pet
        .map { it?.ownerId }
        .distinctUntilChanged()
        .flatMapLatest { ownerId ->
            if (ownerId == null) {
                flowOf("")
            } else {
                userRepository.observeUser(ownerId).map { it?.fullName.orEmpty() }
            }
        }

    /** The three lists this pet owns — its routines, its tasks and its vaccination history. */
    private val petRecords: Flow<Triple<List<CareRoutine>, List<CareTask>, List<VaccinationRecord>>> =
        combine(
            careRoutineRepository.observeRoutinesForPet(petId),
            careTaskRepository.observeTasksForPet(petId),
            vaccinationRepository.observeRecordsForPet(petId)
        ) { routines, tasks, vaccinations -> Triple(routines, tasks, vaccinations) }

    /** The full state of the screen. */
    val uiState: StateFlow<PetDetailUiState> = combine(
        pet,
        petRecords,
        ownerName,
        // One more input, so switching units on the profile screen redraws the weight pill.
        settingsStore.weightUnit
    ) { pet, (routines, tasks, vaccinations), ownerName, weightUnit ->
        // Tasks whose routine was deleted have a null routine id and belong to no card.
        val countsByRoutine = tasks.groupingBy { it.routineId }.eachCount()

        // "Today" is recomputed on every emission rather than captured once.
        val dayStart = CareSchedule.startOfToday()
        val dayEnd = CareSchedule.endOfToday()
        val dueToday = CareSchedule.tasksDueOn(routines, tasks, dayStart)

        PetDetailUiState(
            pet = pet,
            ownerName = ownerName,
            routines = routines.map { routine ->
                PetRoutine(routine = routine, taskCount = countsByRoutine[routine.id] ?: 0)
            },
            // The DAO already returns these newest-given-first, so the order is the query's.
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

    /** Pauses a running routine, or resumes a paused one — the card menu's toggle. */
    fun toggleRoutineActive(routine: CareRoutine) {
        viewModelScope.launch {
            careRoutineRepository.setRoutineActive(routine.id, !routine.isActive)
        }
    }

    /** Deletes a routine and every task inside it. */
    fun deleteRoutine(routine: CareRoutine) {
        viewModelScope.launch {
            careTaskRepository.deleteTasksForRoutine(routine.id)
            careRoutineRepository.deleteRoutine(routine)
        }
    }

    /** Takes back a vaccination just recorded — the undo on the confirmation Snackbar. */
    fun undoVaccinationRecord(recordId: Long, taskId: Long) {
        viewModelScope.launch {
            vaccinationRepository.deleteRecord(recordId)

            if (taskId != AddVaccinationViewModel.NO_TASK_ID) {
                careTaskRepository.getTask(taskId)?.let { careTaskRepository.deleteTask(it) }
            }
        }
    }

    companion object {

        /** Builds the ViewModel for one pet. A function rather than a property. */
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
