package com.prash.petcare.ui.session

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.repository.UserRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * Remembers which account is signed in, for as long as the app is open.
 *
 * **Why this exists.** The signed-in user used to travel as a navigation argument on the home
 * screen, passed along by whichever screen opened the next one. That stopped working the moment the
 * app gained a bottom navigation bar: NavigationUI moves between tabs by destination id alone and
 * has nowhere to attach an argument, so three of the four tabs would have arrived with no idea
 * whose pets to show. Something above the individual screens has to hold the account, and a
 * ViewModel scoped to the Activity is exactly that — every fragment reaches the same instance
 * through `by activityViewModels()`, and it outlives each of them.
 *
 * **Why SavedStateHandle rather than a plain property.** An ordinary ViewModel survives a rotation
 * but not the process being killed, which Android does routinely to a backgrounded app. Coming back
 * to a killed app restores the navigation back stack but would give a plain ViewModel a fresh,
 * empty one — the user would find themselves on a signed-in screen belonging to nobody. Values put
 * in a [SavedStateHandle] are written to the same saved-instance bundle the back stack is, so the
 * two are restored together or not at all.
 *
 * It holds an id and nothing else. The user's name, email and pets are read from Room by the screen
 * that needs them, so there is no second copy of the account here to drift out of date when the
 * profile is edited or synced.
 *
 * **Why it has a [Factory].** Signing out also has to sign the account out of Firebase
 * Authentication, which needs [UserRepository], and the default factory cannot supply one. Every
 * screen therefore asks for this ViewModel with `by activityViewModels { SessionViewModel.Factory }`.
 * They all get the same Activity-scoped instance; the factory only matters to whichever screen asks
 * first, but using it everywhere means it does not matter which one that is.
 *
 * @param userRepository Used only to sign out of Firebase.
 */
class SessionViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val userRepository: UserRepository
) : ViewModel() {

    /**
     * The signed-in account's id, or [NO_USER_ID] when nobody is signed in.
     *
     * A [StateFlow] rather than a plain value so a screen can react to signing out — when the id
     * returns to [NO_USER_ID] the app is on its way back to the sign-in screen, and any screen
     * still collecting stops querying for a user who is no longer there.
     */
    val userId: StateFlow<Long> = savedStateHandle.getStateFlow(KEY_USER_ID, NO_USER_ID)

    /** True once an account has been signed in and not yet signed out. */
    val isSignedIn: Boolean get() = userId.value != NO_USER_ID

    /**
     * Records a successful sign-in or sign-up.
     *
     * Called by the auth screens *before* they navigate, so the account is already known by the
     * time the destination's own ViewModel is constructed and asks for it.
     */
    fun signIn(userId: Long) {
        savedStateHandle[KEY_USER_ID] = userId
    }

    /**
     * Forgets the signed-in account.
     *
     * The app keeps no persisted login of its own, so signing out is forgetting the id, signing out
     * of Firebase Authentication (so Firestore requests are no longer made as this account), and
     * returning to the sign-in screen. Nothing in the database is touched:
     * the user's pets and care history are still theirs when they sign back in, and on this shared
     * device another account's data is filtered out by owner id exactly as it always was.
     */
    fun signOut() {
        savedStateHandle[KEY_USER_ID] = NO_USER_ID
        userRepository.signOut()
    }

    companion object {

        /** The id meaning "nobody is signed in". Matches the app's convention for absent ids. */
        const val NO_USER_ID = -1L

        /**
         * Key under which the id is saved.
         *
         * Private, and deliberately namespaced: a [SavedStateHandle] on an Activity shares its
         * bundle with anything else saving state there, so a bare "userId" is a collision waiting
         * to happen.
         */
        private const val KEY_USER_ID = "session_user_id"

        /**
         * Builds the ViewModel with its SavedStateHandle and repository.
         *
         * `createSavedStateHandle()` gives the same handle the default factory would have supplied,
         * so the signed-in id still survives the process being killed.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                SessionViewModel(createSavedStateHandle(), application.userRepository)
            }
        }
    }
}
