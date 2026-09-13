package com.prash.petcare.ui.pet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.repository.PetRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Everything the Pets tab displays.
 *
 * @property pets The user's pets, newest first.
 * @property isLoading True until the first results arrive, so the screen can tell "still loading"
 *   apart from "genuinely has no pets" — without it, the empty state would flash on every launch.
 */
data class PetsUiState(
    val pets: List<Pet> = emptyList(),
    val isLoading: Boolean = true
) {
    /** True when the empty state should replace the list. */
    val hasNoPets: Boolean get() = !isLoading && pets.isEmpty()
}

/**
 * Supplies the Pets tab with the signed-in user's animals.
 *
 * Sits in the middle of the MVVM chain: it reads from [PetRepository] and publishes one
 * [PetsUiState] for [PetsFragment] to render. It holds no views and no Context, so it survives
 * rotation with its data intact.
 *
 * Deliberately much thinner than [com.prash.petcare.ui.home.HomeViewModel], which shows the same
 * pets. The dashboard has to work out each animal's day, so it gathers routines and tasks as well;
 * this tab is the plain list and needs nothing but the pets. Two ViewModels rather than one shared
 * state, because a screen that subscribed to the dashboard's calculation purely to draw names would
 * be running several queries per pet for data it never reads.
 *
 * @param petRepository Source of the pet list.
 * @param userId Whose pets to show, read from the session — see
 *   [com.prash.petcare.ui.session.SessionViewModel].
 */
class PetsViewModel(
    petRepository: PetRepository,
    userId: Long
) : ViewModel() {

    /**
     * The user's pets.
     *
     * Room drives the flow, so adding, editing or deleting a pet — or one arriving from the
     * Firestore sync — refreshes this list by itself. There is no manual refresh anywhere in the
     * app for exactly this reason.
     *
     * [stateIn] converts the cold query into a hot [StateFlow] that remembers the latest value, so
     * a rotation re-attaches to data that is already there instead of re-querying and flashing
     * empty. `WhileSubscribed(5_000)` keeps the query alive for five seconds after the last
     * observer leaves — long enough to survive a rotation, short enough that a backgrounded screen
     * stops doing work.
     */
    val uiState: StateFlow<PetsUiState> = petRepository.observePetsForOwner(userId)
        .map { pets -> PetsUiState(pets = pets, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PetsUiState()
        )

    companion object {

        /**
         * Builds the ViewModel for a particular account.
         *
         * A function rather than a property because the user id is only known at runtime and has
         * to be captured here to reach the constructor.
         */
        fun factory(userId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                PetsViewModel(
                    petRepository = application.petRepository,
                    userId = userId
                )
            }
        }
    }
}
