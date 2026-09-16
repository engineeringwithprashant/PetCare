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
import com.prash.petcare.data.repository.WeatherRepository
import com.prash.petcare.data.repository.WeatherState
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.prash.petcare.data.local.SettingsStore
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
 * @param weatherRepository Source of the live temperature on the walk-window card. The app's only
 *   non-Room, non-Firestore data source; the ViewModel sees a state, never a network call.
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
    private val weatherRepository: WeatherRepository,
    private val settingsStore: SettingsStore,
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
     * The temperature on the walk-window card, passed straight through from its repository.
     *
     * Kept out of [uiState] for the same reason [syncStatus] is, and the reason is worth stating
     * because the card sits in the middle of the dashboard rather than off to one side. The weather
     * is not derived from the user's pets: it changes on its own schedule, it arrives from the
     * network long after the Room queries have returned, and folding it into the `combine` above
     * would rebuild the streak, the insight and every pet card the moment a temperature landed.
     *
     * The fragment draws the card from this *and* from [HomeUiState.walkWindow] together, because
     * the scheduled walk remains the fallback whenever there is no reading — see
     * HomeFragment.renderWalkWindow.
     */
    val weather: StateFlow<WeatherState> = weatherRepository.state

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
    /**
     * Which currency symbol the "SPENT" stat card writes its figure with.
     *
     * Published separately from [uiState] rather than folded into it, for the same reason the sync
     * status is: the dashboard's data and a display preference change on completely different
     * rhythms, and rebuilding a week of streak days because a symbol changed would be work for
     * nothing. The fragment collects both and redraws the one card.
     */
    val currencySymbol: StateFlow<String> = settingsStore.currency
        .map { it.symbol }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = settingsStore.currency.value.symbol
        )

    val uiState: StateFlow<HomeUiState> = combine(
        userRepository.observeUser(userId),
        petCareFlow,
        completionsFlow,
        clock,
        // The week-start preference is one more input rather than something read inside the builder,
        // so changing it on the profile screen redraws the streak by exactly the path a ticked task
        // takes — and HomeDashboard stays a pure function that reads no settings of its own.
        settingsStore.weekStart
    ) { user, petCare, completions, now, weekStart ->
        HomeDashboard.build(
            userFullName = user?.fullName,
            petCare = petCare,
            completions = completions,
            nowMillis = now,
            firstDayOfWeek = weekStart.calendarDay
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

    /**
     * Asks for an up-to-date temperature for the walk-window card.
     *
     * Called by the fragment each time the home tab comes into view, and again the moment location
     * permission is granted. It is deliberately cheap to call: the repository serves its cached
     * reading without a request while that reading is under half an hour old, so returning to the
     * tab repeatedly costs nothing — see [WeatherRepository.refresh].
     *
     * Pushed rather than pulled: unlike the Room queries, there is no stream that re-emits when the
     * weather changes, so something has to ask. `viewModelScope` is what lets the request outlive a
     * rotation that happens while it is in flight.
     */
    fun refreshWeather() {
        viewModelScope.launch {
            weatherRepository.refresh()
        }
    }

    /** Backs [markLocationAsked]. Not exposed: the only useful operation on it is the one below. */
    private var hasAskedForLocation = false

    /**
     * Claims the single chance to ask for location permission, returning true to whoever gets it.
     *
     * The fragment needs to ask once and only once, and "once" cannot be tracked in the fragment
     * itself: a rotation destroys it and a new one would ask all over again, so the user would face
     * the same dialog every time they turned their phone. A ViewModel outlives exactly those
     * recreations, which makes it the right place for a fact about this *visit* to the screen rather
     * than this instance of the view.
     *
     * A plain boolean rather than a flow: nothing observes it, and it is read once per visit from the
     * main thread. It holds no Android type, so this does not compromise what makes the ViewModel
     * testable.
     */
    fun markLocationAsked(): Boolean {
        if (hasAskedForLocation) return false
        hasAskedForLocation = true
        return true
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
                    weatherRepository = application.weatherRepository,
                    settingsStore = application.settingsStore,
                    syncManager = application.syncManager,
                    userId = userId
                )
            }
        }
    }
}
