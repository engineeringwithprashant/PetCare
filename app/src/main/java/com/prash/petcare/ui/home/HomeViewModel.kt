package com.prash.petcare.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.TaskCategory
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.data.sync.SyncManager
import com.prash.petcare.data.sync.SyncStatus
import com.prash.petcare.util.CareSchedule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One pet as the dashboard shows it: the animal, plus how its care is going today.
 *
 * The counts are held here rather than being looked up by the card that draws them, so the whole
 * dashboard is rendered from one already-consistent snapshot — a card can never report a figure
 * from a different moment than the checklist below it.
 *
 * @property pet The animal itself; the card reads its name, species, breed and photo.
 * @property tasksDueToday How many care tasks fall due today for this pet.
 * @property tasksDoneToday How many of those have been ticked off.
 */
data class PetSummary(
    val pet: Pet,
    val tasksDueToday: Int,
    val tasksDoneToday: Int
) {
    /** How many are still to be done — what the card's pastel pill reports. */
    val tasksOutstanding: Int get() = tasksDueToday - tasksDoneToday
}

/**
 * One row of the dashboard's "today" checklist.
 *
 * Carries the pet's *name* rather than the whole [Pet]: the row shows which animal a task belongs
 * to, and nothing more of the pet is needed to draw it.
 *
 * @property isDoneToday Whether it has been completed *today* specifically, worked out from the
 *   task's completion timestamp rather than read from a flag — see [CareSchedule.isDoneOn].
 */
data class TodayTask(
    val task: CareTask,
    val petName: String,
    val isDoneToday: Boolean
)

/**
 * One entry in the "Upcoming appointments" card: the next time a vet visit comes round.
 *
 * @property dueAtMillis When it next happens. Calculated rather than stored — a care task is a
 *   repeating template with no dates of its own, so the moment is derived from its routine's
 *   schedule by [CareSchedule.nextOccurrence].
 */
data class UpcomingAppointment(
    val task: CareTask,
    val petName: String,
    val dueAtMillis: Long
)

/**
 * Everything the dashboard displays, gathered into one object.
 *
 * The fragment receives a single state rather than several separate values, which means the
 * greeting, the pet cards, the checklist and the appointments can never be drawn from different
 * moments in time — they always arrive together, already consistent.
 *
 * @property userName Name of the signed-in user; empty until the first database read returns.
 * @property pets The user's pets with today's progress against each, newest first.
 * @property todayTasks Every care task due today across all pets, earliest first.
 * @property appointments The next few vet visits coming up, soonest first.
 * @property isLoading True until the first results arrive, so the screen can tell "still loading"
 *   apart from "genuinely has no pets" — without it, the empty state would flash on every launch.
 */
data class HomeUiState(
    val userName: String = "",
    val pets: List<PetSummary> = emptyList(),
    val todayTasks: List<TodayTask> = emptyList(),
    val appointments: List<UpcomingAppointment> = emptyList(),
    val isLoading: Boolean = true
) {
    /** True when the empty state should replace the dashboard. */
    val hasNoPets: Boolean get() = !isLoading && pets.isEmpty()

    /** How many of today's tasks are ticked off, across every pet. */
    val tasksDoneToday: Int get() = todayTasks.count { it.isDoneToday }

    /** How many fall due today in total, across every pet. */
    val tasksDueToday: Int get() = todayTasks.size

    /** How many are still outstanding — the figure the dashboard's headline reports. */
    val tasksLeftToday: Int get() = tasksDueToday - tasksDoneToday

    /**
     * The next piece of care still to be done today, or null when there is none left.
     *
     * "Next" is simply the first one not yet ticked off, because [todayTasks] is already ordered by
     * time of day — see how that list is built below. Deriving it here rather than in the fragment
     * keeps the screen free of even this much logic, and means the rule lives beside the sort that
     * makes it true: change the ordering and this follows automatically, where a copy of the same
     * search in the view would quietly start naming the wrong task.
     *
     * Null covers two different days — one where everything is done, and one where nothing was
     * scheduled — which the fragment tells apart by looking at [tasksDueToday].
     */
    val nextUpTask: TodayTask? get() = todayTasks.firstOrNull { !it.isDoneToday }
}

