package com.prash.petcare.util

import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.ExpenseCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Unit tests for [MonthlySpending], the arithmetic behind the Money tab's total card and legend.
 *
 * The cases concentrate on the edges that fail quietly: which month an expense on the 1st belongs
 * to, time zones either side of Greenwich, the year boundary, and a previous month with nothing in it.
 */
class MonthlySpendingTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val kathmandu = TimeZone.getTimeZone("Asia/Kathmandu") // UTC+5:45
    private val newYork = TimeZone.getTimeZone("America/New_York") // UTC-4 in September

    /** Midnight UTC on a calendar date — how the date picker stores `incurredOn`. */
    private fun storedDate(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day)
        }.timeInMillis

    /** A moment in a given zone, for "now". */
    private fun localMoment(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    private var nextId = 1L

    private fun expense(
        amount: Double,
        category: ExpenseCategory,
        incurredOn: Long,
        petId: Long = 1L
    ) = Expense(
        id = nextId++,
        petId = petId,
        title = "Expense",
        amount = amount,
        category = category,
        incurredOn = incurredOn
    )

    @Test
    fun `the design's September figures come out as drawn`() {
        val expenses = listOf(
            expense(4665.0, ExpenseCategory.MEDICATION, storedDate(2026, 9, 12), petId = 1),
            expense(425.0, ExpenseCategory.FOOD, storedDate(2026, 9, 9), petId = 2),
            expense(150.0, ExpenseCategory.GROOMING, storedDate(2026, 9, 3), petId = 1),
            // August: 5240 / 1.12 ≈ 4679, so this month is 12% up.
            expense(4679.0, ExpenseCategory.VET, storedDate(2026, 8, 20))
        )

        val summary = MonthlySpending.summarise(expenses, localMoment(kathmandu, 2026, 9, 14, 12), kathmandu)

        assertEquals(5240.0, summary.total, 0.001)
        assertEquals(3, summary.expenseCount)
        assertEquals(2, summary.petCount)
        assertEquals(12, summary.changePercent)
        assertEquals(
            listOf(ExpenseCategory.MEDICATION, ExpenseCategory.FOOD, ExpenseCategory.GROOMING),
            summary.breakdown.map { it.category }
        )
        // 4665 / 5240 = 89%, 425 / 5240 = 8%, 150 / 5240 = 3%.
        assertEquals(listOf(89, 8, 3), summary.breakdown.map { Math.round(it.share * 100) })
        assertEquals(storedDate(2026, 9, 1), summary.monthStart)
        assertEquals(storedDate(2026, 8, 1), summary.previousMonthStart)
    }

    @Test
    fun `an expense dated the 1st belongs to that month in a zone behind UTC`() {
        // In New York, local midnight on 1 Sept is 04:00 UTC — after the stored 00:00 UTC. A local
        // boundary would push this expense into August.
        val onTheFirst = expense(100.0, ExpenseCategory.FOOD, storedDate(2026, 9, 1))
        val summary = MonthlySpending.summarise(listOf(onTheFirst), localMoment(newYork, 2026, 9, 1, 9), newYork)

        assertEquals(100.0, summary.total, 0.001)
    }

    @Test
    fun `an expense dated the last day stays in its month in a zone ahead of UTC`() {
        val onTheLast = expense(100.0, ExpenseCategory.FOOD, storedDate(2026, 8, 31))
        val summary = MonthlySpending.summarise(listOf(onTheLast), localMoment(kathmandu, 2026, 9, 1, 1), kathmandu)

        // Not counted in September; and since August had it, September's nothing is a 100% fall.
        assertEquals(0.0, summary.total, 0.001)
        assertEquals(-100, summary.changePercent)
    }

    @Test
    fun `the current month follows the device's clock, not UTC's`() {
        // 00:30 on 1 October in Kathmandu is still 30 September in UTC. For the user it is October.
        val now = localMoment(kathmandu, 2026, 10, 1, 0, 30)
        val summary = MonthlySpending.summarise(emptyList(), now, kathmandu)

        assertEquals(storedDate(2026, 10, 1), summary.monthStart)
        assertEquals(storedDate(2026, 9, 1), summary.previousMonthStart)
    }

    @Test
    fun `January compares against December of the previous year`() {
        val expenses = listOf(
            expense(200.0, ExpenseCategory.TOYS, storedDate(2027, 1, 5)),
            expense(100.0, ExpenseCategory.TOYS, storedDate(2026, 12, 28))
        )
        val summary = MonthlySpending.summarise(expenses, localMoment(utc, 2027, 1, 10, 12), utc)

        assertEquals(storedDate(2026, 12, 1), summary.previousMonthStart)
        assertEquals(100, summary.changePercent)
    }

    @Test
    fun `no spending last month leaves the change out rather than inventing one`() {
        val expenses = listOf(expense(50.0, ExpenseCategory.VET, storedDate(2026, 9, 2)))
        val summary = MonthlySpending.summarise(expenses, localMoment(utc, 2026, 9, 14, 12), utc)

        assertNull(summary.changePercent)
    }

    @Test
    fun `older months are ignored`() {
        val expenses = listOf(
            expense(50.0, ExpenseCategory.VET, storedDate(2026, 9, 2)),
            expense(999.0, ExpenseCategory.VET, storedDate(2026, 6, 2))
        )
        val summary = MonthlySpending.summarise(expenses, localMoment(utc, 2026, 9, 14, 12), utc)

        assertEquals(50.0, summary.total, 0.001)
        assertEquals(1, summary.expenseCount)
        assertNull(summary.changePercent)
    }

    @Test
    fun `a month of zero amounts has zero shares instead of dividing by zero`() {
        val expenses = listOf(expense(0.0, ExpenseCategory.OTHER, storedDate(2026, 9, 2)))
        val summary = MonthlySpending.summarise(expenses, localMoment(utc, 2026, 9, 14, 12), utc)

        assertEquals(0f, summary.breakdown.single().share, 0f)
    }

    @Test
    fun `change percent rounds and signs correctly`() {
        assertEquals(12, MonthlySpending.changePercent(112.0, 100.0))
        assertEquals(-25, MonthlySpending.changePercent(75.0, 100.0))
        assertEquals(0, MonthlySpending.changePercent(100.0, 100.0))
        assertNull(MonthlySpending.changePercent(100.0, 0.0))
    }
}
