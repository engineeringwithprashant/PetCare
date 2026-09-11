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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

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
 * @param userId Which account to show, passed in as a navigation argument.
 */
class HomeViewModel(
    userRepository: UserRepository,
    petRepository: PetRepository,
    careTaskRepository: CareTaskRepository,
    userId: Long
) : ViewModel() {

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
        careTaskRepository.observeTasksForOwnerBetween(userId, startOfToday, endOfToday)
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
                    userId = userId
                )
            }
        }

        /**
         * Midnight at the start of the current day, in the device's own time zone.
         *
         * [Calendar] is used rather than `java.time` because the app supports minSdk 24 and
         * `java.time` only arrived on API 26 without extra library desugaring.
         *
         * Note this is evaluated once, when the ViewModel is created. An app left open across
         * midnight will keep showing the previous day's summary until the screen is recreated —
         * acceptable here, and the point at which a real scheduler would take over.
         */
        private val startOfToday: Long
            get() = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

        /**
         * The last millisecond of the current day.
         *
         * Reached by adding one day to midnight and stepping back a millisecond, rather than
         * adding a fixed 24 hours. On the days clocks change, a day is 23 or 25 hours long, and
         * [Calendar.add] accounts for that where plain arithmetic would not.
         */
        private val endOfToday: Long
            get() = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis - 1
    }
}