/**
 * Supplies the dashboard with its data.
 *
 * Sits in the middle of the MVVM chain: it reads from four repositories and publishes one
 * [HomeUiState] for [HomeFragment] to render. It holds no views and no Context, so it survives
 * rotation with its data intact.
 *
 * **Where "today's care" comes from.** Care tasks are stored as repeating templates rather than one
 * row per day, so there is no table of today's items to query — the day has to be worked out. This
 * class does it the same way the checklist screen does: take each pet's *active* routines, keep the
 * ones whose schedule lands on today, then keep the tasks belonging to those routines. Running the
 * identical calculation is what guarantees the dashboard and the Tasks tab never disagree about
 * what is due; a second, simpler rule here — "tasks whose stored due date is today", say — would
 * quietly show a different list, because a template's stored date is its *first* occurrence and
 * every repeat after that is derived.
 *
 * **Why the data is gathered per pet.** The repositories expose routines and tasks per pet, so the
 * dashboard subscribes once per animal and merges the results — see [petCareFlow]. That is a handful
 * of queries for a handful of pets, and it means this screen needed no new database query written
 * for it.
 *
 * @param userRepository Source of the signed-in user's profile.
 * @param petRepository Source of the pet list.
 * @param careRoutineRepository Source of the schedules that decide what runs today.
 * @param careTaskRepository Source of the tasks, and where a tick on the dashboard is written back.
 * @param syncManager The background sync. This screen is the only one that touches it, for the two
 *   reasons described in [syncStatus] and the `init` block below. It is a data-layer object holding
 *   no Android view types, so depending on it here does not compromise what makes a ViewModel
 *   testable and rotation-proof.
 * @param userId Which account to show, read from the session — see
 *   [com.prash.petcare.ui.session.SessionViewModel].
 */
