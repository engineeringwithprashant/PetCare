package com.prash.petcare.util

import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.RepeatUnit
import java.util.Calendar

/**
 * Works out which care is due on a given day, and whether it has been done.
 *
 * Care tasks are stored as repeating templates rather than one row per day, so "what is due today"
 * is calculated rather than looked up. This object holds that calculation in one place, separate
 * from any screen.
 *
 * Everything here is a pure function of its arguments with no Android dependencies, which means it
 * can be unit-tested on a plain JVM — a real benefit for date logic, which is easy to get subtly
 * wrong and hard to spot by eye.
 *
 * [Calendar] is used throughout rather than `java.time` because the app supports minSdk 24, and
 * `java.time` only became available on API 26 without library desugaring.
 */
object CareSchedule {

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    private const val MINUTES_PER_HOUR = 60

    /**
     * How far ahead [nextOccurrence] looks by default.
     *
     * A leap year plus a day, so even a yearly routine is always found and the search still ends
     * for a schedule that can never match.
     */
    private const val DEFAULT_SEARCH_DAYS = 367

    /** Midnight at the start of the day containing [millis], in the device's time zone. */
    fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * The last millisecond of the day containing [millis].
     *
     * Reached by adding one day to midnight and stepping back a millisecond rather than adding a
     * fixed 24 hours, because a day is 23 or 25 hours long when the clocks change and
     * [Calendar.add] accounts for that.
     */
    fun endOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = startOfDay(millis)
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis - 1

    /** Midnight at the start of today. */
    fun startOfToday(): Long = startOfDay(System.currentTimeMillis())

    /** The last millisecond of today. */
    fun endOfToday(): Long = endOfDay(System.currentTimeMillis())

    /**
     * True if [routine] runs on the day beginning at [dayStartMillis].
     *
     * A paused routine never runs, and no routine runs before the date it started.
     */
    fun occursOn(routine: CareRoutine, dayStartMillis: Long): Boolean =
        routine.isActive && occursOn(
            routineStartDate = routine.startDate,
            repeatInterval = routine.repeatInterval,
            repeatUnit = routine.repeatUnit,
            dayStartMillis = dayStartMillis
        )

    /**
     * The same test, expressed in a routine's individual schedule fields rather than the whole
     * entity.
     *
     * The reminder code works from a lightweight database projection that carries only the columns
     * a notification needs, not a full [CareRoutine], so it has no entity to hand. Splitting the
     * rule out this way means both callers run *exactly* the same arithmetic — a second copy of
     * "does this routine run today?" would be the obvious place for the two to quietly drift apart.
     *
     * Note this overload knows nothing about whether the routine is paused; that check belongs to
     * the caller, and the entity-based overload above does it.
     */
    fun occursOn(
        routineStartDate: Long,
        repeatInterval: Int,
        repeatUnit: RepeatUnit,
        dayStartMillis: Long
    ): Boolean {
        val routineStart = startOfDay(routineStartDate)
        val day = startOfDay(dayStartMillis)
        if (day < routineStart) return false

        // A guard rather than a correctness concern: an interval of zero would divide by zero
        // below, and a negative one is meaningless.
        val interval = repeatInterval.coerceAtLeast(1)

        return when (repeatUnit) {
            RepeatUnit.DAY -> daysBetween(routineStart, day) % interval == 0L

            // Weekly care has to land on the same weekday it started on, and then only every
            // `interval` weeks.
            RepeatUnit.WEEK -> {
                val days = daysBetween(routineStart, day)
                days % 7 == 0L && (days / 7) % interval == 0L
            }

            // The app's routine form only offers daily and weekly, so these two are here to keep
            // the `when` exhaustive and to behave sensibly for any routine created another way.
            // The repeat interval is not applied to them.
            RepeatUnit.MONTH -> fieldOf(routineStart, Calendar.DAY_OF_MONTH) ==
                fieldOf(day, Calendar.DAY_OF_MONTH)

            RepeatUnit.YEAR -> fieldOf(routineStart, Calendar.DAY_OF_YEAR) ==
                fieldOf(day, Calendar.DAY_OF_YEAR)
        }
    }

