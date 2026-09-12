package com.prash.petcare.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.data.sync.SyncManager
import com.prash.petcare.data.sync.SyncStatus
import com.prash.petcare.util.CareSchedule
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Everything the home screen displays, gathered into one object.
 *
 * The fragment receives a single state rather than several separate values, which means the
 * greeting, the pet list and the task summary can never be drawn from different moments in time —
 * they always arrive together, already consistent.
 *
 * @property userName Name of the signed-in user; empty until the first database read returns.
 * @property pets The user's pets, newest first.
 * @property tasksDueToday How many care tasks fall due today, completed or not.
 * @property tasksCompletedToday How many of those have been ticked off.
 * @property isLoading True until the first results arrive, so the screen can tell "still loading"
 *   apart from "genuinely has no pets" — without it, the empty state would flash on every launch.
 */
data class HomeUiState(
    val userName: String = "",
    val pets: List<Pet> = emptyList(),
    val tasksDueToday: Int = 0,
    val tasksCompletedToday: Int = 0,
    val isLoading: Boolean = true
) {
    /** True when the empty state should replace the list. */
    val hasNoPets: Boolean get() = !isLoading && pets.isEmpty()
}

/**
 * Supplies the home screen with its data.
 *
 * Sits in the middle of the MVVM chain: it reads from three repositories and publishes one
 * [HomeUiState] for [HomeFragment] to render. It holds no views and no Context, so it survives
 * rotation with its data intact.
 *
 * @param userRepository Source of the signed-in user's profile.
 * @param petRepository Source of the pet list.
 * @param careTaskRepository Source of today's care tasks.
 * @param syncManager The background sync. This screen is the only one that touches it, for the two
 *   reasons described in [syncStatus] and the `init` block below. It is a data-layer object holding
 *   no Android view types, so depending on it here does not compromise what makes a ViewModel
 *   testable and rotation-proof.
 * @param userId Which account to show, passed in as a navigation argument.
 */
class HomeViewModel(
    userRepository: UserRepository,
    petRepository: PetRepository,
    careTaskRepository: CareTaskRepository,
    syncManager: SyncManager,
    userId: Long
) : ViewModel() {

    /**
     * Tells the sync layer which account is now signed in.
     *
     * This screen is where a session effectively begins: the app keeps no persisted login, so it
     * opens on the sign-in screen and the home screen is the first place the account is known. From
     * here the sync runs on its own.
     *
     * In `init` rather than in the fragment because a ViewModel is created once per screen and
     * survives rotation, whereas the fragment is rebuilt — so this happens exactly once per visit
     * rather than every time the device is turned.
     */
    init {
        syncManager.onUserSignedIn(userId)
    }

    /**
     * What the sync is doing, passed straight through for the indicator to display.
     *
     * Kept out of [uiState] on purpose. That state is assembled from database queries and describes
     * the user's pets; sync progress describes the app itself, changes on a completely different
     * rhythm, and folding it in would mean rebuilding the whole pet list every time the status
     * moved from "syncing" to "synced".
     */
    val syncStatus: StateFlow<SyncStatus> = syncManager.status

    /**
     * The full state of the home screen.
     *
     * [combine] merges the three database streams into one: whenever any of them emits — a pet is
     * added, a task is ticked off, the user renames themselves — this recalculates and the screen
     * updates. There is no manual refresh anywhere in the app because Room drives these flows.
     *
     * [stateIn] then converts the cold result into a hot [StateFlow] that remembers the latest
     * value, so a rotation re-attaches to data that is already there instead of re-querying and
     * flashing empty. `WhileSubscribed(5_000)` keeps the queries alive for five seconds after the
     * last observer leaves — long enough to survive a rotation, short enough that a backgrounded
     * screen stops doing work.
     */
    val uiState: StateFlow<HomeUiState> = combine(
        userRepository.observeUser(userId),
        petRepository.observePetsForOwner(userId),
        careTaskRepository.observeTasksForOwnerBetween(
            userId,
            CareSchedule.startOfToday(),
            CareSchedule.endOfToday()
        )
    ) { user, pets, todayTasks ->
        HomeUiState(
            userName = user?.fullName.orEmpty(),
            pets = pets,
            tasksDueToday = todayTasks.size,
            tasksCompletedToday = todayTasks.count { it.isCompleted },
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState()
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
                HomeViewModel(
                    userRepository = application.userRepository,
                    petRepository = application.petRepository,
                    careTaskRepository = application.careTaskRepository,
                    syncManager = application.syncManager,
                    userId = userId
                )
            }
        }
    }
}
