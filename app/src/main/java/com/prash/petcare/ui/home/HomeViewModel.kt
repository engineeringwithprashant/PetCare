package com.prash.petcare.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.ExpenseRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.data.sync.SyncManager
import com.prash.petcare.data.sync.SyncStatus
import com.prash.petcare.util.CareSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Supplies the dashboard with its data.
 *
 * Sits in the middle of the MVVM chain: it reads from five repositories, hands the rows to
 * [HomeDashboard.build], and publishes the resulting [HomeUiState] for [HomeFragment] to render. It
 * holds no views and no Context, so it survives rotation with its data intact, and it never touches a
 * DAO — the repositories are the only way it reaches Room.
 *
 * **Four inputs, one state.** The dashboard depends on the user's name, every pet's routines, tasks
 * and expenses, the completion history behind the care streak, and — unusually — the *clock*. The
 * greeting shows the time, the next-up ring counts minutes down, and whether a walk is "good now"
 * changes as the hour passes, none of which a database change would ever trigger. So a ticking clock
 * is one more stream combined with the rest; see [clock].
 *
 * @param userRepository Source of the signed-in user's profile.
 * @param petRepository Source of the pet list.
 * @param careRoutineRepository Source of the schedules that decide what runs on each day.
 * @param careTaskRepository Source of the tasks and their completion history, and where "Mark done"
 *   is written back.
 * @param expenseRepository Source of the spending behind the "spent this month" card.
 * @param syncManager The background sync. This screen is the only one that touches it, for the two
 *   reasons described in [syncStatus] and the `init` block below. It is a data-layer object holding no
 *   Android view types, so depending on it here does not compromise what makes a ViewModel testable.
 * @param userId Which account to show, read from the session — see
 *   [com.prash.petcare.ui.session.SessionViewModel].
 */
