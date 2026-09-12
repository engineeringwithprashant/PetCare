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

/**
 * Unit tests for [CareSchedule], the date arithmetic behind "what is due today" and "when does this
 * come round next".
 *
 * These run on a plain JVM with no emulator, which is possible only because [CareSchedule] is pure:
 * every function is a function of its arguments with no Android dependencies. That is worth having
 * tests for precisely *because* it is date logic — the failures here are the quiet kind. A reminder
 * that arrives an hour late twice a year, or a fortnightly routine that skips a week in March, looks
 * like nothing at all in a screenshot and would never be noticed by clicking through the app.
 *
 * The cases below fall into four groups:
 *  - the day boundaries [CareSchedule.startOfDay] and [CareSchedule.endOfDay] everything else rests on;
 *  - [CareSchedule.occursOn], which decides whether a routine runs on a given day;
 *  - [CareSchedule.nextOccurrence], which turns a repeating routine into the single moment an alarm
 *    is set for;
 *  - [CareSchedule.isDoneOn], which decides whether a repeating task counts as done *today*.
 */
class CareScheduleTest {

    // -------------------------------------------------------------------------------------------
    // Time zone control
    // -------------------------------------------------------------------------------------------

    /**
     * The device's real time zone, put back after each test.
     *
     * [CareSchedule] reads the default zone through [Calendar.getInstance], so these tests would
     * otherwise pass or fail depending on where the machine running them happens to be — the worst
     * kind of test, green locally and red on a marker's laptop. Fixing the zone makes every
     * expectation below an exact, reproducible value.
     */
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

    /**
     * Switches to another zone for the daylight-saving tests.
     *
     * Called at the very start of a test, before any timestamp is built, because [at] and [format]
     * both read whatever the default zone is at the moment they run.
     */
    private fun useZone(id: String) {
        TimeZone.setDefault(TimeZone.getTimeZone(id))
    }

    // -------------------------------------------------------------------------------------------
    // Day boundaries
    // -------------------------------------------------------------------------------------------

    @Test
    fun `startOfDay is local midnight`() {
        assertEquals("2026-09-12 00:00", format(CareSchedule.startOfDay(at(2026, 9, 12, 14, 37))))
    }

    @Test
    fun `endOfDay is the last millisecond before the next midnight`() {
        val endOfDay = CareSchedule.endOfDay(at(2026, 9, 12, 14, 37))

        assertEquals("2026-09-12 23:59", format(endOfDay))
        // The exact millisecond matters: the checklist tests completion with `in dayStart..dayEnd`,
        // so a boundary one millisecond out would drop a task ticked off at the very end of the day.
        assertEquals(at(2026, 9, 13) - 1, endOfDay)
    }

    // -------------------------------------------------------------------------------------------
    // occursOn — does this routine run on this day?
    // -------------------------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------------------------
    // nextOccurrence — when should the alarm be set for?
    // -------------------------------------------------------------------------------------------

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

    /**
     * The property that stops a reminder firing in a loop.
     *
     * An alarm is rescheduled the instant it goes off, so this is asked at exactly the moment the
     * occurrence is due. If it answered "now", [com.prash.petcare.reminder.ReminderManager] would
     * register an alarm for the current millisecond, the system would deliver it immediately, and
     * the two would spin until the process was killed.
     */
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
        // 06:00 on the 12th: today's 07:30 has not passed, so only the repeat interval can push the
        // answer to the 13th — which is what makes this test about the schedule rather than the clock.
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

    /**
     * The search is capped, so it always ends.
     *
     * Asked to look only one day ahead on a day whose time has already gone, there is nothing left
     * to find. In the app the cap is a year, and returning null rather than looping forever is what
     * protects it from a schedule that can never match a real date.
     */
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

    // -------------------------------------------------------------------------------------------
    // Daylight saving
    // -------------------------------------------------------------------------------------------

