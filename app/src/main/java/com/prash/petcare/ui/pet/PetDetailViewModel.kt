package com.prash.petcare.ui.pet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.PetRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Everything the pet detail screen displays.
 *
 * @property pet The animal being viewed, or null while loading or if it has been deleted.
 * @property routines That pet's care routines, active ones first.
 * @property isLoading True until the first database results arrive, so the screen can tell
 *   "still loading" apart from "genuinely has no routines".
 */
data class PetDetailUiState(
    val pet: Pet? = null,
    val routines: List<CareRoutine> = emptyList(),
    val isLoading: Boolean = true
) {
    /** True when the "no routines yet" message should replace the list. */
    val hasNoRoutines: Boolean get() = !isLoading && routines.isEmpty()
}

/**
 * Supplies the pet detail screen with the pet and its care routines.
 *
 * Read-only: this screen shows information and offers routes onward to the edit form, the routine
 * form and the checklist. Anything that changes data is handled by the ViewModel belonging to the
 * screen the user moves to.
 *
 * @param petRepository Source of the pet itself.
 * @param careRoutineRepository Source of that pet's routines.
 * @param petId Which pet to show, passed in as a navigation argument.
 */
class PetDetailViewModel(
    petRepository: PetRepository,
    careRoutineRepository: CareRoutineRepository,
    petId: Long
) : ViewModel() {

    /**
     * The pet and its routines as one combined state.
     *
     * [combine] means either stream updating — renaming the pet, adding a routine — refreshes the
     * screen, and the two values always describe the same moment. [stateIn] then caches the latest
     * value so a rotation re-attaches instead of re-querying and flashing empty.
     */
    val uiState: StateFlow<PetDetailUiState> = combine(
        petRepository.observePet(petId),
        careRoutineRepository.observeRoutinesForPet(petId)
    ) { pet, routines ->
        PetDetailUiState(
            pet = pet,
            routines = routines,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PetDetailUiState()
    )

    companion object {
        fun factory(petId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                PetDetailViewModel(
                    petRepository = application.petRepository,
                    careRoutineRepository = application.careRoutineRepository,
                    petId = petId
                )
            }
        }
    }
}
