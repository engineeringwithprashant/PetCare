package com.prash.petcare.util

import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.RepeatUnit
import java.util.Calendar

/** Works out which care is due on a given day, and whether it has been done. */
object CareSchedule {

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    private const val MINUTES_PER_HOUR = 60

    private const val DAYS_PER_WEEK = 7

    /** How far ahead [nextOccurrence] looks by default. */
    private const val DEFAULT_SEARCH_DAYS = 367

    /** Midnight at the start of the day containing [millis], in the device's time zone. */
    fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** The last millisecond of the day containing [millis]. */
    fun endOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = startOfDay(millis)
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis - 1

    /** Midnight at the start of today. */
    fun startOfToday(): Long = startOfDay(System.currentTimeMillis())

    /** The last millisecond of today. */
    fun endOfToday(): Long = endOfDay(System.currentTimeMillis())

    /** The seven day-starts (local midnights) of the week containing [millis], in order. */
    fun weekContaining(
        millis: Long,
        firstDayOfWeek: Int = Calendar.getInstance().firstDayOfWeek
    ): List<Long> {
        val calendar = Calendar.getInstance().apply { timeInMillis = startOfDay(millis) }

        // How many days back the week's first day is. The +7 keeps the result positive.
        val daysSinceWeekStart =
            (calendar.get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + DAYS_PER_WEEK) % DAYS_PER_WEEK
        calendar.add(Calendar.DAY_OF_MONTH, -daysSinceWeekStart)

        val days = ArrayList<Long>(DAYS_PER_WEEK)
        repeat(DAYS_PER_WEEK) {
            days.add(startOfDay(calendar.timeInMillis))
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        return days
    }

    /** True if [routine] runs on the day beginning at [dayStartMillis]. */
    fun occursOn(routine: CareRoutine, dayStartMillis: Long): Boolean =
        routine.isActive && occursOn(
            routineStartDate = routine.startDate,
            repeatInterval = routine.repeatInterval,
            repeatUnit = routine.repeatUnit,
            dayStartMillis = dayStartMillis
        )

    /** The same test, expressed in a routine's individual schedule fields rather than the whole entity. */
    fun occursOn(
        routineStartDate: Long,
        repeatInterval: Int,
        repeatUnit: RepeatUnit,
        dayStartMillis: Long
    ): Boolean {
        val routineStart = startOfDay(routineStartDate)
        val day = startOfDay(dayStartMillis)
        if (day < routineStart) return false

        // A guard rather than a correctness concern: an interval of zero would divide by zero below.
        val interval = repeatInterval.coerceAtLeast(1)

        return when (repeatUnit) {
            RepeatUnit.DAY -> daysBetween(routineStart, day) % interval == 0L

            // Weekly care has to land on the same weekday it started on, and then only every `interval` weeks.
            RepeatUnit.WEEK -> {
                val days = daysBetween(routineStart, day)
                days % 7 == 0L && (days / 7) % interval == 0L
            }

            // The app's routine form only offers daily and weekly.
            RepeatUnit.MONTH -> fieldOf(routineStart, Calendar.DAY_OF_MONTH) ==
                fieldOf(day, Calendar.DAY_OF_MONTH)

            RepeatUnit.YEAR -> fieldOf(routineStart, Calendar.DAY_OF_YEAR) ==
                fieldOf(day, Calendar.DAY_OF_YEAR)
        }
    }

    /** The next moment a routine with this shape comes round at [timeOfDayMinutes]. */
    fun nextOccurrence(
        routineStartDate: Long,
        repeatInterval: Int,
        repeatUnit: RepeatUnit,
        timeOfDayMinutes: Int,
        afterMillis: Long,
        searchDays: Int = DEFAULT_SEARCH_DAYS
    ): Long? {
        // Starting from whichever is later avoids walking through months of days for a routine that has not begun yet.
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

    /** The tasks, out of one pet's [tasks], that fall due on the day beginning at [dayStartMillis]. */
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

    /** True if [task] has been ticked off within the day spanning [dayStart] to [dayEnd]. */
    fun isDoneOn(task: CareTask, dayStart: Long, dayEnd: Long): Boolean {
        val completedAt = task.completedAt ?: return false
        return completedAt in dayStart..dayEnd
    }

    /** Whole days from one midnight to another. */
    private fun daysBetween(fromDayStart: Long, toDayStart: Long): Long =
        (toDayStart - fromDayStart + MILLIS_PER_DAY / 2) / MILLIS_PER_DAY

    private fun fieldOf(millis: Long, field: Int): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(field)

    /** A given time of day on the day beginning at [dayStartMillis]. */
    private fun atTimeOfDay(dayStartMillis: Long, minutesSinceMidnight: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = dayStartMillis
            set(Calendar.HOUR_OF_DAY, minutesSinceMidnight / MINUTES_PER_HOUR)
            set(Calendar.MINUTE, minutesSinceMidnight % MINUTES_PER_HOUR)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Midnight on the day after [dayStartMillis]. */
    private fun nextDayStart(dayStartMillis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = dayStartMillis
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis
}
