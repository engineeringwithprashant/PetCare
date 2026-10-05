package com.prash.petcare.ui.home

import com.prash.petcare.data.local.entity.CareCompletion
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.PetGender
import com.prash.petcare.data.local.entity.RepeatUnit
import com.prash.petcare.data.local.entity.TaskCategory
import com.prash.petcare.util.CareSchedule
import com.prash.petcare.util.MonthSummary
import com.prash.petcare.util.MonthlySpending
import java.util.Calendar

/* The dashboard's figures, and the pure arithmetic that produces them. */

/** Which greeting to use. Decided here from the hour rather than in the fragment. */
enum class PartOfDay {
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT;

    companion object {
        /** 05:00–11:59 morning, 12:00–16:59 afternoon, 17:00–21:59 evening, otherwise night. */
        fun at(hourOfDay: Int): PartOfDay = when (hourOfDay) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..21 -> EVENING
            else -> NIGHT
        }
    }
}

/** One pet with everything the dashboard needs to know about it, as gathered by [HomeViewModel]. */
data class PetCareData(
    val pet: Pet,
    val routines: List<CareRoutine>,
    val tasks: List<CareTask>,
    val expenses: List<Expense>
)

/** A task that falls due today, with the pet it belongs to and whether it has been ticked off. */
data class TodayTask(
    val task: CareTask,
    val pet: Pet,
    val isDone: Boolean
)

/** The walk-window card's fallback, drawn from the walks already on the calendar. */
sealed interface WalkWindow {
    /** No walk is scheduled today. */
    data object NoneToday : WalkWindow

    /** Every walk due today has been done. */
    data object AllDone : WalkWindow

    /** A walk is due now: its time has come, is within the hour, or it has no set time at all. */
    data class GoodNow(val timeOfDayMinutes: Int?) : WalkWindow

    /** The next walk is more than an hour away. */
    data class Later(val timeOfDayMinutes: Int) : WalkWindow
}

/** The next time a vet visit comes round, for the "next vet" stat card. */
data class NextVetVisit(
    val task: CareTask,
    val petName: String,
    val dueAtMillis: Long
)

/** The dark "next up" card. */
data class NextUp(
    val task: CareTask,
    val petName: String,
    val minutesAway: Int?
)

/** One box in the care-streak row. */
data class StreakDay(
    val dayStart: Long,
    val isFull: Boolean,
    val isToday: Boolean
)

/** What the "today's insight" card says. */
sealed interface Insight {
    /** No care falls on today at all. */
    data object NothingScheduled : Insight

    /** Everything due today is done. [fullDays] is the streak's count, for encouragement. */
    data class AllDone(val fullDays: Int) : Insight

    /** One pet holds at least half of what is left. */
    data class BusiestPet(
        val petName: String,
        val petGender: PetGender,
        val petOpenCount: Int,
        val totalOpenCount: Int,
        val firstTaskTitle: String,
        val laterTaskTitle: String,
        val laterTaskMinutes: Int
    ) : Insight

    /** Something is past its time — name the earliest. */
    data class Overdue(
        val taskTitle: String,
        val petName: String,
        val overdueCount: Int
    ) : Insight

    /** The general case: how much is left, and what is next. */
    data class NextTask(
        val taskTitle: String,
        val petName: String,
        val timeOfDayMinutes: Int?,
        val totalOpenCount: Int
    ) : Insight
}

/** One row of "coming up this week". */
data class ComingUpItem(
    val task: CareTask,
    val petName: String,
    val routineTitle: String,
    val dayStart: Long
)

/** Everything the dashboard draws, as one consistent snapshot. */
data class HomeUiState(
    val isLoading: Boolean = true,
    val firstName: String = "",
    val nowMillis: Long = 0L,
    val partOfDay: PartOfDay = PartOfDay.MORNING,
    val pets: List<Pet> = emptyList(),
    val tasksDueToday: Int = 0,
    val tasksDoneToday: Int = 0,
    val overdueCount: Int = 0,
    val nextUp: NextUp? = null,
    val walkWindow: WalkWindow = WalkWindow.NoneToday,
    val spending: MonthSummary? = null,
    val nextVet: NextVetVisit? = null,
    val streak: List<StreakDay> = emptyList(),
    val streakFullDays: Int = 0,
    val insight: Insight = Insight.NothingScheduled,
    val comingUp: List<ComingUpItem> = emptyList()
) {
    /** True when the empty state should replace the dashboard. */
    val hasNoPets: Boolean get() = !isLoading && pets.isEmpty()

    /** What the "today's care" headline reports. */
    val tasksLeftToday: Int get() = tasksDueToday - tasksDoneToday
}

