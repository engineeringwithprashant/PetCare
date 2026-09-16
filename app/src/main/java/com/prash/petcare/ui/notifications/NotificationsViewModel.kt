package com.prash.petcare.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.local.AlertReadStore
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.TaskCategory
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.ExpenseRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.util.CareSchedule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import java.util.TimeZone

/**
 * Everything the notifications screen displays.
 *
 * @property items The list, headings included, already grouped and in the order it is drawn.
 * @property unreadCount How many alerts have not been read — the "3 new alerts" headline, and what
 *   decides whether the "MARK READ" button has anything to do.
 * @property petCount How many pets the account has, for the header's badge.
 * @property isLoading True until the first results arrive, so the screen can tell "still loading"
 *   apart from "genuinely nothing to show" — without it the empty state flashes up on every launch.
 */
data class NotificationsUiState(
    val items: List<AlertListItem> = emptyList(),
    val unreadCount: Int = 0,
    val petCount: Int = 0,
    val isLoading: Boolean = true
) {
    /** True when the friendly empty state should replace the list. */
    val hasNoAlerts: Boolean get() = !isLoading && items.isEmpty()
}

/**
 * Works out what the user should be told about, from what the app already knows.
 *
 * Sits in the middle of the MVVM chain: it reads four repositories plus the read-state store and
 * publishes one [NotificationsUiState] for [NotificationsFragment] to draw. It holds no views and no
 * Context, so it survives rotation with its data intact, and it formats nothing — turning
 * [AlertContent.Milestone] into "Krishala turns 1 month old this week" needs string resources and the
 * user's locale, which belong to the View.
 *
 * **Nothing here is stored.** There is no alerts table and no alert row. Each alert is *derived* on
 * every emission from tasks, routines, expenses and pet records, which has three consequences worth
 * stating plainly:
 *
 *  - Alerts are always true. Tick off the missed task and its alert stops existing on the next
 *    emission, with nothing to delete and no risk of a stale row outliving the thing it described.
 *  - "Now" is re-read each time the sources change, so a reminder that becomes a missed task, or a
 *    date that passes into the EARLIER group, corrects itself while the screen is open.
 *  - The only thing that genuinely cannot be derived — whether the user has *seen* an alert — is the
 *    one thing kept outside the derivation, in [AlertReadStore].
 *
 * **What each kind is read from:**
 *
 * | Alert | Derived from |
 * |---|---|
 * | Care reminder | An uncompleted `care_tasks` row in the VET_VISIT or MEDICATION category whose `dueAt` falls in the next [REMINDER_WINDOW_DAYS] days |
 * | Missed task | An uncompleted `care_tasks` row whose `dueAt` has passed within the last [HISTORY_WINDOW_DAYS] days |
 * | Activity | `expenses` rows created for a pet in the last [ACTIVITY_WINDOW_DAYS] days |
 * | Milestone | `pets.dateOfBirth` reaching a round age within the last [MILESTONE_WINDOW_DAYS] days |
 * | Tip | A blank column on a `pets` row, or a pet with no `care_routines` |
 *
 * The design's fifth card is a *shared care* alert naming the delegate who did something ("Sysy added
 * 2 expenses for Ddjw"). That one has no source: a pet has exactly one `ownerId`, no table records
 * who performed an action, and delegating a checklist hands it to an SMS or email intent without
 * storing the recipient. Rather than invent an actor, the card reports what is actually known — the
 * record moved, for which pet, how recently — as [AlertContent.Activity].
 *
 * @param petRepository The pets themselves: names, photos, ages, and the blanks the tips look for.
 * @param careRoutineRepository The schedules, which say whether a pet has any care set up at all.
 * @param careTaskRepository The tasks whose due times become reminders and missed-task alerts.
 * @param expenseRepository The spending whose recent additions become activity alerts.
 * @param alertReadStore Which alerts have already been read.
 * @param userId Whose alerts to build, read from the session — see
 *   [com.prash.petcare.ui.session.SessionViewModel].
 */
