package com.prash.petcare.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.local.entity.User
import com.prash.petcare.data.repository.UserRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Supplies the signed-in user to [HomeFragment].
 *
 * Deliberately minimal while the home screen is a placeholder, but it already exercises the full
 * MVVM chain — `Fragment` → `ViewModel` → `Repository` → `DAO` → Room — which confirms the data
 * layer works end to end before the pet list is built on top of it.
 *
 * @param userRepository Data source, injected so a test can supply a fake.
 * @param userId Which account to show, passed in as a navigation argument.
 */
class HomeViewModel(
    userRepository: UserRepository,
    userId: Long
) : ViewModel() {

    /**
     * The signed-in user, or null until the first database read completes.
     *
     * `stateIn` converts the repository's cold [kotlinx.coroutines.flow.Flow] into a hot
     * [StateFlow] that holds the latest value. Without it every rotation would re-run the query
     * from scratch and the screen would flash empty; with it the fragment re-attaches to a value
     * that is already there.
     *
     * `WhileSubscribed(5_000)` keeps the database query alive for five seconds after the last
     * observer goes away. That window is long enough to cover a rotation — so the query is not
     * torn down and immediately restarted — but short enough that a genuinely backgrounded screen
     * stops doing work.
     */
    val user: StateFlow<User?> = userRepository.observeUser(userId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    companion object {

        /**
         * Builds the ViewModel for a particular account.
         *
         * Unlike [com.prash.petcare.ui.auth.AuthViewModel.Factory] this is a function rather than
         * a property, because the user id is only known at runtime and has to be captured here
         * and handed to the constructor.
         */
        fun factory(userId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                HomeViewModel(application.userRepository, userId)
            }
        }
    }
}
