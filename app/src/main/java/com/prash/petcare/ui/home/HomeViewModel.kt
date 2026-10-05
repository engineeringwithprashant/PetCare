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

/** Supplies the dashboard with its data. */
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

    /** Tells the sync layer which account is now signed in. */
    init {
        syncManager.onUserSignedIn(userId)
    }

    /** What the sync is doing, passed straight through for the "All synced" line. */
    val syncStatus: StateFlow<SyncStatus> = syncManager.status

    /** The temperature on the walk-window card, passed straight through from its repository. */
    val weather: StateFlow<WeatherState> = weatherRepository.state

    /** Each pet together with its active routines, its tasks and its expenses. */
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

    /** The completion history the care streak is drawn from. */
    private val completionsFlow = careTaskRepository.observeCompletionsForOwnerSince(
        ownerId = userId,
        fromMillis = historyWindowStart()
    )

    /** The current time, re-emitted at the start of every minute. */
    private val clock: Flow<Long> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            delay(MINUTE_MILLIS - now % MINUTE_MILLIS)
        }
    }

    /** The full state of the dashboard. */
    /** Which currency symbol the "SPENT" stat card writes its figure with. */
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
        // The week-start preference is one more input rather than something read inside the builder.
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

    /** Ticks a task off from the "Mark done" button, or clears it again from the Snackbar's undo. */
    fun setTaskDone(taskId: Long, isDone: Boolean) {
        viewModelScope.launch {
            careTaskRepository.setTaskCompleted(taskId, isDone)
        }
    }

    /** Asks for an up-to-date temperature for the walk-window card. */
    fun refreshWeather() {
        viewModelScope.launch {
            weatherRepository.refresh()
        }
    }

    /** Backs [markLocationAsked]. Not exposed: the only useful operation on it is the one below. */
    private var hasAskedForLocation = false

    /** Claims the single chance to ask for location permission, returning true to whoever gets it. */
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

        /** Builds the ViewModel for a particular account. */
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