class HomeViewModel(
    userRepository: UserRepository,
    petRepository: PetRepository,
    careRoutineRepository: CareRoutineRepository,
    private val careTaskRepository: CareTaskRepository,
    expenseRepository: ExpenseRepository,
    syncManager: SyncManager,
    userId: Long
) : ViewModel() {

    /**
     * Tells the sync layer which account is now signed in.
     *
     * This screen is where a session effectively begins: the app keeps no persisted login, so it opens
     * on the sign-in screen and the dashboard is the first place the account is known. In `init`
     * rather than in the fragment because a ViewModel is created once per visit and survives rotation,
     * whereas the fragment is rebuilt — so this happens exactly once rather than on every turn.
     */
    init {
        syncManager.onUserSignedIn(userId)
    }

    /**
     * What the sync is doing, passed straight through for the "All synced" line.
     *
     * Kept out of [uiState] on purpose: that state is assembled from the user's data, while sync
     * progress describes the app itself and changes on a completely different rhythm. Folding it in
     * would rebuild the whole dashboard every time the status moved from "syncing" to "synced".
     */
    val syncStatus: StateFlow<SyncStatus> = syncManager.status

    /**
     * Each pet together with its active routines, its tasks and its expenses.
     *
     * [flatMapLatest] is what makes this possible: the pet list is itself a stream, so the per-pet
     * queries have to be rebuilt whenever a pet is added or removed. "Latest" is the important half of
     * the name — when a new list arrives, the subscriptions opened for the previous one are cancelled,
     * so a deleted pet's queries stop rather than accumulating.
     *
     * The inner [combine] over a *list* of flows yields one emission holding every pet, which is what
     * lets the state be assembled from a single consistent snapshot. The empty case is short-circuited
     * because `combine` over an empty list never emits — without it, a user with no pets would sit on
     * the loading state forever and never see the empty state inviting them to add one.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val petCareFlow: Flow<List<PetCareData>> =
        petRepository.observePetsForOwner(userId).flatMapLatest { pets ->
            if (pets.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    pets.map { pet ->
                        combine(
                            careRoutineRepository.observeActiveRoutinesForPet(pet.id),
                            careTaskRepository.observeTasksForPet(pet.id),
                            expenseRepository.observeExpensesForPet(pet.id)
                        ) { routines, tasks, expenses -> PetCareData(pet, routines, tasks, expenses) }
                    }
                ) { perPet -> perPet.toList() }
            }
        }

    /**
     * The completion history the care streak is drawn from.
     *
     * The query starts from midnight [HomeDashboard.STREAK_DAYS] days ago, worked out once when the
     * ViewModel is created. That start does not move if the screen is left open for days, which is
     * harmless: the query is "everything *since* then", so it still includes every newer day the
     * streak could ask about — it only carries a few extra old rows.
     */
    private val completionsFlow = careTaskRepository.observeCompletionsForOwnerSince(
        ownerId = userId,
        fromMillis = historyWindowStart()
    )

    /**
     * The current time, re-emitted at the start of every minute.
     *
     * It waits for the *remainder* of the current minute rather than a flat 60 seconds, so the clock on
     * screen turns over when the phone's own clock does instead of up to a minute later. It is a cold
     * flow collected through [uiState]'s `WhileSubscribed`, so it stops ticking a few seconds after the
     * screen goes into the background and costs nothing while the app is closed.
     *
     * It also fixes something the old dashboard had to work around: because every emission recomputes
     * "today", a screen left open past midnight rolls over to the new day by itself.
     */
    private val clock: Flow<Long> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            delay(MINUTE_MILLIS - now % MINUTE_MILLIS)
        }
    }

    /**
     * The full state of the dashboard.
     *
     * [combine] recalculates whenever any input emits — a task is ticked on the Tasks tab, an expense is
     * logged, the minute turns over — and there is no manual refresh anywhere, because Room and the
     * clock drive these flows. [flowOn] moves that calculation off the main thread: working out a week
     * of streak days across several pets is quick, but it runs every minute and has no business
     * competing with scrolling.
     *
     * [stateIn] turns the result into a hot [StateFlow] that remembers the latest value, so a rotation
     * re-attaches to data already there instead of re-querying and flashing empty. `WhileSubscribed`
     * keeps it alive for five seconds after the last observer leaves — long enough to survive a
     * rotation, short enough that a backgrounded screen stops doing work.
     */
    val uiState: StateFlow<HomeUiState> = combine(
        userRepository.observeUser(userId),
        petCareFlow,
        completionsFlow,
        clock
    ) { user, petCare, completions, now ->
        HomeDashboard.build(
            userFullName = user?.fullName,
            petCare = petCare,
            completions = completions,
            nowMillis = now
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState()
        )

    /**
     * Ticks a task off from the "Mark done" button, or clears it again from the Snackbar's undo.
     *
     * Only the id and the new state are passed; the repository decides the timestamp and writes the
     * streak history, so every screen records completion the same way. The dashboard then updates by
     * itself, because the Room queries re-emit as soon as the row changes — which is also why the Tasks
     * tab shows the tick without either screen telling the other.
     */
    fun setTaskDone(taskId: Long, isDone: Boolean) {
        viewModelScope.launch {
            careTaskRepository.setTaskCompleted(taskId, isDone)
        }
    }

    companion object {

        private const val MINUTE_MILLIS = 60_000L

        /** Midnight [HomeDashboard.STREAK_DAYS] days before today — see [completionsFlow]. */
        private fun historyWindowStart(): Long {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = CareSchedule.startOfToday()
                add(Calendar.DAY_OF_MONTH, -HomeDashboard.STREAK_DAYS)
            }
            return calendar.timeInMillis
        }

        /**
         * Builds the ViewModel for a particular account.
         *
         * A function rather than a property because the user id is only known at runtime and has to be
         * captured here to reach the constructor.
         */
        fun factory(userId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                HomeViewModel(
                    userRepository = application.userRepository,
                    petRepository = application.petRepository,
                    careRoutineRepository = application.careRoutineRepository,
                    careTaskRepository = application.careTaskRepository,
                    expenseRepository = application.expenseRepository,
                    syncManager = application.syncManager,
                    userId = userId
                )
            }
        }
    }
}
