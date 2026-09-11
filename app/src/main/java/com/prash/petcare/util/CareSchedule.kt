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
    fun occursOn(routine: CareRoutine, dayStartMillis: Long): Boolean {
        if (!routine.isActive) return false

        val routineStart = startOfDay(routine.startDate)
        val day = startOfDay(dayStartMillis)
        if (day < routineStart) return false

        // A guard rather than a correctness concern: an interval of zero would divide by zero
        // below, and a negative one is meaningless.
        val interval = routine.repeatInterval.coerceAtLeast(1)

        return when (routine.repeatUnit) {
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
}