class HomeViewModel(
    userRepository: UserRepository,
    petRepository: PetRepository,
    careRoutineRepository: CareRoutineRepository,
    private val careTaskRepository: CareTaskRepository,
    syncManager: SyncManager,
    userId: Long
) : ViewModel() {

    /**
     * Tells the sync layer which account is now signed in.
     *
     * This screen is where a session effectively begins: the app keeps no persisted login, so it
     * opens on the sign-in screen and the dashboard is the first place the account is known. From
     * here the sync runs on its own.
     *
     * In `init` rather than in the fragment because a ViewModel is created once per screen and
     * survives rotation, whereas the fragment is rebuilt — so this happens exactly once per visit
     * rather than every time the device is turned.
     */
    init {
        syncManager.onUserSignedIn(userId)
    }

    /**
     * What the sync is doing, passed straight through for the indicator to display.
     *
     * Kept out of [uiState] on purpose. That state is assembled from database queries and describes
     * the user's pets; sync progress describes the app itself, changes on a completely different
     * rhythm, and folding it in would mean rebuilding the whole dashboard every time the status
     * moved from "syncing" to "synced".
     */
    val syncStatus: StateFlow<SyncStatus> = syncManager.status

    /**
     * Each pet together with the routines and tasks the dashboard needs in order to work out its
     * day.
     *
     * [flatMapLatest] is what makes this possible: the list of pets is itself a stream, so the set
     * of per-pet queries has to be rebuilt whenever a pet is added or removed. "Latest" is the
     * important half of the name — when a new pet list arrives, the subscriptions opened for the
     * previous one are cancelled, so a deleted pet's queries stop rather than accumulating.
     *
     * The inner [combine] over a *list* of flows yields one emission holding all of them, which is
     * what lets the state below be assembled from a single consistent snapshot rather than from
     * pets whose data arrived one at a time.
     *
     * The empty case is short-circuited because `combine` over an empty list never emits at all —
     * so without this, a user with no pets would sit on the loading state forever and never reach
     * the empty state that invites them to add one.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val petCareFlow: Flow<List<PetCare>> =
        petRepository.observePetsForOwner(userId).flatMapLatest { pets ->
            if (pets.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    pets.map { pet ->
                        combine(
                            careRoutineRepository.observeActiveRoutinesForPet(pet.id),
                            careTaskRepository.observeTasksForPet(pet.id)
                        ) { routines, tasks -> PetCare(pet, routines, tasks) }
                    }
                ) { perPet -> perPet.toList() }
            }
        }

    /**
     * The full state of the dashboard.
     *
     * [combine] merges the user's profile with the per-pet care above: whenever either emits — a pet
     * is added, a task is ticked off, the user renames themselves — this recalculates and the screen
     * updates. There is no manual refresh anywhere in the app because Room drives these flows.
     *
     * [stateIn] then converts the cold result into a hot [StateFlow] that remembers the latest
     * value, so a rotation re-attaches to data that is already there instead of re-querying and
     * flashing empty. `WhileSubscribed(5_000)` keeps the queries alive for five seconds after the
     * last observer leaves — long enough to survive a rotation, short enough that a backgrounded
     * screen stops doing work.
     */
    val uiState: StateFlow<HomeUiState> = combine(
        userRepository.observeUser(userId),
        petCareFlow
    ) { user, petCare ->

        // Recomputed on every emission rather than captured once, so the boundaries stay correct if
        // the screen is still open after midnight.
        val todayStart = CareSchedule.startOfToday()
        val todayEnd = CareSchedule.endOfToday()
        val now = System.currentTimeMillis()

        // Worked out once and reused by all three sections below, so the cards, the checklist and
        // the appointment list are guaranteed to be describing the same set of tasks.
        val today = petCare.map { it.tasksDueOn(todayStart, todayEnd) }

        HomeUiState(
            userName = user?.fullName.orEmpty(),
            pets = today.map { it.toSummary() },
            todayTasks = today
                .flatMap { it.rows }
                // Earliest first across every pet, so the checklist reads as the day itself rather
                // than as one animal after another. A task with no recorded time sorts last, which
                // is what the large fallback value achieves.
                .sortedWith(
                    compareBy(
                        { it.task.timeOfDayMinutes ?: Int.MAX_VALUE },
                        { it.petName },
                        { it.task.title }
                    )
                ),
            appointments = buildAppointments(petCare, now),
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState()
    )

    /**
     * Ticks a task off from the dashboard, or clears it again.
     *
     * Only the task id and the new state are passed; the repository decides the timestamp, so every
     * screen records completion the same way. The dashboard then updates itself, because the
     * underlying Room queries re-emit as soon as the row changes — which is also why a tick here is
     * reflected on the Tasks tab without either screen telling the other.
     */
    fun setTaskDone(taskId: Long, isDone: Boolean) {
        viewModelScope.launch {
            careTaskRepository.setTaskCompleted(taskId, isDone)
        }
    }

    /**
     * Finds the next few vet visits across every pet.
     *
     * Only [TaskCategory.VET_VISIT] tasks are considered, because that is what the card claims to
     * show — an appointment is a thing with a date that somebody else is also expecting you at, and
     * a daily feed is not that however far ahead it is scheduled.
     *
     * Each candidate's next occurrence is calculated from its owning routine's schedule, since a
     * task is a repeating template and carries no future dates of its own. Anything whose routine
     * has been deleted or paused drops out: [PetCare.activeRoutines] holds only active ones, so a
     * task whose routine is not in that map has no schedule to project forward and is skipped.
     *
     * Capped at [MAX_APPOINTMENTS]. The card is a glance at what is coming, not a diary — a
     * complete list belongs on the pet's own routines, and an unbounded one here would push the rest
     * of the dashboard off the screen for anyone with several animals.
     */
    private fun buildAppointments(petCare: List<PetCare>, now: Long): List<UpcomingAppointment> =
        petCare
            .flatMap { care ->
                val routinesById = care.activeRoutines.associateBy { it.id }

                care.tasks
                    .filter { it.category == TaskCategory.VET_VISIT }
                    .mapNotNull { task ->
                        val routine = task.routineId?.let { routinesById[it] } ?: return@mapNotNull null
                        val timeOfDay = task.timeOfDayMinutes ?: return@mapNotNull null

                        val nextAt = CareSchedule.nextOccurrence(
                            routineStartDate = routine.startDate,
                            repeatInterval = routine.repeatInterval,
                            repeatUnit = routine.repeatUnit,
                            timeOfDayMinutes = timeOfDay,
                            afterMillis = now
                        ) ?: return@mapNotNull null

                        UpcomingAppointment(
                            task = task,
                            petName = care.pet.name,
                            dueAtMillis = nextAt
                        )
                    }
            }
            .sortedBy { it.dueAtMillis }
            .take(MAX_APPOINTMENTS)

    /**
     * One pet's raw care data, as gathered by [petCareFlow].
     *
     * Private because it is a step in the calculation rather than anything the screen renders: the
     * fragment only ever sees the [PetSummary] and [TodayTask] values derived from it.
     */
    private data class PetCare(
        val pet: Pet,
        val activeRoutines: List<CareRoutine>,
        val tasks: List<CareTask>
    ) {

        /**
         * Narrows this pet's tasks down to the ones actually due on the given day.
         *
         * The rule itself — a task runs on a day only if its routine does — lives in
         * [CareSchedule.tasksDueOn], shared with the Pets tab so the two screens always agree.
         */
        fun tasksDueOn(dayStart: Long, dayEnd: Long): PetToday {
            val rows = CareSchedule.tasksDueOn(activeRoutines, tasks, dayStart)
                .map { task ->
                    TodayTask(
                        task = task,
                        petName = pet.name,
                        isDoneToday = CareSchedule.isDoneOn(task, dayStart, dayEnd)
                    )
                }

            return PetToday(pet, rows)
        }
    }

    /** One pet's care for today, after [PetCare.tasksDueOn] has narrowed it down. */
    private data class PetToday(val pet: Pet, val rows: List<TodayTask>) {

        fun toSummary(): PetSummary = PetSummary(
            pet = pet,
            tasksDueToday = rows.size,
            tasksDoneToday = rows.count { it.isDoneToday }
        )
    }

    companion object {

        /** How many upcoming vet visits the dashboard's appointments card shows at most. */
        private const val MAX_APPOINTMENTS = 3

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
                HomeViewModel(
                    userRepository = application.userRepository,
                    petRepository = application.petRepository,
                    careRoutineRepository = application.careRoutineRepository,
                    careTaskRepository = application.careTaskRepository,
                    syncManager = application.syncManager,
                    userId = userId
                )
            }
        }
    }
}
