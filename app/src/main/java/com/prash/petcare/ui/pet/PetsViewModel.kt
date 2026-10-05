package com.prash.petcare.ui.pet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.local.SettingsStore
import com.prash.petcare.data.local.WeightUnit
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.util.CareSchedule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** One card on the Pets tab: the animal, who looks after it, and how much care it has due today. */
data class PetCard(
    val pet: Pet,
    val ownerName: String,
    val tasksDueToday: Int,
    /** Which unit the card writes the pet's weight in, from the profile screen's preference. */
    val weightUnit: WeightUnit
)

/** Everything the Pets tab displays. */
data class PetsUiState(
    val pets: List<PetCard> = emptyList(),
    val isLoading: Boolean = true
) {
    /** True when the empty state should replace the list. */
    val hasNoPets: Boolean get() = !isLoading && pets.isEmpty()
}

/** Supplies the Pets tab with the signed-in user's animals. */
class PetsViewModel(
    userRepository: UserRepository,
    petRepository: PetRepository,
    careRoutineRepository: CareRoutineRepository,
    careTaskRepository: CareTaskRepository,
    settingsStore: SettingsStore,
    userId: Long
) : ViewModel() {

    /** Each pet paired with its count of tasks due today. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val petsWithDueCounts: Flow<List<Pair<Pet, Int>>> =
        petRepository.observePetsForOwner(userId).flatMapLatest { pets ->
            if (pets.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    pets.map { pet ->
                        combine(
                            careRoutineRepository.observeActiveRoutinesForPet(pet.id),
                            careTaskRepository.observeTasksForPet(pet.id)
                        ) { routines, tasks ->
                            // "Today" is recomputed on every emission rather than captured once.
                            val dueToday = CareSchedule.tasksDueOn(
                                routines = routines,
                                tasks = tasks,
                                dayStartMillis = CareSchedule.startOfToday()
                            )
                            pet to dueToday.size
                        }
                    }
                ) { perPet -> perPet.toList() }
            }
        }

    /** The full state of the Pets tab. */
    val uiState: StateFlow<PetsUiState> = combine(
        userRepository.observeUser(userId),
        petsWithDueCounts,
        // One more input, so changing the unit on the profile screen redraws these cards.
        settingsStore.weightUnit
    ) { user, pets, weightUnit ->
        val ownerName = user?.fullName.orEmpty()

        PetsUiState(
            pets = pets.map { (pet, dueToday) ->
                PetCard(
                    pet = pet,
                    ownerName = ownerName,
                    tasksDueToday = dueToday,
                    weightUnit = weightUnit
                )
            },
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PetsUiState()
    )

    companion object {

        /** Builds the ViewModel for a particular account. */
        fun factory(userId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                PetsViewModel(
                    userRepository = application.userRepository,
                    petRepository = application.petRepository,
                    careRoutineRepository = application.careRoutineRepository,
                    careTaskRepository = application.careTaskRepository,
                    settingsStore = application.settingsStore,
                    userId = userId
                )
            }
        }
    }
}