    /**
     * A reminder keeps its wall-clock time when the clocks go forward.
     *
     * New York moves from 02:00 to 03:00 on 8 March 2026, making that day 23 hours long. The
     * tempting implementation — midnight plus 450 minutes — would land on 08:30 that morning,
     * because the hour it adds no longer exists. Setting the hour and minute on a calendar instead
     * asks for 07:30 on that date and lets the calendar resolve it, which is what this pins down.
     */
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

    /**
     * A repeat interval still counts whole days across a spring forward.
     *
     * From 1 March, an every-other-day routine runs on the 3rd, 5th, 7th, 9th and so on. Reaching
     * the 9th crosses the 23-hour day, so a plain millisecond division gives 7.96 days, truncates to
     * 7, and reports an odd-numbered day — the routine would silently skip from the 7th to the 11th.
     * Rounding to the nearest whole day is what keeps the count honest, and this is the case that
     * proves it: an interval of 1 would pass either way, so only an even interval can catch it.
     */
    @Test
    fun `an alternating routine does not skip a day across a spring forward`() {
        useZone(DST_ZONE)

        val start = at(2026, 3, 1)

        assertFalse(CareSchedule.occursOn(start, 2, RepeatUnit.DAY, at(2026, 3, 8)))
        assertTrue(CareSchedule.occursOn(start, 2, RepeatUnit.DAY, at(2026, 3, 9)))
    }

    // -------------------------------------------------------------------------------------------
    // isDoneOn — does this task count as done today?
    // -------------------------------------------------------------------------------------------

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

    /**
     * The behaviour that makes a repeating checklist reset itself overnight.
     *
     * The task's `isCompleted` flag is still true here — it was genuinely ticked off — but the
     * completion belongs to yesterday, so it must not count today. This is why the checklist tests
     * the timestamp rather than the flag, and why nothing has to run at midnight to clear it.
     */
    @Test
    fun `a task completed yesterday is not done today`() {
        val dayStart = at(2026, 9, 12)
        val dayEnd = CareSchedule.endOfDay(dayStart)

        val completedYesterday = task(completedAt = at(2026, 9, 11, 19, 30))

        assertTrue(completedYesterday.isCompleted)
        assertFalse(CareSchedule.isDoneOn(completedYesterday, dayStart, dayEnd))
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    /**
     * Epoch milliseconds for a local date and time in whatever zone the test is running in.
     *
     * [Calendar.clear] first, so the seconds and milliseconds are zero rather than inherited from
     * the moment the test happened to run — otherwise an exact-equality assertion would fail at
     * random. Note the month is written the way a human would (9 for September); [Calendar] counts
     * them from zero, which is corrected here so no test has to remember it.
     */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    /**
     * Renders a timestamp as local "yyyy-MM-dd HH:mm".
     *
     * Assertions read as dates rather than as thirteen-digit numbers, so a failure message says
     * which day and time came back instead of leaving the difference to be worked out by hand.
     */
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

    /**
     * A task carrying a given completion timestamp.
     *
     * `isCompleted` is derived from that timestamp rather than passed separately, so the two can
     * never be set to contradict each other — which is also the invariant the repository maintains
     * when it writes them.
     */
    private fun task(completedAt: Long?) = CareTask(
        petId = 1L,
        title = "Morning meal",
        category = TaskCategory.FEEDING,
        dueAt = 0L,
        isCompleted = completedAt != null,
        completedAt = completedAt
    )

    private companion object {
        /**
         * A zone with no daylight saving, used for every test that is not about DST.
         *
         * Kathmandu deliberately: its +05:45 offset is not a whole number of hours, so any accidental
         * assumption that a day boundary falls on a round UTC hour shows up here rather than in
         * production.
         */
        private const val FIXED_ZONE = "Asia/Kathmandu"

        /** A zone that does observe daylight saving; it springs forward on 8 March 2026. */
        private const val DST_ZONE = "America/New_York"

        /** 07:30 as minutes since midnight, the form a task stores its time in. */
        private const val SEVEN_THIRTY = 7 * 60 + 30
    }
}
