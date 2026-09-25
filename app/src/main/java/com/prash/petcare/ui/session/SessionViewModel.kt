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
 * It holds an id, and two *derived* values read straight from Room, both of them drawn by the app's
 * chrome rather than by any one screen: the profile photo in the shared header
 * ([profilePhotoPath]), and the number of care tasks still open today, which the navigation bar
 * badges the Tasks tab with ([openTasksToday]). The user's name, email and pets are read from Room
 * by the screen that needs them, so there is no second copy of the account here to drift out of date
 * when the profile is edited or synced.
 *
 * **Why it has a [Factory].** Signing out also has to sign the account out of Firebase
 * Authentication, which needs [UserRepository], and the default factory cannot supply one. Every
 * screen therefore asks for this ViewModel with `by activityViewModels { SessionViewModel.Factory }`.
 * They all get the same Activity-scoped instance; the factory only matters to whichever screen asks
 * first, but using it everywhere means it does not matter which one that is.
 *
 * @param userRepository Signs out of Firebase, and supplies the account row the header's photo is
 *   read from. Both go through the repository rather than a DAO, as the MVVM chain requires.
 * @param careRoutineRepository Supplies the repeat schedules the badge count is projected from —
 *   which days each routine runs. See [openTasksToday] for why a date cannot answer that alone.
 * @param careTaskRepository Supplies the tasks those schedules are matched against. Together the two
 *   give the count behind the navigation bar's Tasks badge — the second value here that belongs to
 *   the app's chrome rather than to any screen.
 * @param alertRepository Supplies the alerts behind the number on the header's bell — the third such
 *   value, and drawn on more screens than either of the others.
 */
// flatMapLatest is still marked experimental in kotlinx.coroutines but is long-settled API, used
// here for the reason given on profilePhotoPath: the photo query has to follow the signed-in id.
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val userRepository: UserRepository,
    private val careRoutineRepository: CareRoutineRepository,
    private val careTaskRepository: CareTaskRepository,
    private val alertRepository: AlertRepository
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
     * How many care tasks are due today and still not ticked off — the number on the Tasks tab's
     * badge.
     *
     * **Why this lives here, like the profile photo above and for the same reason.** It is drawn by
     * the navigation bar, which belongs to no screen: it is visible on all five tabs and on every
     * detail screen under them. The Tasks tab itself already counts its own work, but the badge has
     * to be right while the user is looking at the dashboard, or a pet, or their spending — so
     * asking the Tasks screen would mean keeping a screen alive to answer a question about a screen
     * nobody is on. The Activity-scoped ViewModel every screen already shares is the level the
     * figure belongs at.
     *
     * **Why it is live rather than fetched.** Both inputs are Room queries, so ticking a task off on
     * the Tasks tab, marking one done from the dashboard's "next up" card, adding or pausing a
     * routine, or a sync writing rows in from another device all move the badge by themselves.
     * Nothing pushes an update and nothing has to remember to.
     *
     * **Why the count cannot be a SQL `WHERE dueAt` clause, tempting as that is.** A repeating task
     * is one row, not one row per occurrence: its stored date is the day the routine began, and
     * whether it falls due *today* has to be projected from that routine's repeat schedule. A task
     * repeating daily since last week is due today with a `dueAt` of last Tuesday, and its
     * `isCompleted` flag is still set from the last time it was ticked. So the rule is
     * [CareSchedule.tasksDueOn] for what today holds and [CareSchedule.isDoneOn] for what has
     * actually been done *today* — the same two calls the dashboard's "things left for today" and
     * the Tasks tab's own list are built from. A date filter here would have read zero on a day the
     * dashboard was reporting four, which is the kind of disagreement a badge exists to avoid.
     *
     * Routines and tasks are fetched for the whole account in one query each rather than per pet.
     * Routine ids are unique across the database and a task names its routine, so matching the two
     * whole lists gives exactly what matching them pet by pet would, without a query per animal.
     *
     * **Two nested [flatMapLatest]s, for the two things that can change underneath the pair.** The
     * account, as everywhere else in this class — switching users has to drop the previous user's
     * rows rather than keep counting them. And *today*: the day is worked out from the device clock,
     * so it has to be recomputed when the date rolls over, which is what [todayWindow] does.
     *
     * `distinctUntilChanged` means an edit that leaves the total alone — retitling a task, moving one
     * an hour later — does not redraw the badge.
     */
    /**
     * How many alerts the user has not read yet — the number on the bell in the shared header.
     *
     * **Why this lives here, like the two values above and for the same reason.** It is drawn by the
     * app's chrome rather than by any one screen: the bell is in the header of a dozen screens, and
     * the count has to be right on every one of them. The notifications screen counts the same thing
     * for its own headline, but asking that screen would mean keeping it alive to answer a question
     * about a screen nobody is on.
     *
     * **It cannot drift from the list behind it**, because both come from the single
     * [AlertRepository] the Application holds: the bell counts the unread items of exactly the list
     * the notifications screen will show. That was the point of moving the derivation out of that
     * screen's ViewModel.
     *
     * `flatMapLatest` re-subscribes when the account changes, so signing out and in as somebody else
     * cannot leave the first user's count on the bell. `distinctUntilChanged` means a rebuild that
     * leaves the total alone — an alert's wording changing as a due date approaches — does not redraw
     * every header. `WhileSubscribed` stops the queries when no header is on screen, with the same
     * five-second grace period as the others so a rotation does not re-derive from scratch.
     */
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

    /**
     * Today's first and last millisecond, re-emitted when the day changes.
     *
     * A badge reading "3 left today" on a phone left on the kitchen counter overnight would still
     * say three the next morning, counting yesterday's tasks, if the window were worked out once. So
     * this emits the current day and then waits exactly as long as is left of it before emitting the
     * next — no polling, and no clock ticking every minute for a value that changes once a day.
     *
     * A cold flow, collected only through [openTasksToday]'s `WhileSubscribed`, so it stops with the
     * rest of the chain when no bar is on screen and costs nothing while the app is closed.
     */
    private fun todayWindow(): Flow<Pair<Long, Long>> = flow {
        while (true) {
            val dayStart = CareSchedule.startOfToday()
            val dayEnd = CareSchedule.endOfToday()
            emit(dayStart to dayEnd)
            // +1 to land on the first millisecond of the new day rather than the last of the old one.
            delay(dayEnd - System.currentTimeMillis() + 1)
        }
    }

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
