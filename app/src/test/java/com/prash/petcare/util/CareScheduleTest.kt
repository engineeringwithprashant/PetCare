package com.prash.petcare.util

import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.RepeatUnit
import com.prash.petcare.data.local.entity.TaskCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Unit tests for [CareSchedule]. */
class CareScheduleTest {

    // Time zone control.

    /** The device's real time zone, put back after each test. */
    private lateinit var realTimeZone: TimeZone

    @Before
    fun fixTimeZone() {
        realTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(FIXED_ZONE))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(realTimeZone)
    }

    /** Switches to another zone for the daylight-saving tests. */
    private fun useZone(id: String) {
        TimeZone.setDefault(TimeZone.getTimeZone(id))
    }

    // Day boundaries.

    @Test
    fun `startOfDay is local midnight`() {
        assertEquals("2026-09-12 00:00", format(CareSchedule.startOfDay(at(2026, 9, 12, 14, 37))))
    }

    @Test
    fun `endOfDay is the last millisecond before the next midnight`() {
        val endOfDay = CareSchedule.endOfDay(at(2026, 9, 12, 14, 37))

        assertEquals("2026-09-12 23:59", format(endOfDay))
        // The exact millisecond matters: the checklist tests completion with `in dayStart..dayEnd`.
        assertEquals(at(2026, 9, 13) - 1, endOfDay)
    }

    // occursOn — does this routine run on this day?

    @Test
    fun `a paused routine never runs`() {
        val paused = routine(startDate = at(2026, 9, 1), isActive = false)

        assertFalse(CareSchedule.occursOn(paused, at(2026, 9, 12)))
    }

    @Test
    fun `a routine does not run before the day it starts`() {
        val startsTomorrow = routine(startDate = at(2026, 9, 13))

        assertFalse(CareSchedule.occursOn(startsTomorrow, at(2026, 9, 12)))
        assertTrue(CareSchedule.occursOn(startsTomorrow, at(2026, 9, 13)))
    }

    @Test
    fun `a daily routine runs every day from its start`() {
        val start = at(2026, 9, 1)

        assertTrue(CareSchedule.occursOn(start, 1, RepeatUnit.DAY, at(2026, 9, 1)))
        assertTrue(CareSchedule.occursOn(start, 1, RepeatUnit.DAY, at(2026, 9, 2)))
        assertTrue(CareSchedule.occursOn(start, 1, RepeatUnit.DAY, at(2026, 9, 30)))
    }

    @Test
    fun `an every-other-day routine skips the days between`() {
        val start = at(2026, 9, 1)

        assertTrue(CareSchedule.occursOn(start, 2, RepeatUnit.DAY, at(2026, 9, 1)))
        assertFalse(CareSchedule.occursOn(start, 2, RepeatUnit.DAY, at(2026, 9, 2)))
        assertTrue(CareSchedule.occursOn(start, 2, RepeatUnit.DAY, at(2026, 9, 3)))
    }

    @Test
    fun `a weekly routine only runs on the weekday it started on`() {
        val start = at(2026, 9, 1)

        assertTrue(CareSchedule.occursOn(start, 1, RepeatUnit.WEEK, at(2026, 9, 1)))
        assertFalse(CareSchedule.occursOn(start, 1, RepeatUnit.WEEK, at(2026, 9, 2)))
        assertTrue(CareSchedule.occursOn(start, 1, RepeatUnit.WEEK, at(2026, 9, 8)))
    }

    @Test
    fun `a fortnightly routine skips the week in between`() {
        val start = at(2026, 9, 1)

        assertTrue(CareSchedule.occursOn(start, 2, RepeatUnit.WEEK, at(2026, 9, 1)))
        assertFalse(CareSchedule.occursOn(start, 2, RepeatUnit.WEEK, at(2026, 9, 8)))
        assertTrue(CareSchedule.occursOn(start, 2, RepeatUnit.WEEK, at(2026, 9, 15)))
    }

    // nextOccurrence — when should the alarm be set for?

    @Test
    fun `nextOccurrence returns today when the time is still ahead`() {
        val next = CareSchedule.nextOccurrence(
            routineStartDate = at(2026, 9, 1),
            repeatInterval = 1,
            repeatUnit = RepeatUnit.DAY,
            timeOfDayMinutes = SEVEN_THIRTY,
            afterMillis = at(2026, 9, 12, 6, 0)
        )

        assertEquals("2026-09-12 07:30", format(next))
    }

    @Test
    fun `nextOccurrence rolls on to tomorrow once today's time has passed`() {
        val next = CareSchedule.nextOccurrence(
            routineStartDate = at(2026, 9, 1),
            repeatInterval = 1,
            repeatUnit = RepeatUnit.DAY,
            timeOfDayMinutes = SEVEN_THIRTY,
            afterMillis = at(2026, 9, 12, 9, 0)
        )

        assertEquals("2026-09-13 07:30", format(next))
    }

    /** The property that stops a reminder firing in a loop. */
    @Test
    fun `nextOccurrence is strictly after the moment it is given`() {
        val dueNow = at(2026, 9, 12, 7, 30)

        val next = CareSchedule.nextOccurrence(
            routineStartDate = at(2026, 9, 1),
            repeatInterval = 1,
            repeatUnit = RepeatUnit.DAY,
            timeOfDayMinutes = SEVEN_THIRTY,
            afterMillis = dueNow
        )

        assertEquals("2026-09-13 07:30", format(next))
    }

    @Test
    fun `nextOccurrence waits for a routine that has not started yet`() {
        val next = CareSchedule.nextOccurrence(
            routineStartDate = at(2026, 10, 1),
            repeatInterval = 1,
            repeatUnit = RepeatUnit.DAY,
            timeOfDayMinutes = SEVEN_THIRTY,
            afterMillis = at(2026, 9, 12, 9, 0)
        )

        assertEquals("2026-10-01 07:30", format(next))
    }

    @Test
    fun `nextOccurrence follows an every-other-day schedule`() {
        // 06:00 on the 12th: today's 07:30 has not passed.
        val next = CareSchedule.nextOccurrence(
            routineStartDate = at(2026, 9, 1),
            repeatInterval = 2,
            repeatUnit = RepeatUnit.DAY,
            timeOfDayMinutes = SEVEN_THIRTY,
            afterMillis = at(2026, 9, 12, 6, 0)
        )

        assertEquals("2026-09-13 07:30", format(next))
    }

    @Test
    fun `nextOccurrence follows a weekly schedule`() {
        val next = CareSchedule.nextOccurrence(
            routineStartDate = at(2026, 9, 1),
            repeatInterval = 1,
            repeatUnit = RepeatUnit.WEEK,
            timeOfDayMinutes = SEVEN_THIRTY,
            afterMillis = at(2026, 9, 1, 12, 0)
        )

        assertEquals("2026-09-08 07:30", format(next))
    }

    /** The search is capped, so it always ends. */
    @Test
    fun `nextOccurrence returns null when nothing falls inside the search window`() {
        val next = CareSchedule.nextOccurrence(
            routineStartDate = at(2026, 9, 1),
            repeatInterval = 1,
            repeatUnit = RepeatUnit.DAY,
            timeOfDayMinutes = SEVEN_THIRTY,
            afterMillis = at(2026, 9, 12, 9, 0),
            searchDays = 1
        )

        assertNull(next)
    }

    // Daylight saving.

    /** A reminder keeps its wall-clock time when the clocks go forward. */
    @Test
    fun `nextOccurrence keeps its time of day across a spring forward`() {
        useZone(DST_ZONE)

        val next = CareSchedule.nextOccurrence(
            routineStartDate = at(2026, 3, 1),
            repeatInterval = 1,
            repeatUnit = RepeatUnit.DAY,
            timeOfDayMinutes = SEVEN_THIRTY,
            afterMillis = at(2026, 3, 7, 12, 0)
        )

        assertEquals("2026-03-08 07:30", format(next))
    }

    /** A repeat interval still counts whole days across a spring forward. */
    @Test
    fun `an alternating routine does not skip a day across a spring forward`() {
        useZone(DST_ZONE)

        val start = at(2026, 3, 1)

        assertFalse(CareSchedule.occursOn(start, 2, RepeatUnit.DAY, at(2026, 3, 8)))
        assertTrue(CareSchedule.occursOn(start, 2, RepeatUnit.DAY, at(2026, 3, 9)))
    }

    // isDoneOn — does this task count as done today?

    @Test
    fun `a task that has never been completed is not done`() {
        val dayStart = at(2026, 9, 12)
        val dayEnd = CareSchedule.endOfDay(dayStart)

        assertFalse(CareSchedule.isDoneOn(task(completedAt = null), dayStart, dayEnd))
    }

    @Test
    fun `a task completed within the day is done`() {
        val dayStart = at(2026, 9, 12)
        val dayEnd = CareSchedule.endOfDay(dayStart)

        val completedThisMorning = task(completedAt = at(2026, 9, 12, 7, 2))

        assertTrue(CareSchedule.isDoneOn(completedThisMorning, dayStart, dayEnd))
    }

    /** The behaviour that makes a repeating checklist reset itself overnight. */
    @Test
    fun `a task completed yesterday is not done today`() {
        val dayStart = at(2026, 9, 12)
        val dayEnd = CareSchedule.endOfDay(dayStart)

        val completedYesterday = task(completedAt = at(2026, 9, 11, 19, 30))

        assertTrue(completedYesterday.isCompleted)
        assertFalse(CareSchedule.isDoneOn(completedYesterday, dayStart, dayEnd))
    }

    // tasksDueOn — the shared "what runs on this day" rule used by the Home, Pets and Tasks tabs.

    /** A task counts on a day only when its routine does. */
    @Test
    fun `tasksDueOn keeps only tasks whose routine runs that day`() {
        val weekly = routine(startDate = at(2026, 9, 11), repeatUnit = RepeatUnit.WEEK)
            .copy(id = 7L)
        val weeklyTask = task(completedAt = null).copy(id = 1L, routineId = 7L)

        assertEquals(
            listOf(weeklyTask),
            CareSchedule.tasksDueOn(listOf(weekly), listOf(weeklyTask), at(2026, 9, 11))
        )
        assertTrue(
            CareSchedule.tasksDueOn(listOf(weekly), listOf(weeklyTask), at(2026, 9, 12)).isEmpty()
        )
    }

    /** A task with no routine has no schedule, so it is never due — and a paused routine never runs. */
    @Test
    fun `tasksDueOn ignores unscheduled tasks and paused routines`() {
        val paused = routine(startDate = at(2026, 9, 1), isActive = false).copy(id = 3L)
        val pausedTask = task(completedAt = null).copy(id = 1L, routineId = 3L)
        val orphanTask = task(completedAt = null).copy(id = 2L, routineId = null)

        assertTrue(
            CareSchedule.tasksDueOn(listOf(paused), listOf(pausedTask, orphanTask), at(2026, 9, 11))
                .isEmpty()
        )
    }

    // weekContaining — the Tasks tab's week strip.

    /** Thursday 10 September 2026 in a Monday-first week: Monday the 7th through Sunday the 13th. */
    @Test
    fun `weekContaining starts on the given first day and runs seven midnights`() {
        val week = CareSchedule.weekContaining(at(2026, 9, 10, 15, 45), Calendar.MONDAY)

        assertEquals(7, week.size)
        assertEquals("2026-09-07 00:00", format(week.first()))
        assertEquals("2026-09-13 00:00", format(week.last()))
    }

    /** A Sunday in a Monday-first week belongs to the week that *ends* on it, not the one after. */
    @Test
    fun `weekContaining puts a Sunday at the end of a Monday-first week`() {
        val week = CareSchedule.weekContaining(at(2026, 9, 13), Calendar.MONDAY)

        assertEquals("2026-09-07 00:00", format(week.first()))
    }

    /** Every cell must be a real local midnight even in the week the clocks go forward. */
    @Test
    fun `weekContaining stays on midnight across a spring forward`() {
        useZone(DST_ZONE)

        val week = CareSchedule.weekContaining(at(2026, 3, 8), Calendar.SUNDAY)

        assertEquals(
            listOf(
                "2026-03-08 00:00", "2026-03-09 00:00", "2026-03-10 00:00", "2026-03-11 00:00",
                "2026-03-12 00:00", "2026-03-13 00:00", "2026-03-14 00:00"
            ),
            week.map { format(it) }
        )
    }

    // Helpers.

    /** Epoch milliseconds for a local date and time in whatever zone the test is running in. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    /** Renders a timestamp as local "yyyy-MM-dd HH:mm". */
    private fun format(millis: Long?): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.UK).format(Date(requireNotNull(millis)))

    /** A routine with only the fields these tests care about set. */
    private fun routine(
        startDate: Long,
        repeatInterval: Int = 1,
        repeatUnit: RepeatUnit = RepeatUnit.DAY,
        isActive: Boolean = true
    ) = CareRoutine(
        petId = 1L,
        title = "Morning walk",
        repeatInterval = repeatInterval,
        repeatUnit = repeatUnit,
        startDate = startDate,
        isActive = isActive
    )

    /** A task carrying a given completion timestamp. */
    private fun task(completedAt: Long?) = CareTask(
        petId = 1L,
        title = "Morning meal",
        category = TaskCategory.FEEDING,
        dueAt = 0L,
        isCompleted = completedAt != null,
        completedAt = completedAt
    )

    private companion object {
        /** A zone with no daylight saving, used for every test that is not about DST. */
        private const val FIXED_ZONE = "Asia/Kathmandu"

        /** A zone that does observe daylight saving; it springs forward on 8 March 2026. */
        private const val DST_ZONE = "America/New_York"

        /** 07:30 as minutes since midnight, the form a task stores its time in. */
        private const val SEVEN_THIRTY = 7 * 60 + 30
    }
}
