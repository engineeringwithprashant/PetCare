package com.prash.petcare.ui.session

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.repository.AlertRepository
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.util.CareSchedule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Remembers which account is signed in, for as long as the app is open. */
// flatMapLatest is still marked experimental in kotlinx.coroutines but is long-settled API.
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val userRepository: UserRepository,
    private val careRoutineRepository: CareRoutineRepository,
    private val careTaskRepository: CareTaskRepository,
    private val alertRepository: AlertRepository
) : ViewModel() {

    /** The signed-in account's id, or [NO_USER_ID] when nobody is signed in. */
    val userId: StateFlow<Long> = savedStateHandle.getStateFlow(KEY_USER_ID, NO_USER_ID)

    /** True once an account has been signed in and not yet signed out. */
    val isSignedIn: Boolean get() = userId.value != NO_USER_ID

    /** The signed-in account's profile photo, as a path on disk, or null when there is none. */
    val profilePhotoPath: StateFlow<String?> = userId
        .flatMapLatest { id ->
            if (id == NO_USER_ID) flowOf(null) else userRepository.observeUser(id).map { it?.photoPath }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** How many care tasks are due today and still not ticked off — the number on the Tasks tab's badge. */
    /** How many alerts the user has not read yet — the number on the bell in the shared header. */
    val unreadAlertCount: StateFlow<Int> = userId
        .flatMapLatest { id ->
            if (id == NO_USER_ID) {
                flowOf(0)
            } else {
                alertRepository.observeAlerts(id).map { alerts -> alerts.count { !it.isRead } }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val openTasksToday: StateFlow<Int> = userId
        .flatMapLatest { id ->
            if (id == NO_USER_ID) {
                flowOf(0)
            } else {
                todayWindow().flatMapLatest { (dayStart, dayEnd) ->
                    combine(
                        careRoutineRepository.observeActiveRoutinesForOwner(id),
                        careTaskRepository.observeTasksForOwner(id)
                    ) { routines, tasks ->
                        CareSchedule.tasksDueOn(routines, tasks, dayStart)
                            .count { task -> !CareSchedule.isDoneOn(task, dayStart, dayEnd) }
                    }
                }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Today's first and last millisecond, re-emitted when the day changes. */
    private fun todayWindow(): Flow<Pair<Long, Long>> = flow {
        while (true) {
            val dayStart = CareSchedule.startOfToday()
            val dayEnd = CareSchedule.endOfToday()
            emit(dayStart to dayEnd)
            // +1 to land on the first millisecond of the new day rather than the last of the old one.
            delay(dayEnd - System.currentTimeMillis() + 1)
        }
    }

    /** Records a successful sign-in or sign-up. */
    fun signIn(userId: Long) {
        savedStateHandle[KEY_USER_ID] = userId
    }

    /** Forgets the signed-in account. */
    fun signOut() {
        savedStateHandle[KEY_USER_ID] = NO_USER_ID
        userRepository.signOut()
    }

    companion object {

        /** The id meaning "nobody is signed in". Matches the app's convention for absent ids. */
        const val NO_USER_ID = -1L

        /** Key under which the id is saved. */
        private const val KEY_USER_ID = "session_user_id"

        /** Builds the ViewModel with its SavedStateHandle and repository. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                SessionViewModel(
                    createSavedStateHandle(),
                    application.userRepository,
                    application.careRoutineRepository,
                    application.careTaskRepository,
                    application.alertRepository
                )
            }
        }
    }
}
