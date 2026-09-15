package com.prash.petcare.ui.home

import com.prash.petcare.data.local.entity.CareCompletion
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.PetGender
import com.prash.petcare.data.local.entity.PetSpecies
import com.prash.petcare.data.local.entity.RepeatUnit
import com.prash.petcare.data.local.entity.TaskCategory
import com.prash.petcare.util.CareSchedule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Unit tests for [HomeDashboard], the arithmetic behind every card on the Home tab.
 *
 * They run on a plain JVM because [HomeDashboard] is pure — the clock is a parameter and nothing touches
 * Android — and they are worth having for the same reason CareScheduleTest is: the failures here are
 * quiet ones. A streak that counts yesterday's tick on today, a walk that shows "later" when it is
 * already late, or a "coming up" list flooded with tomorrow's breakfast all look like reasonable
 * screens; only a test notices they are wrong.
 *
 * Every test is set on **Thursday 17 September 2026 at 17:38** in Kathmandu, like the design's "5:38 PM"
 * evening, unless it says otherwise.
 */
class HomeDashboardTest {

    // -------------------------------------------------------------------------------------------
    // Time zone control — see CareScheduleTest for why the zone is fixed.
    // -------------------------------------------------------------------------------------------

    private lateinit var realTimeZone: TimeZone

    @Before
    fun fixTimeZone() {
        realTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kathmandu"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(realTimeZone)
    }

    // -------------------------------------------------------------------------------------------
    // Greeting
    // -------------------------------------------------------------------------------------------

    @Test
    fun `part of day follows the hour`() {
        assertEquals(PartOfDay.NIGHT, PartOfDay.at(4))
        assertEquals(PartOfDay.MORNING, PartOfDay.at(5))
        assertEquals(PartOfDay.AFTERNOON, PartOfDay.at(12))
        assertEquals(PartOfDay.EVENING, PartOfDay.at(17))
        assertEquals(PartOfDay.NIGHT, PartOfDay.at(22))
    }

    @Test
    fun `the greeting uses the first word of the name and the evening`() {
        val state = HomeDashboard.build("Prashant Pant", emptyList(), emptyList(), now())

        assertEquals("Prashant", state.firstName)
        assertEquals(PartOfDay.EVENING, state.partOfDay)
        assertFalse(state.isLoading)
    }

    // -------------------------------------------------------------------------------------------
    // Next up, overdue and the walk window
    // -------------------------------------------------------------------------------------------

    @Test
    fun `next up is the earliest open task, with minutes counted from now`() {
        val krishala = pet(1, "Krishala")
        val care = care(
            krishala,
            routines = listOf(routine(id = 10, petId = 1)),
            tasks = listOf(
                task(id = 100, petId = 1, routineId = 10, title = "Morning meds", time = 8 * 60, completedAt = now()),
                task(id = 101, petId = 1, routineId = 10, title = "Brush coat", time = 18 * 60)
            )
        )

        val state = HomeDashboard.build(null, listOf(care), emptyList(), now())

        assertEquals("Brush coat", state.nextUp?.task?.title)
        // 18:00 − 17:38.
        assertEquals(22, state.nextUp?.minutesAway)
        assertEquals(2, state.tasksDueToday)
        assertEquals(1, state.tasksDoneToday)
        assertEquals(0, state.overdueCount)
    }

    @Test
    fun `an open task whose time has passed is overdue and counts negative minutes`() {
        val care = care(
            pet(1, "Ddjw"),
            routines = listOf(routine(id = 10, petId = 1)),
            tasks = listOf(task(id = 100, petId = 1, routineId = 10, title = "Lunch", time = 13 * 60))
        )

        val state = HomeDashboard.build(null, listOf(care), emptyList(), now())

        assertEquals(1, state.overdueCount)
        assertEquals(13 * 60 - (17 * 60 + 38), state.nextUp?.minutesAway)
    }