class NotificationsViewModel(
    petRepository: PetRepository,
    careRoutineRepository: CareRoutineRepository,
    careTaskRepository: CareTaskRepository,
    expenseRepository: ExpenseRepository,
    private val alertReadStore: AlertReadStore,
    userId: Long
) : ViewModel() {

    /**
     * One pet with everything the alert rules ask of it.
     *
     * Gathered per pet rather than queried account-wide because every alert needs the animal's name
     * beside its fact, and three of the five are *about* the animal rather than about a row. Private:
     * it is this class's working material, not something the View ever sees.
     */
    private data class PetData(
        val pet: Pet,
        val routines: List<CareRoutine>,
        val tasks: List<CareTask>,
        val expenses: List<Expense>
    )

    /**
     * Each pet with its routines, tasks and expenses.
     *
     * [flatMapLatest] is needed because the pet list is itself a stream: adding or removing a pet has
     * to rebuild the set of per-pet queries. "Latest" means the subscriptions opened for the previous
     * list are cancelled, so a deleted pet's queries stop rather than piling up.
     *
     * The empty case is short-circuited because `combine` over an empty list never emits at all — an
     * account with no pets would otherwise sit on the loading state forever and never reach the empty
     * state. This is the same shape [com.prash.petcare.ui.pet.PetsViewModel] uses, for the same
     * reasons.
     *
     * Composed from the existing per-pet queries rather than by adding account-wide ones to the DAOs:
     * this screen is a new reading of the data, not a new requirement on it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val petData: Flow<List<PetData>> =
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
                        ) { routines, tasks, expenses ->
                            PetData(pet, routines, tasks, expenses)
                        }
                    }
                ) { perPet -> perPet.toList() }
            }
        }

    /**
     * The whole screen.
     *
     * [combine] merges the pet data with the read-state store, so the list rebuilds both when the
     * database changes — a task ticked, an expense added, a weight filled in — and when an alert is
     * marked read. Room drives every one of those flows, including rows written by the Firestore
     * sync, so there is no manual refresh anywhere.
     *
     * The [onEach] afterwards is the one side effect in the chain: it tells the store which ids still
     * exist so it can forget the rest — see [AlertReadStore.keepOnly]. It cannot loop, because the
     * store only emits when it actually removes something, and the rebuild it triggers then finds
     * nothing left to remove.
     *
     * [stateIn] turns the cold result into a hot [StateFlow] holding the latest value, so a rotation
     * re-attaches to data that is already there instead of re-querying and flashing empty.
     * `WhileSubscribed(5_000)` keeps the queries alive for five seconds after the last observer
     * leaves — long enough to survive a rotation, short enough that a backgrounded screen stops
     * working.
     */
    val uiState: StateFlow<NotificationsUiState> = combine(
        petData,
        alertReadStore.readIds
    ) { pets, readIds ->
        // Read once per rebuild rather than per alert, so every age on the screen is measured from
        // the same instant and the TODAY/EARLIER split cannot disagree with the "2h ago" beside it.
        val now = System.currentTimeMillis()
        val alerts = buildAlerts(pets, readIds, now)

        NotificationsUiState(
            items = groupIntoSections(alerts, CareSchedule.startOfToday()),
            unreadCount = alerts.count { !it.isRead },
            petCount = pets.size,
            isLoading = false
        )
    }.onEach { state ->
        alertReadStore.keepOnly(state.alertIds())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NotificationsUiState()
    )

    /**
     * Marks one alert read — what tapping a card does, alongside opening the screen it points at.
     *
     * The ViewModel writes it rather than the fragment so the View never touches a data source
     * directly, which is the same rule that keeps it away from the DAOs.
     */
    fun markRead(alertId: String) {
        alertReadStore.markRead(alertId)
    }

    /** Marks every alert on screen read — the "MARK READ" button. */
    fun markAllRead() {
        alertReadStore.markRead(uiState.value.alertIds())
    }

    /** The ids of every alert currently listed, for marking and for pruning. */
    private fun NotificationsUiState.alertIds(): Set<String> =
        items.filterIsInstance<AlertListItem.AlertRow>()
            .map { it.alert.id }
            .toSet()

    /**
     * Builds every alert for every pet, newest first and capped.
     *
     * The cap exists because the rules are open-ended — an account with ten pets and a busy week
     * could produce a hundred rows, which is a list nobody reads. [MAX_ALERTS] of the most recent is
     * what a notifications screen is for; anything older has been overtaken by events.
     */
    private fun buildAlerts(pets: List<PetData>, readIds: Set<String>, now: Long): List<Alert> {
        val alerts = mutableListOf<Alert>()

        pets.forEach { data ->
            alerts += careReminders(data, now)
            alerts += missedTasks(data, now)
            alerts += activity(data, now)
            alerts += milestone(data, now)
        }

        // Tips are added from a pool of their own so a row of blank records cannot crowd out the
        // alerts that are actually about today. See [tips].
        alerts += tips(pets)

        return alerts
            .sortedByDescending { it.occurredAt }
            .take(MAX_ALERTS)
            .map { alert -> alert.copy(isRead = alert.id in readIds) }
    }

    /**
     * Health care coming up: a vet visit or a medication dose due within the next week.
     *
     * Only those two categories, because these are the appointments that are worth being reminded of
     * days ahead — a walk or a feed is a thing you do today, not something to prepare for, and the
     * Tasks tab already lists it.
     *
     * **When the alert "happened".** An appointment next Tuesday did not become news at the moment it
     * was typed in months ago, nor at the moment the screen was opened; it became news when it came
     * within the reminder window. So the alert's age is measured from `dueAt` minus that window,
     * which makes it read "6d ago" the day it appears and count up as the date approaches. A task
     * created *inside* the window — booked for tomorrow, entered today — would date the alert before
     * the task existed, so the creation time is the floor.
     */
    private fun careReminders(data: PetData, now: Long): List<Alert> =
        data.tasks
            .filter { task ->
                !task.isCompleted &&
                    task.category in HEALTH_CATEGORIES &&
                    task.dueAt > now &&
                    task.dueAt <= now + REMINDER_WINDOW_DAYS * MILLIS_PER_DAY
            }
            .map { task ->
                Alert(
                    // The due time is part of the id as well as the task's own: a repeating task
                    // rescheduled to a new date is a new thing to be told about, and should arrive
                    // unread rather than inheriting the previous occurrence's read state.
                    id = "reminder:${task.id}:${task.dueAt}",
                    content = AlertContent.CareReminder(
                        petName = data.pet.name,
                        taskTitle = task.title,
                        category = task.category,
                        dueAt = task.dueAt
                    ),
                    occurredAt = maxOf(
                        task.createdAt,
                        task.dueAt - REMINDER_WINDOW_DAYS * MILLIS_PER_DAY
                    ),
                    isRead = false,
                    target = AlertTarget.Tasks
                )
            }

    /**
     * Care whose time has gone by without it being checked off.
     *
     * The same definition the rest of the app uses for an overdue task — a due time in the past and
     * no completion — so the red dot on the header badge, the Tasks tab and this screen can never
     * disagree about what is late. It is worked out here from the pet's own tasks rather than through
     * the DAO's overdue query, because that query binds "now" into its SQL when the subscription
     * opens: on a screen left open, tasks would stop becoming overdue as the evening went on.
     *
     * Bounded to the last [HISTORY_WINDOW_DAYS] days. A task missed a month ago is no longer news,
     * and listing every one ever missed would bury today's under a history the user cannot act on.
     */
    private fun missedTasks(data: PetData, now: Long): List<Alert> =
        data.tasks
            .filter { task ->
                !task.isCompleted &&
                    task.dueAt < now &&
                    task.dueAt >= now - HISTORY_WINDOW_DAYS * MILLIS_PER_DAY
            }
            .map { task ->
                Alert(
                    id = "missed:${task.id}:${task.dueAt}",
                    content = AlertContent.MissedTask(
                        taskTitle = task.title,
                        petName = data.pet.name
                    ),
                    // The moment it was missed is the moment it fell due, which is what makes
                    // "5h ago" on the card mean something precise.
                    occurredAt = task.dueAt,
                    isRead = false,
                    target = AlertTarget.Tasks
                )
            }

    /**
     * Recent movement on a pet's record: expenses added in the last few days.
     *
     * One alert per pet rather than one per expense. Three receipts entered in a sitting are a single
     * piece of news — "2 expenses added for Ddjw" — and listing them separately would push everything
     * else off the screen for nothing.
     *
     * `createdAt` is the right column and `incurredOn` is not: the alert is about the record being
     * updated, not about when the money was spent. A receipt from last month entered this morning is
     * news this morning.
     *
     * The newest expense's id is part of the alert's id, so adding another expense makes a new alert
     * that arrives unread, while merely reopening the screen does not.
     */
    private fun activity(data: PetData, now: Long): List<Alert> {
        val recent = data.expenses.filter {
            it.createdAt >= now - ACTIVITY_WINDOW_DAYS * MILLIS_PER_DAY
        }
        val newest = recent.maxByOrNull { it.createdAt } ?: return emptyList()

        return listOf(
            Alert(
                id = "activity:${data.pet.id}:${newest.id}",
                content = AlertContent.Activity(
                    petName = data.pet.name,
                    expenseCount = recent.size
                ),
                occurredAt = newest.createdAt,
                isRead = false,
                target = AlertTarget.PetDetail(data.pet.id)
            )
        )
    }

    /**
     * A pet reaching a round age, from its recorded date of birth.
     *
     * **Which anniversaries count.** Under a year, every month: the first months of an animal's life
     * are when its age actually changes what it needs, and "1 month old" is a thing an owner tells
     * people. From a year on, only whole years — a monthly "turns 37 months old" for an adult dog is
     * noise, and nobody counts that way.
     *
     * Only anniversaries that have just *passed* produce an alert, within the last
     * [MILESTONE_WINDOW_DAYS] days, so the card can honestly say "this week" and the alert has a real
     * moment to be dated from.
     */
    private fun milestone(data: PetData, now: Long): List<Alert> {
        val dateOfBirth = data.pet.dateOfBirth ?: return emptyList()
        val months = completedMonths(dateOfBirth, now)

        // 0 would be "turns 0 months old", which is not a milestone; over a year, only whole years.
        if (months < 1) return emptyList()
        if (months >= MONTHS_PER_YEAR && months % MONTHS_PER_YEAR != 0) return emptyList()

        val anniversary = anniversaryOf(dateOfBirth, months)
        if (anniversary < now - MILESTONE_WINDOW_DAYS * MILLIS_PER_DAY) return emptyList()

        return listOf(
            Alert(
                // The age is part of the id, so next month's milestone is a new alert rather than
                // the same one the user has already dismissed.
                id = "milestone:${data.pet.id}:$months",
                content = AlertContent.Milestone(petName = data.pet.name, months = months),
                occurredAt = anniversary,
                isRead = false,
                target = AlertTarget.PetDetail(data.pet.id)
            )
        )
    }

    /**
     * Suggestions, each prompted by something a pet's record is missing.
     *
     * Every tip names a real gap in this user's data — no weight, no date of birth, no routine, no
     * photo — so it is a true statement rather than a general-purpose aphorism, and it disappears the
     * moment the gap is filled. One tip per pet, the first in [TipKind]'s own order, because a newly
     * added pet is missing nearly everything and would otherwise produce four cards on its own.
     *
     * Capped at [MAX_TIPS] across the whole account for the same reason: tips are the least urgent
     * thing here and must not crowd out a missed dose. The newest pets keep theirs, since those are
     * the records the user is most likely to still be filling in.
     *
     * The alert is dated from the pet's creation, which is when the gap began.
     */
    private fun tips(pets: List<PetData>): List<Alert> =
        pets.mapNotNull { data ->
            val kind = when {
                data.pet.weightKg == null -> TipKind.NO_WEIGHT
                data.pet.dateOfBirth == null -> TipKind.NO_DATE_OF_BIRTH
                data.routines.isEmpty() -> TipKind.NO_ROUTINE
                data.pet.photoPath.isNullOrBlank() -> TipKind.NO_PHOTO
                else -> null
            } ?: return@mapNotNull null

            Alert(
                id = "tip:${data.pet.id}:${kind.name}",
                content = AlertContent.Tip(kind = kind, petName = data.pet.name),
                occurredAt = data.pet.createdAt,
                isRead = false,
                target = AlertTarget.PetDetail(data.pet.id)
            )
        }
            .sortedByDescending { it.occurredAt }
            .take(MAX_TIPS)

    /**
     * Splits the sorted alerts into TODAY and EARLIER, inserting a heading before each group.
     *
     * A heading is only added when its group has something in it, so a screen holding nothing but
     * older alerts does not show an empty "TODAY".
     *
     * The alerts arrive already sorted newest first, which is what guarantees every TODAY item
     * precedes every EARLIER one — so this only has to find the boundary rather than sort again.
     */
    private fun groupIntoSections(alerts: List<Alert>, todayStart: Long): List<AlertListItem> {
        if (alerts.isEmpty()) return emptyList()

        val (today, earlier) = alerts.partition { it.occurredAt >= todayStart }
        val items = mutableListOf<AlertListItem>()

        if (today.isNotEmpty()) {
            items += AlertListItem.SectionHeader(AlertSection.TODAY)
            items += today.map { AlertListItem.AlertRow(it) }
        }
        if (earlier.isNotEmpty()) {
            items += AlertListItem.SectionHeader(AlertSection.EARLIER)
            items += earlier.map { AlertListItem.AlertRow(it) }
        }

        return items
    }

    /**
     * A pet's age in completed months.
     *
     * The same rule as [com.prash.petcare.ui.common.formatAge], and for the same reasons: a UTC
     * calendar, because a date of birth is stored as midnight UTC and reading it locally would roll
     * it back a day for anyone west of Greenwich; and a day-of-month check, so a pet born on the 30th
     * does not gain a month on the 1st.
     *
     * It is repeated here rather than called, because that function returns a *formatted string* for
     * a Context this class must not hold. The duplication is the arithmetic only — the wording still
     * lives in exactly one place.
     */
    private fun completedMonths(dateOfBirthMillis: Long, now: Long): Int {
        val utc = TimeZone.getTimeZone("UTC")
        val born = Calendar.getInstance(utc).apply { timeInMillis = dateOfBirthMillis }
        val today = Calendar.getInstance(utc).apply { timeInMillis = now }

        var months = (today.get(Calendar.YEAR) - born.get(Calendar.YEAR)) * MONTHS_PER_YEAR +
            (today.get(Calendar.MONTH) - born.get(Calendar.MONTH))

        if (today.get(Calendar.DAY_OF_MONTH) < born.get(Calendar.DAY_OF_MONTH)) {
            months--
        }

        return months.coerceAtLeast(0)
    }

    /**
     * A pet's Nth-month birthday, as local midnight on that calendar day.
     *
     * Two conversions, each with a reason.
     *
     * **The month is added on a UTC calendar**, because that is the zone the date of birth is stored
     * in — the date picker returns midnight UTC for the chosen day, and reading it locally would roll
     * it back a day for anyone west of Greenwich. [Calendar.add] rather than arithmetic on
     * milliseconds, because months are not a fixed length: "add 30 days" would drift by a day or two
     * each month and land a year's anniversary on the wrong date entirely. The calendar also settles
     * the awkward cases by itself — a pet born on the 31st reaches its first month on the 28th of
     * February.
     *
     * **The result is then rebuilt as local midnight** on the same calendar day. Every other alert is
     * dated from a wall-clock moment (a task's due time, an expense's creation), and those are what
     * the TODAY/EARLIER split and the "2h ago" ages are measured against. Leaving this one on UTC
     * would put a birthday into yesterday's group, and print yesterday's date beside it, for every
     * user west of Greenwich.
     */
    private fun anniversaryOf(dateOfBirthMillis: Long, months: Int): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = dateOfBirthMillis
            add(Calendar.MONTH, months)
        }

        return Calendar.getInstance().apply {
            clear()
            set(
                utc.get(Calendar.YEAR),
                utc.get(Calendar.MONTH),
                utc.get(Calendar.DAY_OF_MONTH)
            )
        }.timeInMillis
    }

    companion object {

        /**
         * Builds the ViewModel for a particular account.
         *
         * A function rather than a property because the user id is only known at runtime and has to
         * be captured here to reach the constructor. The repositories and the read store come from
         * the Application, which creates each one once — so this screen shares them with every other
         * ViewModel rather than opening its own database connection.
         */
        fun factory(userId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                NotificationsViewModel(
                    petRepository = application.petRepository,
                    careRoutineRepository = application.careRoutineRepository,
                    careTaskRepository = application.careTaskRepository,
                    expenseRepository = application.expenseRepository,
                    alertReadStore = application.alertReadStore,
                    userId = userId
                )
            }
        }

        /** The two categories worth being reminded of in advance — see [careReminders]. */
        private val HEALTH_CATEGORIES = setOf(TaskCategory.VET_VISIT, TaskCategory.MEDICATION)

        /** How far ahead a vet or medication date is announced. */
        private const val REMINDER_WINDOW_DAYS = 7L

        /** How far back missed care is still reported. */
        private const val HISTORY_WINDOW_DAYS = 14L

        /** How recently an expense must have been added to count as activity. */
        private const val ACTIVITY_WINDOW_DAYS = 7L

        /** How recently a birthday must have passed for the card to say "this week". */
        private const val MILESTONE_WINDOW_DAYS = 7L

        /** The most alerts the screen will show, newest first. */
        private const val MAX_ALERTS = 20

        /** The most tips shown at once, so they cannot crowd out care — see [tips]. */
        private const val MAX_TIPS = 2

        private const val MONTHS_PER_YEAR = 12
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