/** Builds [HomeUiState] from raw rows. See the note at the top of this file. */
object HomeDashboard {

    /** How many days the care streak shows, today included. */
    const val STREAK_DAYS = 7

    /** How far ahead "coming up this week" looks, starting tomorrow. */
    const val COMING_UP_DAYS = 7

    /** The most rows "coming up this week" shows — it is a glance, not a diary. */
    const val MAX_COMING_UP = 4

    /** A walk within this many minutes counts as "good now". */
    const val WALK_SOON_MINUTES = 60

    private const val MINUTES_PER_HOUR = 60

    /** Assembles the whole dashboard. */
    fun build(
        userFullName: String?,
        petCare: List<PetCareData>,
        completions: List<CareCompletion>,
        nowMillis: Long,
        firstDayOfWeek: Int
    ): HomeUiState {
        val todayStart = CareSchedule.startOfDay(nowMillis)
        val todayEnd = CareSchedule.endOfDay(nowMillis)
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * MINUTES_PER_HOUR +
            calendar.get(Calendar.MINUTE)

        val today = todayTasks(petCare, todayStart, todayEnd)
        val open = today.filter { !it.isDone }
        val streak = streak(petCare, completions, nowMillis, firstDayOfWeek)
        val fullDays = streak.count { it.isFull }

        return HomeUiState(
            isLoading = false,
            firstName = userFullName.orEmpty().trim().substringBefore(' '),
            nowMillis = nowMillis,
            partOfDay = PartOfDay.at(calendar.get(Calendar.HOUR_OF_DAY)),
            pets = petCare.map { it.pet },
            tasksDueToday = today.size,
            tasksDoneToday = today.size - open.size,
            overdueCount = open.count { isLate(it, nowMinutes) },
            nextUp = open.firstOrNull()?.let { next ->
                NextUp(
                    task = next.task,
                    petName = next.pet.name,
                    minutesAway = next.task.timeOfDayMinutes?.let { it - nowMinutes }
                )
            },
            walkWindow = walkWindow(today, nowMinutes),
            spending = MonthlySpending.summarise(petCare.flatMap { it.expenses }, nowMillis),
            nextVet = nextVetVisit(petCare, nowMillis),
            streak = streak,
            streakFullDays = fullDays,
            insight = insightFor(today, nowMinutes, fullDays),
            comingUp = comingUp(petCare, nowMillis)
        )
    }

    /** Every task due today across all pets, earliest first. */
    fun todayTasks(petCare: List<PetCareData>, todayStart: Long, todayEnd: Long): List<TodayTask> =
        petCare
            .flatMap { care ->
                CareSchedule.tasksDueOn(care.routines, care.tasks, todayStart).map { task ->
                    TodayTask(task, care.pet, CareSchedule.isDoneOn(task, todayStart, todayEnd))
                }
            }
            .sortedWith(
                compareBy(
                    { it.task.timeOfDayMinutes ?: Int.MAX_VALUE },
                    { it.pet.name },
                    { it.task.title }
                )
            )

    /** An open task whose time of day has already passed. A task with no time is never late. */
    private fun isLate(item: TodayTask, nowMinutes: Int): Boolean {
        val time = item.task.timeOfDayMinutes ?: return false
        return !item.isDone && time < nowMinutes
    }

    /** See [WalkWindow]. [today] must already be in time order, as [todayTasks] returns it. */
    fun walkWindow(today: List<TodayTask>, nowMinutes: Int): WalkWindow {
        val walks = today.filter { it.task.category == TaskCategory.WALK }
        val nextWalk = walks.firstOrNull { !it.isDone }

        return when {
            walks.isEmpty() -> WalkWindow.NoneToday
            nextWalk == null -> WalkWindow.AllDone
            else -> {
                val time = nextWalk.task.timeOfDayMinutes
                // A walk that is late is still a walk worth doing now, so anything up to an hour ahead.
                if (time == null || time <= nowMinutes + WALK_SOON_MINUTES) {
                    WalkWindow.GoodNow(time)
                } else {
                    WalkWindow.Later(time)
                }
            }
        }
    }

    /** The soonest vet visit across every pet. */
    fun nextVetVisit(petCare: List<PetCareData>, nowMillis: Long): NextVetVisit? =
        petCare
            .flatMap { care ->
                val routinesById = care.routines.associateBy { it.id }
                care.tasks
                    .filter { it.category == TaskCategory.VET_VISIT }
                    .mapNotNull { task ->
                        val routine = task.routineId?.let { routinesById[it] } ?: return@mapNotNull null
                        val time = task.timeOfDayMinutes ?: return@mapNotNull null
                        val dueAt = CareSchedule.nextOccurrence(
                            routineStartDate = routine.startDate,
                            repeatInterval = routine.repeatInterval,
                            repeatUnit = routine.repeatUnit,
                            timeOfDayMinutes = time,
                            afterMillis = nowMillis
                        ) ?: return@mapNotNull null
                        NextVetVisit(task, care.pet.name, dueAt)
                    }
            }
            .minByOrNull { it.dueAtMillis }