    @Test
    fun `walk window reports none, done, good now and later`() {
        val minutesNow = 17 * 60 + 38

        assertEquals(WalkWindow.NoneToday, HomeDashboard.walkWindow(emptyList(), minutesNow))

        val done = today(walk(time = 7 * 60, isDone = true))
        assertEquals(WalkWindow.AllDone, HomeDashboard.walkWindow(done, minutesNow))

        val withinTheHour = today(walk(time = 18 * 60 + 30))
        assertEquals(WalkWindow.GoodNow(18 * 60 + 30), HomeDashboard.walkWindow(withinTheHour, minutesNow))

        val alreadyLate = today(walk(time = 16 * 60))
        assertEquals(WalkWindow.GoodNow(16 * 60), HomeDashboard.walkWindow(alreadyLate, minutesNow))

        val later = today(walk(time = 20 * 60))
        assertEquals(WalkWindow.Later(20 * 60), HomeDashboard.walkWindow(later, minutesNow))

        val anyTime = today(walk(time = null))
        assertEquals(WalkWindow.GoodNow(null), HomeDashboard.walkWindow(anyTime, minutesNow))
    }

    // -------------------------------------------------------------------------------------------
    // Care streak
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the streak is seven days ending today`() {
        val streak = HomeDashboard.streak(emptyList(), emptyList(), now())

        assertEquals(7, streak.size)
        assertEquals(CareSchedule.startOfDay(now()), streak.last().dayStart)
        assertTrue(streak.last().isToday)
        assertFalse(streak.first().isToday)
        assertEquals(dayStart(daysAgo = 6), streak.first().dayStart)
    }

    @Test
    fun `a past day is full only when every task due that day was done that day`() {
        // A daily routine with two tasks, started well before the window.
        val care = care(
            pet(1, "Krishala"),
            routines = listOf(routine(id = 10, petId = 1)),
            tasks = listOf(
                task(id = 100, petId = 1, routineId = 10, title = "Breakfast", time = 8 * 60),
                task(id = 101, petId = 1, routineId = 10, title = "Dinner", time = 19 * 60)
            )
        )

        val completions = listOf(
            // Two days ago: both done → full.
            completion(taskId = 100, daysAgo = 2, hour = 8),
            completion(taskId = 101, daysAgo = 2, hour = 19),
            // Yesterday: only one done → missed.
            completion(taskId = 100, daysAgo = 1, hour = 8)
        )

        val streak = HomeDashboard.streak(listOf(care), completions, now())

        assertTrue(streak[4].isFull)   // two days ago
        assertFalse(streak[5].isFull)  // yesterday
        assertFalse(streak[6].isFull)  // today, nothing done yet
    }

    @Test
    fun `a tick late in the evening counts for its own day, not the next`() {
        val care = care(
            pet(1, "Krishala"),
            routines = listOf(routine(id = 10, petId = 1)),
            tasks = listOf(task(id = 100, petId = 1, routineId = 10, title = "Dinner", time = 19 * 60))
        )
        // 23:59 yesterday.
        val lateTick = CareCompletion(
            taskId = 100,
            petId = 1,
            dayStart = dayStart(daysAgo = 1),
            completedAt = CareSchedule.endOfDay(dayStart(daysAgo = 1)) - 30_000
        )

        val streak = HomeDashboard.streak(listOf(care), listOf(lateTick), now())

        assertTrue(streak[5].isFull)
        assertFalse(streak[6].isFull)
    }

    @Test
    fun `a day with nothing scheduled is not full`() {
        // A routine that only starts today: the six days before had nothing due.
        val care = care(
            pet(1, "Krishala"),
            routines = listOf(routine(id = 10, petId = 1, startDate = dayStart(daysAgo = 0))),
            tasks = listOf(task(id = 100, petId = 1, routineId = 10, title = "Walk", time = 7 * 60))
        )

        val streak = HomeDashboard.streak(listOf(care), emptyList(), now())

        assertTrue(streak.take(6).none { it.isFull })
    }

    @Test
    fun `today fills from the task's own completion even before its history row is read`() {
        val care = care(
            pet(1, "Krishala"),
            routines = listOf(routine(id = 10, petId = 1)),
            tasks = listOf(
                task(id = 100, petId = 1, routineId = 10, title = "Walk", time = 7 * 60, completedAt = now() - 60_000)
            )
        )

