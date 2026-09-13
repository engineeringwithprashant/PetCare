package com.prash.petcare.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.local.entity.User
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.data.sync.SyncManager
import com.prash.petcare.data.sync.SyncStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Everything the profile screen displays.
 *
 * @property user The signed-in account, or null while the first database read is in flight.
 */
data class ProfileUiState(val user: User? = null)

/**
 * Supplies the profile screen with the signed-in account's details.
 *
 * Sits in the middle of the MVVM chain: it reads from [UserRepository] and publishes one
 * [ProfileUiState] for [ProfileFragment] to render.
 *
 * The screen is read-only, so this ViewModel has no functions at all — only state. Changing an
 * account's details is not something the app offers, and adding a save path here to match the shape
 * of the other ViewModels would be building a door to a room that does not exist.
 *
 * Note what is deliberately *not* here: the password hash. It is a property of [User] and therefore
 * reaches this class, but no part of the screen reads it, and nothing derived from a credential is
 * ever put on display.
 *
 * @param userRepository Source of the account's profile.
 * @param syncManager The background sync, for the backup status this screen reports. A data-layer
 *   object holding no Android view types, so depending on it here does not compromise what makes a
 *   ViewModel testable and rotation-proof.
 * @param userId Which account to show, read from the session — see
 *   [com.prash.petcare.ui.session.SessionViewModel].
 */
class ProfileViewModel(
    userRepository: UserRepository,
    syncManager: SyncManager,
    userId: Long
) : ViewModel() {

    /**
     * How the backup is getting on.
     *
     * The same status the dashboard shows, deliberately — this is the screen a user opens when they
     * go looking for it after glimpsing it on the dashboard, and a second, differently-worded answer
     * would invite the question of which one is right. Kept out of [uiState] for the same reason it
     * is there: the account's details and the sync's progress change on completely different
     * rhythms.
     */
    val syncStatus: StateFlow<SyncStatus> = syncManager.status

    /**
     * The signed-in account.
     *
     * Read from the database rather than taken from the session, which holds only an id. That is
     * what keeps the name shown here correct after a sync pulls down a change made on another
     * device: Room re-emits and the screen updates itself, with nothing to refresh by hand.
     *
     * `WhileSubscribed(5_000)` keeps the query alive for five seconds after the last observer
     * leaves — long enough to survive a rotation, short enough that a backgrounded screen stops
     * doing work.
     */
    val uiState: StateFlow<ProfileUiState> = userRepository.observeUser(userId)
        .map { user -> ProfileUiState(user = user) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProfileUiState()
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
                ProfileViewModel(
                    userRepository = application.userRepository,
                    syncManager = application.syncManager,
                    userId = userId
                )
            }
        }
    }
}