    /**
     * The next moment a routine with this shape comes round at [timeOfDayMinutes], or null if it
     * does not happen again within [searchDays].
     *
     * This is what turns a repeating template into a single point in time that Android's
     * `AlarmManager` can be pointed at. Only the *next* occurrence is ever calculated: one alarm per
     * task is registered, and the reminder is re-planned each time it fires, which keeps the number
     * of live alarms equal to the number of tasks no matter how far into the future the routine
     * repeats.
     *
     * The result is strictly **after** [afterMillis], never equal to it. That matters more than it
     * looks: rescheduling happens the instant an alarm fires, so "at or after now" would hand back
     * the occurrence that has just gone off, set an alarm for the current millisecond, and spin.
     *
     * Days are stepped through one at a time and tested with [occursOn] rather than the interval
     * being multiplied out, so the answer cannot disagree with what the checklist shows — the two
     * ask the very same question of each day. A year of days is a trivial loop, and capping it is
     * what guarantees the search ends at all for a routine whose rule never matches a real date — a
     * monthly one anchored to the 31st, say.
     *
     * @param routineStartDate The owning routine's first day, as epoch milliseconds.
     * @param repeatInterval How many [repeatUnit]s pass between occurrences.
     * @param repeatUnit The unit that [repeatInterval] counts.
     * @param timeOfDayMinutes The task's time of day, as minutes since midnight.
     * @param afterMillis The moment to search forward from — normally now.
     * @param searchDays How far ahead to look before giving up.
     */
    fun nextOccurrence(
        routineStartDate: Long,
        repeatInterval: Int,
        repeatUnit: RepeatUnit,
        timeOfDayMinutes: Int,
        afterMillis: Long,
        searchDays: Int = DEFAULT_SEARCH_DAYS
    ): Long? {
        // Starting from whichever is later avoids walking through months of days for a routine
        // that has not begun yet, and avoids looking backwards for one that started long ago.
        var day = startOfDay(maxOf(afterMillis, routineStartDate))

        repeat(searchDays) {
            if (occursOn(routineStartDate, repeatInterval, repeatUnit, day)) {
                val occurrence = atTimeOfDay(day, timeOfDayMinutes)
                if (occurrence > afterMillis) return occurrence
            }
            day = nextDayStart(day)
        }

        return null
    }

    /**
     * The tasks, out of one pet's [tasks], that fall due on the day beginning at [dayStartMillis].
     *
     * A task runs on a day only if the routine that owns it runs that day — a weekly routine counts
     * on its own weekday and not otherwise — which is why the routines are filtered first and the
     * tasks matched against what survives. A task with no routine is a one-off with no schedule to
     * project, so it is never counted.
     *
     * Lives here rather than inside a ViewModel because two screens ask this exact question: the
     * dashboard (for its care card and checklist) and the Pets tab (for each card's "due today"
     * figure). One copy of the rule is what guarantees the two never report different numbers.
     *
     * @param routines The pet's routines. Paused ones are filtered out by [occursOn] itself, so the
     *   caller may pass all of them or only the active ones.
     */
    fun tasksDueOn(
        routines: List<CareRoutine>,
        tasks: List<CareTask>,
        dayStartMillis: Long
    ): List<CareTask> {
        val routineIdsDue = routines
            .filter { routine -> occursOn(routine, dayStartMillis) }
            .map { it.id }
            .toSet()

        return tasks.filter { task -> task.routineId?.let { it in routineIdsDue } == true }
    }

    /**
     * True if [task] has been ticked off within the day spanning [dayStart] to [dayEnd].
     *
     * The task's own completion timestamp is what is tested, not its boolean flag. Because a task
     * repeats, a plain flag would stay set forever after the first tick; checking the date means
     * yesterday's completion no longer counts today, so the checklist resets itself overnight
     * without anything having to run at midnight.
     */
    fun isDoneOn(task: CareTask, dayStart: Long, dayEnd: Long): Boolean {
        val completedAt = task.completedAt ?: return false
        return completedAt in dayStart..dayEnd
    }

    /**
     * Whole days from one midnight to another.
     *
     * Half a day is added before dividing so the result rounds to the nearest whole day. Without
     * that, the 23-hour day when clocks go forward would divide to zero and a daily routine would
     * silently skip.
     */
    private fun daysBetween(fromDayStart: Long, toDayStart: Long): Long =
        (toDayStart - fromDayStart + MILLIS_PER_DAY / 2) / MILLIS_PER_DAY

    private fun fieldOf(millis: Long, field: Int): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(field)

    /**
     * A given time of day on the day beginning at [dayStartMillis].
     *
     * The hour and minute are *set* on a calendar rather than added as milliseconds to midnight.
     * On the day the clocks go forward, adding "7 hours 30 minutes" to midnight lands at 08:30 —
     * an alarm an hour late, twice a year. Setting the fields asks for 07:30 on that date and lets
     * [Calendar] resolve it against the real offset.
     */
    private fun atTimeOfDay(dayStartMillis: Long, minutesSinceMidnight: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = dayStartMillis
            set(Calendar.HOUR_OF_DAY, minutesSinceMidnight / MINUTES_PER_HOUR)
            set(Calendar.MINUTE, minutesSinceMidnight % MINUTES_PER_HOUR)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * Midnight on the day after [dayStartMillis].
     *
     * [Calendar.add] rather than adding 24 hours, for the same reason [endOfDay] uses it: a day is
     * 23 or 25 hours long when the clocks change, and stepping by a fixed amount would drift off
     * midnight and start skipping or repeating days.
     */
    private fun nextDayStart(dayStartMillis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = dayStartMillis
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis
}