    /** The seven streak boxes: the days of the current week, in order. */
    fun streak(
        petCare: List<PetCareData>,
        completions: List<CareCompletion>,
        nowMillis: Long,
        firstDayOfWeek: Int
    ): List<StreakDay> {
        val todayStart = CareSchedule.startOfDay(nowMillis)

        // The same helper the Tasks tab's week strip uses.
        val dayStarts = CareSchedule.weekContaining(nowMillis, firstDayOfWeek)

        return dayStarts.map { dayStart ->
            val dayEnd = CareSchedule.endOfDay(dayStart)
            val isToday = dayStart == todayStart
            val doneIds = completions
                .filter { it.completedAt in dayStart..dayEnd }
                .mapTo(HashSet()) { it.taskId }

            val due = petCare.flatMap { CareSchedule.tasksDueOn(it.routines, it.tasks, dayStart) }
            val isFull = due.isNotEmpty() && due.all { task ->
                task.id in doneIds || (isToday && CareSchedule.isDoneOn(task, dayStart, dayEnd))
            }

            StreakDay(dayStart = dayStart, isFull = isFull, isToday = isToday)
        }
    }

    /** Picks the insight for today. The cases are tried in order and the first that applies wins. */
    fun insightFor(today: List<TodayTask>, nowMinutes: Int, fullDays: Int): Insight {
        val open = today.filter { !it.isDone }
        if (today.isEmpty()) return Insight.NothingScheduled
        if (open.isEmpty()) return Insight.AllDone(fullDays)

        // groupBy keeps first-seen order, and maxBy keeps the first of equals.
        val busiest = open.groupBy { it.pet.id }.values.maxBy { it.size }
        val busiestPet = busiest.first().pet
        val later = open.firstOrNull { it.pet.id != busiestPet.id && it.task.timeOfDayMinutes != null }

        if (busiest.size >= 2 && busiest.size * 2 >= open.size && later != null) {
            return Insight.BusiestPet(
                petName = busiestPet.name,
                petGender = busiestPet.gender,
                petOpenCount = busiest.size,
                totalOpenCount = open.size,
                firstTaskTitle = busiest.first().task.title,
                laterTaskTitle = later.task.title,
                laterTaskMinutes = later.task.timeOfDayMinutes ?: 0
            )
        }

        val late = open.filter { isLate(it, nowMinutes) }
        if (late.isNotEmpty()) {
            return Insight.Overdue(
                taskTitle = late.first().task.title,
                petName = late.first().pet.name,
                overdueCount = late.size
            )
        }

        val next = open.first()
        return Insight.NextTask(
            taskTitle = next.task.title,
            petName = next.pet.name,
            timeOfDayMinutes = next.task.timeOfDayMinutes,
            totalOpenCount = open.size
        )
    }

    /** The next few notable things in the seven days after today. */
    fun comingUp(petCare: List<PetCareData>, nowMillis: Long): List<ComingUpItem> {
        val todayStart = CareSchedule.startOfDay(nowMillis)
        val seenTaskIds = HashSet<Long>()
        val items = ArrayList<ComingUpItem>()

        for (daysAhead in 1..COMING_UP_DAYS) {
            val dayStart = CareSchedule.startOfDay(
                Calendar.getInstance().apply {
                    timeInMillis = todayStart
                    add(Calendar.DAY_OF_MONTH, daysAhead)
                }.timeInMillis
            )

            val dayItems = petCare.flatMap { care ->
                val routinesById = care.routines.associateBy { it.id }
                CareSchedule.tasksDueOn(care.routines, care.tasks, dayStart).mapNotNull { task ->
                    val routine = task.routineId?.let { routinesById[it] } ?: return@mapNotNull null
                    if (isEveryDay(routine) || !seenTaskIds.add(task.id)) return@mapNotNull null
                    ComingUpItem(task, care.pet.name, routine.title, dayStart)
                }
            }

            items += dayItems.sortedBy { it.task.timeOfDayMinutes ?: Int.MAX_VALUE }
        }

        return items.take(MAX_COMING_UP)
    }

    /** A routine that recurs every day, which "coming up" skips. */
    private fun isEveryDay(routine: CareRoutine): Boolean =
        routine.repeatUnit == RepeatUnit.DAY && routine.repeatInterval <= 1
}
