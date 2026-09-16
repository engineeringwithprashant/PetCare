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

/**
 * One card on the Pets tab: the animal, who looks after it, and how much care it has due today.
 *
 * Everything the card draws is gathered here, so the adapter only formats and never looks anything
 * up. It also means [PetAdapter]'s DiffUtil sees a change when *any* of these move — ticking a
 * task off on the Tasks tab, or the user renaming themselves, redraws exactly the cards affected.
 *
 * @property pet The animal itself; the card reads its name, species, age, weight and photo.
 * @property ownerName Who the pet is cared for by — the signed-in account's name, since the list only
 *   ever holds that account's pets. Empty until the profile has been read.
 * @property tasksDueToday How many care tasks fall due today for this pet, by the same rule the
 *   dashboard uses — see [CareSchedule.tasksDueOn].
 */
data class PetCard(
    val pet: Pet,
    val ownerName: String,
    val tasksDueToday: Int,
    /**
     * Which unit the card writes the pet's weight in, from the profile screen's preference. Carried
     * on the card rather than read by the adapter, so switching to imperial makes this a *different*
     * card — which is what has DiffUtil redraw it by itself, with no manual refresh.
     */
    val weightUnit: WeightUnit
)

/**
 * Everything the Pets tab displays.
 *
 * @property pets One card per pet, newest first.
 * @property isLoading True until the first results arrive, so the screen can tell "still loading"
 *   apart from "genuinely has no pets" — without it, the empty state would flash on every launch.
 */
data class PetsUiState(
    val pets: List<PetCard> = emptyList(),
    val isLoading: Boolean = true
) {
    /** True when the empty state should replace the list. */
    val hasNoPets: Boolean get() = !isLoading && pets.isEmpty()
}

/**
 * Supplies the Pets tab with the signed-in user's animals.
 *
 * Sits in the middle of the MVVM chain: it reads from four repositories and publishes one
 * [PetsUiState] for [PetsFragment] to render. It holds no views and no Context, so it survives
 * rotation with its data intact — and it formats nothing: turning a count into "Two pets, one
 * household" needs string resources, which belong to the View.
 *
 * **Why it now gathers routines and tasks as well as pets.** Each card reports how many tasks the
 * pet has due today. Care tasks are stored as repeating templates, not one row per day, so there is
 * no column to read that from — it has to be worked out from each pet's routines, exactly as the
 * dashboard does. Both ViewModels call [CareSchedule.tasksDueOn] for that, so the Home tab and this
 * one can never disagree about a pet's day.
 *
 * It stays thinner than [com.prash.petcare.ui.home.HomeViewModel] all the same: no checklist, no
 * appointments and no sync status, because this screen shows none of them.
 *
 * @param userRepository Source of the owner's name for the "Cared for by" line.
 * @param petRepository Source of the pet list.
 * @param careRoutineRepository Source of the schedules that decide what runs today.
 * @param careTaskRepository Source of the tasks those schedules own.
 * @param userId Whose pets to show, read from the session — see
 *   [com.prash.petcare.ui.session.SessionViewModel].
 */
class PetsViewModel(
    userRepository: UserRepository,
    petRepository: PetRepository,
    careRoutineRepository: CareRoutineRepository,
    careTaskRepository: CareTaskRepository,
    settingsStore: SettingsStore,
    userId: Long
) : ViewModel() {

    /**
     * Each pet paired with its count of tasks due today.
     *
     * [flatMapLatest] is needed because the pet list is itself a stream: whenever a pet is added or
     * removed, the set of per-pet queries has to be rebuilt. "Latest" means the subscriptions opened
     * for the previous list are cancelled, so a deleted pet's queries stop rather than piling up.
     *
     * The inner [combine] over a *list* of flows emits one list holding every pet's result, so the
     * screen always receives a complete, consistent set of cards rather than pets trickling in one
     * at a time.
     *
     * The empty case is short-circuited because `combine` over an empty list never emits at all — a
     * user with no pets would otherwise sit on the loading state forever and never see the empty
     * state inviting them to add one.
     */
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
                            // "Today" is recomputed on every emission rather than captured once, so
                            // the count stays right if the screen is still open after midnight.
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

    /**
     * The full state of the Pets tab.
     *
     * [combine] merges the owner's profile with the per-pet counts: whenever either emits — a pet is
     * added, a routine is paused, a task is created — this recalculates and the cards update. Room
     * drives every one of these flows, including rows written by the Firestore sync, so there is no
     * manual refresh anywhere.
     *
     * [stateIn] converts the cold result into a hot [StateFlow] that remembers the latest value, so
     * a rotation re-attaches to data that is already there instead of re-querying and flashing
     * empty. `WhileSubscribed(5_000)` keeps the queries alive for five seconds after the last
     * observer leaves — long enough to survive a rotation, short enough that a backgrounded screen
     * stops doing work.
     */
    val uiState: StateFlow<PetsUiState> = combine(
        userRepository.observeUser(userId),
        petsWithDueCounts,
        // One more input, so changing the unit on the profile screen redraws these cards by the same
        // path adding a pet does.
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

        /**
         * Builds the ViewModel for a particular account.
         *
         * A function rather than a property because the user id is only known at runtime and has
         * to be captured here to reach the constructor. The repositories come from the Application,
         * which creates each one once — so this screen shares them with every other ViewModel
         * rather than opening its own database connection.
         */
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