        val state = HomeDashboard.build(null, listOf(care), completions = emptyList(), nowMillis = now())

        assertTrue(state.streak.last().isFull)
        assertEquals(1, state.streakFullDays)
    }

    // -------------------------------------------------------------------------------------------
    // Insight
    // -------------------------------------------------------------------------------------------

    @Test
    fun `insight says nothing is scheduled, or that everything is done`() {
        val minutesNow = 17 * 60 + 38

        assertEquals(Insight.NothingScheduled, HomeDashboard.insightFor(emptyList(), minutesNow, fullDays = 3))

        val allDone = today(walk(time = 7 * 60, isDone = true))
        assertEquals(Insight.AllDone(3), HomeDashboard.insightFor(allDone, minutesNow, fullDays = 3))
    }

    @Test
    fun `insight names the pet holding most of the open tasks, planned around another pet's later task`() {
        val ddjw = pet(2, "Ddjw", PetGender.MALE)
        val krishala = pet(1, "Krishala", PetGender.FEMALE)
        val today = listOf(
            TodayTask(task(1, 2, 10, "Walk", time = 17 * 60 + 45), ddjw, isDone = false),
            TodayTask(task(2, 1, 11, "Brush coat", time = 18 * 60), krishala, isDone = false),
            TodayTask(task(3, 2, 10, "Dinner", time = 19 * 60), ddjw, isDone = false),
            TodayTask(task(4, 1, 11, "Play", time = 20 * 60), krishala, isDone = false),
            TodayTask(task(5, 2, 10, "Night meds", time = 21 * 60), ddjw, isDone = false)
        )

        val insight = HomeDashboard.insightFor(today, 17 * 60 + 38, fullDays = 0)

        assertEquals(
            Insight.BusiestPet(
                petName = "Ddjw",
                petGender = PetGender.MALE,
                petOpenCount = 3,
                totalOpenCount = 5,
                firstTaskTitle = "Walk",
                laterTaskTitle = "Brush coat",
                laterTaskMinutes = 18 * 60
            ),
            insight
        )
    }

    @Test
    fun `without a busiest pet, an overdue task is named first`() {
        val krishala = pet(1, "Krishala")
        val today = listOf(
            TodayTask(task(1, 1, 10, "Lunch", time = 13 * 60), krishala, isDone = false),
            TodayTask(task(2, 1, 10, "Dinner", time = 19 * 60), krishala, isDone = false)
        )

        // One pet only, so there is no *other* pet's task to plan around.
        val insight = HomeDashboard.insightFor(today, 17 * 60 + 38, fullDays = 0)

        assertEquals(Insight.Overdue(taskTitle = "Lunch", petName = "Krishala", overdueCount = 1), insight)
    }

    // -------------------------------------------------------------------------------------------
    // Coming up this week, and the next vet visit
    // -------------------------------------------------------------------------------------------

    @Test
    fun `coming up skips daily care and lists each task once on its next day`() {
        val care = care(
            pet(1, "Krishala"),
            routines = listOf(
                routine(id = 10, petId = 1),                                     // daily
                routine(id = 11, petId = 1, repeatUnit = RepeatUnit.WEEK,        // weekly, on Fridays
                    startDate = dayStart(daysAgo = -1))
            ),
            tasks = listOf(
                task(id = 100, petId = 1, routineId = 10, title = "Breakfast", time = 8 * 60),
                task(id = 101, petId = 1, routineId = 11, title = "Coat brush", time = 18 * 60,
                    category = TaskCategory.GROOMING)
            )
        )

        val items = HomeDashboard.comingUp(listOf(care), now())

        assertEquals(listOf("Coat brush"), items.map { it.task.title })
        assertEquals(dayStart(daysAgo = -1), items.single().dayStart)
        assertEquals("Routine 11", items.single().routineTitle)
    }

    @Test
    fun `coming up starts tomorrow and is capped`() {
        // Six every-other-day tasks starting today: each next falls in two days' time, never today.
        val tasks = (1..6).map { task(id = it.toLong(), petId = 1, routineId = 20, title = "Task $it", time = it * 60) }
        val care = care(
            pet(1, "Krishala"),
            routines = listOf(routine(id = 20, petId = 1, repeatInterval = 2, startDate = dayStart(daysAgo = 0))),
            tasks = tasks
        )

        val items = HomeDashboard.comingUp(listOf(care), now())

        assertEquals(HomeDashboard.MAX_COMING_UP, items.size)
        assertTrue(items.all { it.dayStart == dayStart(daysAgo = -2) })
        // Ordered by time within the day.
        assertEquals(listOf("Task 1", "Task 2", "Task 3", "Task 4"), items.map { it.task.title })
    }

    @Test
    fun `next vet is the soonest vet visit across every pet`() {
        val a = care(
            pet(1, "Krishala"),
            routines = listOf(routine(id = 10, petId = 1, repeatUnit = RepeatUnit.WEEK, startDate = dayStart(daysAgo = -5))),
            tasks = listOf(task(100, 1, 10, "Annual vaccination", time = 10 * 60, category = TaskCategory.VET_VISIT))
        )
        val b = care(
            pet(2, "Ddjw"),
            routines = listOf(routine(id = 20, petId = 2, repeatUnit = RepeatUnit.WEEK, startDate = dayStart(daysAgo = -4))),
            tasks = listOf(task(200, 2, 20, "Rabies booster", time = 11 * 60, category = TaskCategory.VET_VISIT))
        )

        val visit = HomeDashboard.nextVetVisit(listOf(a, b), now())

        assertEquals("Ddjw", visit?.petName)
        assertNull(HomeDashboard.nextVetVisit(emptyList(), now()))
    }

    // -------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------

    /** Thursday 17 September 2026, 17:38 local. */
    private fun now(): Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 17, 17, 38, 0)
    }.timeInMillis

    /** Local midnight [daysAgo] days before today; negative for days ahead. */
    private fun dayStart(daysAgo: Int): Long = Calendar.getInstance().apply {
        timeInMillis = CareSchedule.startOfDay(now())
        add(Calendar.DAY_OF_MONTH, -daysAgo)
    }.timeInMillis

    private fun completion(taskId: Long, daysAgo: Int, hour: Int): CareCompletion {
        val day = dayStart(daysAgo)
        return CareCompletion(
            taskId = taskId,
            petId = 1,
            dayStart = day,
            completedAt = day + hour * 60 * 60 * 1000L
        )
    }

    private fun pet(id: Long, name: String, gender: PetGender = PetGender.UNKNOWN) =
        Pet(id = id, ownerId = 1L, name = name, species = PetSpecies.DOG, gender = gender)

    /** A routine that has been running daily since the start of the month unless told otherwise. */
    private fun routine(
        id: Long,
        petId: Long,
        startDate: Long = dayStart(daysAgo = 16),
        repeatInterval: Int = 1,
        repeatUnit: RepeatUnit = RepeatUnit.DAY
    ) = CareRoutine(
        id = id,
        petId = petId,
        title = "Routine $id",
        repeatInterval = repeatInterval,
        repeatUnit = repeatUnit,
        startDate = startDate
    )

    private fun task(
        id: Long,
        petId: Long,
        routineId: Long,
        title: String,
        time: Int?,
        category: TaskCategory = TaskCategory.OTHER,
        completedAt: Long? = null
    ) = CareTask(
        id = id,
        petId = petId,
        routineId = routineId,
        title = title,
        category = category,
        timeOfDayMinutes = time,
        dueAt = 0L,
        isCompleted = completedAt != null,
        completedAt = completedAt
    )

    private fun care(pet: Pet, routines: List<CareRoutine>, tasks: List<CareTask>) =
        PetCareData(pet, routines, tasks, expenses = emptyList())

    /** A walk for [HomeDashboard.walkWindow], which only reads the category, time and done flag. */
    private fun walk(time: Int?, isDone: Boolean = false) = TodayTask(
        task = task(id = 1, petId = 1, routineId = 10, title = "Walk", time = time, category = TaskCategory.WALK),
        pet = pet(1, "Ddjw"),
        isDone = isDone
    )

    private fun today(vararg items: TodayTask): List<TodayTask> = items.toList()
}
