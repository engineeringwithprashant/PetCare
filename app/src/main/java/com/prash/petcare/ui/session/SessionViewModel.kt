package com.prash.petcare.ui.session

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.repository.UserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
 * It holds an id, and one *derived* value read straight from Room: the profile photo drawn in the
 * shared header, which belongs to no single screen — see [profilePhotoPath]. The user's name, email
 * and pets are read from Room by the screen that needs them, so there is no second copy of the
 * account here to drift out of date when the profile is edited or synced.
 *
 * **Why it has a [Factory].** Signing out also has to sign the account out of Firebase
 * Authentication, which needs [UserRepository], and the default factory cannot supply one. Every
 * screen therefore asks for this ViewModel with `by activityViewModels { SessionViewModel.Factory }`.
 * They all get the same Activity-scoped instance; the factory only matters to whichever screen asks
 * first, but using it everywhere means it does not matter which one that is.
 *
 * @param userRepository Signs out of Firebase, and supplies the account row the header's photo is
 *   read from. Both go through the repository rather than a DAO, as the MVVM chain requires.
 */
// flatMapLatest is still marked experimental in kotlinx.coroutines but is long-settled API, used
// here for the reason given on profilePhotoPath: the photo query has to follow the signed-in id.
@OptIn(ExperimentalCoroutinesApi::class)
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
     * The signed-in account's profile photo, as a path on disk, or null when there is none.
     *
     * **Why the account's photo lives here when nothing else about the account does.** The rule
     * above still holds — this class keeps an id, and each screen reads the data it needs from Room.
     * The photo is the one exception because it is not any screen's data: it is drawn in the shared
     * header (res/layout/view_tab_header.xml) by the nine screens that show it, none of which
     * otherwise has any reason to know who the user is. Threading it through nine ViewModels would
     * put the same query in nine places; reading it once here, in the one object every screen already
     * shares, puts it in one.
     *
     * **Why it updates by itself.** `observeUser` is a Room Flow, so changing the photo on the
     * profile screen re-emits here, and every header collecting this redraws — no broadcast, no
     * manual refresh, and no waiting for a restart. `flatMapLatest` re-subscribes when the account
     * changes and drops the previous account's rows, so signing out and in as somebody else cannot
     * leave the first user's face in the corner.
     *
     * `WhileSubscribed` stops the database query when no header is on screen (during the sign-in
     * flow, for instance), and the five-second grace period keeps it alive across a rotation so the
     * photo does not visibly reload. `distinctUntilChanged` means an unrelated edit to the account —
     * a new phone number, a sync writing the row back — does not make every header reload the same
     * image.
     */
    val profilePhotoPath: StateFlow<String?> = userId
        .flatMapLatest { id ->
            if (id == NO_USER_ID) flowOf(null) else userRepository.observeUser(id).map { it?.photoPath }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
